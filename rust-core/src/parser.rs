use crate::{EstadoMateria, Materia};
use chrono::{Duration, NaiveDate, Utc};
use scraper::{Html, Selector};

/// Intenta parsear una fecha en formatos habituales del SIU Guaraní (dd/mm/aaaa, aaaa-mm-dd, etc.)
pub fn parsear_fecha(texto: &str) -> Option<NaiveDate> {
    let limpio = texto.trim();
    for token in limpio.split_whitespace() {
        let t = token.trim_matches(|c: char| !c.is_numeric() && c != '/' && c != '-');
        if let Ok(d) = NaiveDate::parse_from_str(t, "%d/%m/%Y") {
            return Some(d);
        }
        if let Ok(d) = NaiveDate::parse_from_str(t, "%Y-%m-%d") {
            return Some(d);
        }
        if let Ok(d) = NaiveDate::parse_from_str(t, "%d-%m-%Y") {
            return Some(d);
        }
    }
    None
}

/// Calcula los días restantes de vigencia de una regularidad según el reglamento de FI-UNJu
/// (Vigencia típica de 2 años y medio = ~912 días desde la fecha de regularización,
/// o contra fecha de vencimiento explícita si ya es futura).
pub fn calcular_dias_vencimiento(fecha_str: &str, hoy_opt: Option<NaiveDate>) -> Option<i64> {
    let fecha = parsear_fecha(fecha_str)?;
    let hoy = hoy_opt.unwrap_or_else(|| Utc::now().date_naive());

    // Si la fecha ya es posterior a hoy en más de 30 días, asumimos que es una fecha de vencimiento explícita
    let fecha_vencimiento = if fecha > hoy + Duration::days(30) {
        fecha
    } else {
        // En FI-UNJu la regularidad dura 2.5 años (912 días) desde que se regularizó
        fecha + Duration::days(912)
    };

    Some((fecha_vencimiento - hoy).num_days())
}

/// Determina si una respuesta HTML corresponde a la pantalla de login (falso 200 OK de SIU-Toba)
pub fn es_pantalla_login(html: &str) -> bool {
    let doc = Html::parse_document(html);
    if let Ok(sel) = Selector::parse("input[name='password'], #guarani_form_login, input[name='usuario']") {
        if doc.select(&sel).next().is_some() {
            return true;
        }
    }
    html.contains("guarani_form_login") || (html.contains("Iniciar Sesión") && html.contains("password"))
}

/// Parsea la tabla del plan de estudios de SIU Guaraní 3
pub fn parsear_tabla_plan(html: &str) -> Vec<Materia> {
    parsear_tabla_plan_con_fecha(html, None)
}

