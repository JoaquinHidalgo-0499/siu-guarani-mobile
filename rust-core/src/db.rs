use crate::{EstadoMateria, Materia};
use rusqlite::{params, Connection, Result};

/// Inicializa las tablas y configuraciones de rendimiento en SQLite (Modo WAL)
pub fn init_db(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        PRAGMA temp_store = MEMORY;
        PRAGMA cache_size = -2000;

        CREATE TABLE IF NOT EXISTS materias (
            codigo TEXT PRIMARY KEY,
            nombre TEXT NOT NULL,
            anio INTEGER NOT NULL,
            estado TEXT NOT NULL,
            nota TEXT,
            dias_vencimiento INTEGER,
            actualizado_en DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        ",
    )?;
    Ok(())
}

/// Guarda o actualiza las materias en la base de datos dentro de una transacción única
pub fn guardar_materias(conn: &mut Connection, materias: &[Materia]) -> Result<()> {
    let tx = conn.transaction()?;
    {
        let mut stmt = tx.prepare(
            "
            INSERT INTO materias (codigo, nombre, anio, estado, nota, dias_vencimiento, actualizado_en)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, CURRENT_TIMESTAMP)
            ON CONFLICT(codigo) DO UPDATE SET
                nombre = excluded.nombre,
                anio = excluded.anio,
                estado = excluded.estado,
                nota = excluded.nota,
                dias_vencimiento = excluded.dias_vencimiento,
                actualizado_en = CURRENT_TIMESTAMP;
            ",
        )?;

        for m in materias {
            let estado_str = match m.estado {
                EstadoMateria::Aprobada => "Aprobada",
                EstadoMateria::Regular => "Regular",
                EstadoMateria::Pendiente => "Pendiente",
            };

            stmt.execute(params![
                m.codigo,
                m.nombre,
                m.anio,
                estado_str,
                m.nota,
                m.dias_vencimiento,
            ])?;
        }
    }
    tx.commit()?;
    Ok(())
}

/// Recupera todas las materias ordenadas por año y código
pub fn obtener_materias(conn: &Connection) -> Result<Vec<Materia>> {
    let mut stmt = conn.prepare(
        "
        SELECT codigo, nombre, anio, estado, nota, dias_vencimiento
        FROM materias
        ORDER BY anio ASC, codigo ASC;
        ",
    )?;

    let iter = stmt.query_map([], |row| {
        let estado_str: String = row.get(3)?;
        let estado = match estado_str.as_str() {
            "Aprobada" => EstadoMateria::Aprobada,
            "Regular" => EstadoMateria::Regular,
            _ => EstadoMateria::Pendiente,
        };

        Ok(Materia {
            codigo: row.get(0)?,
            nombre: row.get(1)?,
            anio: row.get(2)?,
            estado,
            nota: row.get(4)?,
            dias_vencimiento: row.get(5)?,
        })
    })?;

    let mut result = Vec::new();
    for materia in iter {
        result.push(materia?);
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_db_init_and_crud() {
        let mut conn = Connection::open_in_memory().unwrap();
        init_db(&conn).unwrap();

        let materias = vec![
            Materia {
                codigo: "001".to_string(),
                nombre: "Álgebra".to_string(),
                anio: 1,
                estado: EstadoMateria::Aprobada,
                nota: Some("10".to_string()),
                dias_vencimiento: None,
            },
            Materia {
                codigo: "002".to_string(),
                nombre: "Física I".to_string(),
                anio: 1,
                estado: EstadoMateria::Regular,
                nota: None,
                dias_vencimiento: Some(180),
            },
        ];

        guardar_materias(&mut conn, &materias).unwrap();

        let recuperadas = obtener_materias(&conn).unwrap();
        assert_eq!(recuperadas.len(), 2);
        assert_eq!(recuperadas[0].codigo, "001");
        assert_eq!(recuperadas[0].estado, EstadoMateria::Aprobada);
        assert_eq!(recuperadas[1].dias_vencimiento, Some(180));
    }
}
