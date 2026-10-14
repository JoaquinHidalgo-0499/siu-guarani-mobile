use crate::{EstadoMateria, Materia};
use scraper::{Html, Selector};

/// Determina si una respuesta HTML corresponde a la pantalla de login (falso 200 OK de SIU-Toba)
pub fn es_pantalla_login(html: &str) -> bool {
    let doc = Html::parse_document(html);
    if let Ok(sel) = Selector::parse("input[name='password'], #guarani_form_login, input[name='usuario']") {
        if doc.select(&sel).next().is_some() {
            return true;
        }
    }
    // Verificación adicional por texto característico si no hay inputs
    html.contains("guarani_form_login") || html.contains("Iniciar Sesión") && html.contains("password")
}

/// Parsea la tabla del plan de estudios de SIU Guaraní 3
pub fn parsear_tabla_plan(html: &str) -> Vec<Materia> {
    let document = Html::parse_document(html);
    
    // Selectores para la estructura típica de tabla de SIU Guaraní / Bootstrap
    let row_selector = Selector::parse("table.table-bordered tbody tr, table.tabla_plan tbody tr, table.table tbody tr")
        .expect("Selector de filas válido");
    let cell_selector = Selector::parse("td").expect("Selector de celdas válido");

    let mut materias = Vec::with_capacity(40);

    for row in document.select(&row_selector) {
        let celdas: Vec<_> = row.select(&cell_selector).collect();
        if celdas.len() >= 4 {
            let codigo = celdas[0].text().collect::<String>().trim().to_string();
            let nombre = celdas[1].text().collect::<String>().trim().to_string();
            
            // Si el código o el nombre están vacíos, probablemente sea una fila de encabezado o separador
            if codigo.is_empty() && nombre.is_empty() {
                continue;
            }

            // Detectar año (si existe la columna o tomar por defecto 1)
            let anio: u8 = if celdas.len() >= 5 {
                celdas[2].text().collect::<String>().trim().parse().unwrap_or(1)
            } else {
                1
            };

            // Detectar columna de estado y nota según el número de celdas
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

            materias.push(Materia {
                codigo,
                nombre,
                anio,
                estado,
                nota,
                dias_vencimiento: None,
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
    fn test_parsear_tabla_plan() {
        let html_plan = r#"
            <table class="table table-bordered">
                <thead>
                    <tr>
                        <th>Código</th>
                        <th>Materia</th>
                        <th>Año</th>
                        <th>Estado</th>
                        <th>Nota</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>001</td>
                        <td>Álgebra y Geometría Analítica</td>
                        <td>1</td>
                        <td>Aprobada</td>
                        <td>9</td>
                    </tr>
                    <tr>
                        <td>002</td>
                        <td>Análisis Matemático I</td>
                        <td>1</td>
                        <td>Regular</td>
                        <td>-</td>
                    </tr>
                    <tr>
                        <td>003</td>
                        <td>Programación I</td>
                        <td>1</td>
                        <td>Pendiente</td>
                        <td></td>
                    </tr>
                </tbody>
            </table>
        "#;

        let materias = parsear_tabla_plan(html_plan);
        assert_eq!(materias.len(), 3);

        assert_eq!(materias[0].codigo, "001");
        assert_eq!(materias[0].nombre, "Álgebra y Geometría Analítica");
        assert_eq!(materias[0].anio, 1);
        assert_eq!(materias[0].estado, EstadoMateria::Aprobada);
        assert_eq!(materias[0].nota, Some("9".to_string()));

        assert_eq!(materias[1].codigo, "002");
        assert_eq!(materias[1].estado, EstadoMateria::Regular);
        assert_eq!(materias[1].nota, None);

        assert_eq!(materias[2].codigo, "003");
        assert_eq!(materias[2].estado, EstadoMateria::Pendiente);
        assert_eq!(materias[2].nota, None);
    }
}