/// Versión parametrizable para tests que permite fijar la fecha actual de referencia
pub fn parsear_tabla_plan_con_fecha(html: &str, hoy_ref: Option<NaiveDate>) -> Vec<Materia> {
    let document = Html::parse_document(html);
    
    let row_selector = Selector::parse("table.table-bordered tbody tr, table.tabla_plan tbody tr, table.table tbody tr")
        .expect("Selector de filas válido");
    let cell_selector = Selector::parse("td").expect("Selector de celdas válido");

    let mut materias = Vec::with_capacity(40);

    for row in document.select(&row_selector) {
        let celdas: Vec<_> = row.select(&cell_selector).collect();
        if celdas.len() >= 4 {
            let codigo = celdas[0].text().collect::<String>().trim().to_string();
            let nombre = celdas[1].text().collect::<String>().trim().to_string();
            
            if codigo.is_empty() && nombre.is_empty() {
                continue;
            }

            let anio: u8 = if celdas.len() >= 5 {
                celdas[2].text().collect::<String>().trim().parse().unwrap_or(1)
            } else {
                1
            };

            let (estado_str, nota_str) = if celdas.len() >= 5 {
                (
                    celdas[3].text().collect::<String>().trim().to_lowercase(),
                    celdas[4].text().collect::<String>().trim().to_string(),
                )
            } else {
                (
                    celdas[2].text().collect::<String>().trim().to_lowercase(),
                    celdas[3].text().collect::<String>().trim().to_string(),
                )
            };

            let estado = if estado_str.contains("aprob") || estado_str.contains("promoc") {
                EstadoMateria::Aprobada
            } else if estado_str.contains("regul") {
                EstadoMateria::Regular
            } else {
                EstadoMateria::Pendiente
            };

            let nota = if nota_str.is_empty() || nota_str == "-" {
                None
            } else {
                Some(nota_str)
            };

            // Buscar fecha de vencimiento o de regularización en celdas adicionales (ej: columna 5 o 6)
            let dias_vencimiento = if estado == EstadoMateria::Regular {
                let mut dias = None;
                for celda in celdas.iter().skip(3) {
                    let txt = celda.text().collect::<String>();
                    if let Some(calc) = calcular_dias_vencimiento(&txt, hoy_ref) {
                        dias = Some(calc);
                        break;
                    }
                }
                dias
            } else {
                None
            };

            materias.push(Materia {
                codigo,
                nombre,
                anio,
                estado,
                nota,
                dias_vencimiento,
            });
        }
    }

    materias
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_detectar_pantalla_login() {
        let html_login = r#"
            <html>
                <body>
                    <form id="guarani_form_login" action="/acceso" method="post">
                        <input type="text" name="usuario" />
                        <input type="password" name="password" />
                        <button type="submit">Ingresar</button>
                    </form>
                </body>
            </html>
        "#;
        assert!(es_pantalla_login(html_login));

        let html_normal = r#"
            <html>
                <body>
                    <h1>Bienvenido Estudiante</h1>
                    <div>Plan de Estudio</div>
                </body>
            </html>
        "#;
        assert!(!es_pantalla_login(html_normal));
    }

    #[test]
    fn test_calculo_vencimiento_regularidad_fi_unju() {
        let hoy = NaiveDate::from_ymd_opt(2026, 10, 14).unwrap();

        // 1. Materia regularizada el 14/10/2025 (hace 1 año exacto)
        // Vigencia 2.5 años (912 días) -> quedan 912 - 365 = 547 días
        let dias = calcular_dias_vencimiento("14/10/2025", Some(hoy));
        assert!(dias.is_some());
        assert_eq!(dias.unwrap(), 547);

        // 2. Materia regularizada el 14/10/2023 (hace 3 años) -> ya vencida
        let dias_vencida = calcular_dias_vencimiento("14/10/2023", Some(hoy));
        assert!(dias_vencida.is_some());
        assert!(dias_vencida.unwrap() < 0);

        // 3. Fecha de vencimiento explícita (ej: 31/03/2027)
        let dias_explicita = calcular_dias_vencimiento("31/03/2027", Some(hoy));
        assert!(dias_explicita.is_some());
        assert_eq!(dias_explicita.unwrap(), 168);
    }

    #[test]
    fn test_parsear_tabla_plan_con_vencimiento() {
        let hoy = NaiveDate::from_ymd_opt(2026, 10, 14).unwrap();

        let html_plan = r#"
            <table class="table table-bordered">
                <thead>
                    <tr>
                        <th>Código</th>
                        <th>Materia</th>
                        <th>Año</th>
                        <th>Estado</th>
                        <th>Nota</th>
                        <th>Fecha Reg.</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>001</td>
                        <td>Álgebra y Geometría Analítica</td>
                        <td>1</td>
                        <td>Aprobada</td>
                        <td>9</td>
                        <td>10/12/2024</td>
                    </tr>
                    <tr>
                        <td>002</td>
                        <td>Análisis Matemático I</td>
                        <td>1</td>
                        <td>Regular</td>
                        <td>-</td>
                        <td>14/10/2025</td>
                    </tr>
                    <tr>
                        <td>003</td>
                        <td>Programación I</td>
                        <td>1</td>
                        <td>Pendiente</td>
                        <td></td>
                        <td>-</td>
                    </tr>
                </tbody>
            </table>
        "#;

        let materias = parsear_tabla_plan_con_fecha(html_plan, Some(hoy));
        assert_eq!(materias.len(), 3);

        // Aprobada: no tiene vencimiento
        assert_eq!(materias[0].codigo, "001");
        assert_eq!(materias[0].estado, EstadoMateria::Aprobada);
        assert_eq!(materias[0].dias_vencimiento, None);

        // Regular: regularizada hace 1 año -> 547 días restantes
        assert_eq!(materias[1].codigo, "002");
        assert_eq!(materias[1].estado, EstadoMateria::Regular);
        assert_eq!(materias[1].dias_vencimiento, Some(547));

        // Pendiente: no tiene vencimiento
        assert_eq!(materias[2].codigo, "003");
        assert_eq!(materias[2].estado, EstadoMateria::Pendiente);
        assert_eq!(materias[2].dias_vencimiento, None);
    }
}
