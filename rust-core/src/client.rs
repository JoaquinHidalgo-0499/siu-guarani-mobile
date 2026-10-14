use crate::db;
use crate::parser;
use crate::{GuaraniError, Materia};
use reqwest::Client;
use std::sync::Arc;
use std::time::Duration;

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
    pub async fn autenticar(&self, dni: String, clave: String) -> Result<bool, GuaraniError> {
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

    /// Descarga y parsea el Plan de Estudios
    pub async fn obtener_plan_estudios(&self) -> Result<Vec<Materia>, GuaraniError> {
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

    /// Descarga un Certificado PDF (BEGU / Alumno Regular) usando la sesión autenticada en Rust
    pub async fn descargar_certificado_pdf(&self, endpoint_tramite: String) -> Result<Vec<u8>, GuaraniError> {
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
        
        // Si el servidor devolvió un HTML (ej: redirección a login por sesión caducada)
        if bytes.starts_with(b"<!DOCTYPE") || bytes.starts_with(b"<html") {
            return Err(GuaraniError::CredencialesInvalidas);
        }

        Ok(bytes.to_vec())
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
