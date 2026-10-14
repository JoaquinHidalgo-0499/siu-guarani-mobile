pub mod client;
pub mod db;
pub mod parser;

pub use client::GuaraniClient;

uniffi::setup_scaffolding!();

#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct Materia {
    pub codigo: String,
    pub nombre: String,
    pub anio: u8,
    pub estado: EstadoMateria,
    pub nota: Option<String>,
    pub dias_vencimiento: Option<i64>,
}

#[derive(uniffi::Enum, Debug, Clone, PartialEq)]
pub enum EstadoMateria {
    Aprobada,
    Regular,
    Pendiente,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum GuaraniError {
    #[error("Error de conexión con el servidor de la UNJu")]
    RedError,
    #[error("DNI o contraseña incorrectos en SIU Guaraní")]
    CredencialesInvalidas,
    #[error("Error al procesar la estructura HTML del SIU")]
    ParseError,
    #[error("Error en base de datos local")]
    DbError,
}
