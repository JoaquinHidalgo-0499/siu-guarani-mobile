use crate::db;
use crate::parser;
use crate::{GuaraniError, Materia};
use reqwest::Client;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tokio::runtime::Runtime;

fn get_runtime() -> &'static Runtime {
    static RUNTIME: OnceLock<Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Fallo al inicializar Tokio runtime")
    })
}

#[derive(uniffi::Object)]
pub struct GuaraniClient {
    http: Client,
    base_url: String,
}

#[uniffi::export]
impl GuaraniClient {
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Result<Arc<Self>, GuaraniError> {
        let http = Client::builder()
            .cookie_store(true)
            .user_agent("Mozilla/5.0 (Linux; Android 14; Moto E14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
            .timeout(Duration::from_secs(20))
            .build()
            .map_err(|_| GuaraniError::RedError)?;

        let trimmed_url = base_url.trim_end_matches('/').to_string();
        Ok(Arc::new(Self {
            http,
            base_url: trimmed_url,
        }))
    }

    /// Realiza el login contra SIU-Toba manejando la sesión inicial y cookies PHPSESSID
    pub fn autenticar(&self, dni: String, clave: String) -> Result<bool, GuaraniError> {
        get_runtime().block_on(self.autenticar_async(dni, clave))
    }

    /// Descarga y parsea el Plan de Estudios
    pub fn obtener_plan_estudios(&self) -> Result<Vec<Materia>, GuaraniError> {
        get_runtime().block_on(self.obtener_plan_estudios_async())
    }

    /// Descarga un Certificado PDF (BEGU / Alumno Regular) usando la sesión autenticada en Rust
    pub fn descargar_certificado_pdf(&self, endpoint_tramite: String) -> Result<Vec<u8>, GuaraniError> {
        get_runtime().block_on(self.descargar_certificado_pdf_async(endpoint_tramite))
    }

    /// Persiste las materias en la base de datos SQLite local
    pub fn guardar_en_cache(&self, db_path: String, materias: Vec<Materia>) -> Result<(), GuaraniError> {
        let mut conn = rusqlite::Connection::open(&db_path).map_err(|_| GuaraniError::DbError)?;
        db::init_db(&conn).map_err(|_| GuaraniError::DbError)?;
        db::guardar_materias(&mut conn, &materias).map_err(|_| GuaraniError::DbError)?;
        Ok(())
    }

    /// Obtiene las materias cacheadas localmente desde SQLite
    pub fn obtener_de_cache(&self, db_path: String) -> Result<Vec<Materia>, GuaraniError> {
        let conn = rusqlite::Connection::open(&db_path).map_err(|_| GuaraniError::DbError)?;
        db::init_db(&conn).map_err(|_| GuaraniError::DbError)?;
        let materias = db::obtener_materias(&conn).map_err(|_| GuaraniError::DbError)?;
        Ok(materias)
    }
}

// Métodos asíncronos internos protegidos por el Tokio Runtime
impl GuaraniClient {
    async fn autenticar_async(&self, dni: String, clave: String) -> Result<bool, GuaraniError> {
        let base = self.base_url.trim_end_matches('/');
        let login_get_url = format!("{}/acceso", base);
        let login_post_url = format!("{}/acceso?auth=form", base);

        // Paso 1: GET inicial a /acceso para recibir las cookies de sesión (siu_sess_...)
        self.http
            .get(&login_get_url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        // Paso 2: POST con credenciales exactas según el formulario de SIU-Toba
        let params = [
            ("usuario", dni.trim()),
            ("password", clave.as_str()),
            ("login", "Ingresar"),
            ("guarani_form_login", "1"),
        ];

        let resp = self
            .http
            .post(&login_post_url)
            .header("Referer", &login_get_url)
            .header("Origin", base)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .form(&params)
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        let final_url = resp.url().as_str().to_string();
        let html_texto = resp.text().await.map_err(|_| GuaraniError::RedError)?;

        // Si la respuesta contiene el error específico de login de SIU Guaraní
        if html_texto.contains("error_login") || html_texto.contains("El nombre de usuario y/o contrase") {
            return Err(GuaraniError::CredencialesInvalidas);
        }

        // Si fuimos redirigidos fuera de acceso (ej: a /unju/ o /inicio) o tiene indicadores de sesión activa
        if html_texto.contains("desconectar") || html_texto.contains("salir") || (!final_url.contains("/acceso") && !html_texto.contains("guarani_form_login")) {
            return Ok(true);
        }

        // Si seguimos en la pantalla de login
        if parser::es_pantalla_login(&html_texto) {
            return Err(GuaraniError::CredencialesInvalidas);
        }

        Ok(true)
    }

    async fn obtener_plan_estudios_async(&self) -> Result<Vec<Materia>, GuaraniError> {
        let base = self.base_url.trim_end_matches('/');

        // 1. Obtener Historia Académica (fuente oficial de calificaciones, promociones y regularidades)
        let hist_url = format!("{}/historia_academica", base);
        let mut materias_historia = Vec::new();
        let mut login_detectado = false;

        if let Ok(hist_resp) = self
            .http
            .get(&hist_url)
            .header("Referer", format!("{}/", base))
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .send()
            .await
        {
            if let Ok(hist_html) = hist_resp.text().await {
                if parser::es_pantalla_login(&hist_html) {
                    login_detectado = true;
                } else {
                    materias_historia = parser::parsear_historia_academica(&hist_html);
                }
            }
        }

        // 2. Obtener Plan de Estudios completo (para la malla curricular completa y correlativas)
        let plan_url = format!("{}/plan_estudio", base);
        let resp = self
            .http
            .get(&plan_url)
            .header("Referer", format!("{}/", base))
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        let html_texto = resp.text().await.map_err(|_| GuaraniError::RedError)?;

        if parser::es_pantalla_login(&html_texto) {
            if materias_historia.is_empty() {
                return Err(GuaraniError::CredencialesInvalidas);
            }
        } else {
            login_detectado = false;
        }

        if login_detectado && materias_historia.is_empty() {
            return Err(GuaraniError::CredencialesInvalidas);
        }

        let materias_plan = parser::parsear_tabla_plan(&html_texto);

        // 3. Unificar ambas listas para brindar la visión integral exacta al estudiante
        let materias = parser::unificar_plan_e_historia(materias_plan, materias_historia);
        Ok(materias)
    }

    async fn descargar_certificado_pdf_async(&self, endpoint_tramite: String) -> Result<Vec<u8>, GuaraniError> {
        let base = self.base_url.trim_end_matches('/');
        let endpoint = endpoint_tramite.trim_start_matches('/');
        let url = format!("{}/{}", base, endpoint);

        let referer = if endpoint.contains('/') {
            let prefix = endpoint.rsplit_once('/').map(|(p, _)| p).unwrap_or("");
            format!("{}/{}", base, prefix)
        } else {
            format!("{}/", base)
        };

        let resp = self
            .http
            .get(&url)
            .header("Referer", &referer)
            .header("Accept", "application/pdf,application/octet-stream,*/*")
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        if !resp.status().is_success() {
            return Err(GuaraniError::RedError);
        }

        let bytes = resp.bytes().await.map_err(|_| GuaraniError::RedError)?;
        
        if bytes.starts_with(b"<!DOCTYPE") || bytes.starts_with(b"<html") {
            let html_str = String::from_utf8_lossy(&bytes);
            if parser::es_pantalla_login(&html_str) {
                return Err(GuaraniError::CredencialesInvalidas);
            }
            return Err(GuaraniError::ParseError);
        }

        Ok(bytes.to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_login_con_credenciales_invalidas_no_hace_panic() {
        let client = GuaraniClient::new("https://alumnos.unju.edu.ar/unju".to_string()).unwrap();
        let res = client.autenticar("00000000".to_string(), "clave_erronea_test".to_string());
        assert!(matches!(res, Err(GuaraniError::CredencialesInvalidas) | Err(GuaraniError::RedError)));
    }
}
