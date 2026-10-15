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
            .timeout(Duration::from_secs(15))
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
        let login_url = format!("{}/acceso", self.base_url);

        // Paso 1: GET inicial para sembrar la cookie de sesión limpia
        self.http
            .get(&login_url)
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        // Paso 2: POST con credenciales del alumno
        let params = [
            ("usuario", dni.as_str()),
            ("password", clave.as_str()),
            ("guarani_form_login", "1"),
        ];

        let resp = self
            .http
            .post(&login_url)
            .form(&params)
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        let html_texto = resp.text().await.map_err(|_| GuaraniError::RedError)?;

        if parser::es_pantalla_login(&html_texto) {
            return Err(GuaraniError::CredencialesInvalidas);
        }

        Ok(true)
    }

    async fn obtener_plan_estudios_async(&self) -> Result<Vec<Materia>, GuaraniError> {
        let url = format!("{}/plan_estudio", self.base_url);
        let resp = self
            .http
            .get(&url)
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        let html_texto = resp.text().await.map_err(|_| GuaraniError::RedError)?;

        if parser::es_pantalla_login(&html_texto) {
            return Err(GuaraniError::CredencialesInvalidas);
        }

        let materias = parser::parsear_tabla_plan(&html_texto);
        Ok(materias)
    }

    async fn descargar_certificado_pdf_async(&self, endpoint_tramite: String) -> Result<Vec<u8>, GuaraniError> {
        let url = format!("{}/{}", self.base_url, endpoint_tramite.trim_start_matches('/'));
        let resp = self
            .http
            .get(&url)
            .send()
            .await
            .map_err(|_| GuaraniError::RedError)?;

        if !resp.status().is_success() {
            return Err(GuaraniError::RedError);
        }

        let bytes = resp.bytes().await.map_err(|_| GuaraniError::RedError)?;
        
        if bytes.starts_with(b"<!DOCTYPE") || bytes.starts_with(b"<html") {
            return Err(GuaraniError::CredencialesInvalidas);
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
        // Verificamos que ejecute dentro del runtime de Tokio sin abortar el proceso
        let res = client.autenticar("00000000".to_string(), "clave_erronea_test".to_string());
        assert!(matches!(res, Err(GuaraniError::CredencialesInvalidas) | Err(GuaraniError::RedError)));
    }
}
