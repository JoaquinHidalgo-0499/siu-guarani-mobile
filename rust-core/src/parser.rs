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

/// Extrae y desescapa fragmentos HTML inyectados vía JavaScript en SIU Guaraní 3 (Chulupí)
/// buscando llamadas a `kernel.renderer.on_arrival({"content": "..."})` o bloques con `"content": "..."`.
pub fn extraer_pagelets_html(raw_html: &str) -> String {
    let mut resultado = String::with_capacity(raw_html.len() * 2);
    resultado.push_str(raw_html);
    resultado.push('\n');

    let mut cursor = 0;
    while let Some(pos) = raw_html[cursor..].find("\"content\"") {
        let abs_pos = cursor + pos + 9;
        let rest = &raw_html[abs_pos..];
        let mut chars = rest.char_indices();
        let mut string_start = None;
        while let Some((idx, c)) = chars.next() {
            if c == ':' || c.is_whitespace() {
                continue;
            } else if c == '"' {
                string_start = Some(abs_pos + idx);
                break;
            } else {
                break;
            }
        }

        if let Some(start) = string_start {
            let bytes = raw_html.as_bytes();
            let mut i = start + 1;
            let mut escaped = false;
            let mut end_opt = None;
            while i < bytes.len() {
                let b = bytes[i];
                if escaped {
                    escaped = false;
                } else if b == b'\\' {
                    escaped = true;
                } else if b == b'"' {
                    end_opt = Some(i);
                    break;
                }
                i += 1;
            }

            if let Some(end) = end_opt {
                let slice = &raw_html[start..=end];
                if let Ok(decoded) = serde_json::from_str::<String>(slice) {
                    if decoded.contains("<table") || decoded.contains("<tr") || decoded.contains("<div") {
                        resultado.push_str(&decoded);
                        resultado.push('\n');
                    }
                }
                cursor = end + 1;
                continue;
            }
        }
        cursor = abs_pos;
    }

    resultado
}

/// Separa código y nombre de materias desde textos como "(042) Análisis Matemático I",
/// "042 - Álgebra Lineal" o "Programación I"
pub fn separar_codigo_y_nombre(texto: &str) -> (String, String) {
    let limpio = texto.trim();

    // Caso 1: "(042) Análisis Matemático I" o "Álgebra (042)"
    if let Some(open) = limpio.find('(') {
        if let Some(close) = limpio[open..].find(')') {
            let close = open + close;
            let codigo = limpio[open + 1..close].trim().to_string();
            let mut nombre = String::new();
            nombre.push_str(limpio[..open].trim());
            if !nombre.is_empty() && close + 1 < limpio.len() {
                nombre.push(' ');
            }
            nombre.push_str(limpio[close + 1..].trim());
            let nombre = nombre.trim().trim_start_matches('-').trim().to_string();
            if !codigo.is_empty() && !nombre.is_empty() {
                return (codigo, nombre);
            }
        }
    }

    // Caso 2: "042 - Álgebra Lineal" o "042 : ..."
    if let Some(sep) = limpio.find(" - ").or_else(|| limpio.find(" : ")) {
        let codigo = limpio[..sep].trim().to_string();
        let nombre = limpio[sep + 3..].trim().to_string();
        if !codigo.is_empty() && !nombre.is_empty() {
            return (codigo, nombre);
        }
    }

    // Caso 3: "APU102 Programación I"
    let parts: Vec<&str> = limpio.splitn(2, ' ').collect();
    if parts.len() == 2 && parts[0].len() <= 7 && parts[0].chars().all(|c| c.is_alphanumeric() || c == '-') {
        return (parts[0].to_string(), parts[1].trim().to_string());
    }

    // Si no tiene código explícito
    (String::new(), limpio.to_string())
}

/// Mapea un texto al estado de materia
pub fn parsear_estado_str(texto: &str) -> EstadoMateria {
    let t = texto.to_lowercase();
    if t.contains("aprob") || t.contains("promoc") || t.contains("equivalencia") {
        EstadoMateria::Aprobada
    } else if t.contains("regul") {
        EstadoMateria::Regular
    } else {
        EstadoMateria::Pendiente
    }
}

#[derive(Default, Debug)]
struct ColumnMap {
    col_codigo: Option<usize>,
    col_materia: Option<usize>,
    col_anio: Option<usize>,
    col_estado: Option<usize>,
    col_nota: Option<usize>,
    col_fecha: Option<usize>,
}

/// Parsea el plan de estudio y/o la historia académica desde el HTML completo de SIU Guaraní
pub fn parsear_tabla_plan(html: &str) -> Vec<Materia> {
    parsear_tabla_plan_con_fecha(html, None)
}

/// Parsea las materias soportando tanto el formato de `/plan_estudio` como el de `/historia_academica`
pub fn parsear_tabla_plan_con_fecha(html: &str, hoy_ref: Option<NaiveDate>) -> Vec<Materia> {
    let html_expandido = extraer_pagelets_html(html);
    let document = Html::parse_document(&html_expandido);

    let table_selector = Selector::parse("table").expect("Selector table");
    let row_selector = Selector::parse("tbody tr, tr").expect("Selector tr");
    let cell_selector = Selector::parse("td").expect("Selector td");
    let th_selector = Selector::parse("th").expect("Selector th");

    let mut materias = Vec::with_capacity(50);
    let mut codigos_vistos = std::collections::HashSet::new();

    for table in document.select(&table_selector) {
        // Analizar encabezados de la tabla para mapear columnas inteligentemente
        let mut map = ColumnMap::default();
        let mut tiene_encabezados_relevantes = false;

        for th in table.select(&th_selector) {
            let t = th.text().collect::<String>().to_lowercase();
            if t.contains("código") || t.contains("codigo") || t.contains("cod.") {
                map.col_codigo = Some(map.col_codigo.unwrap_or_else(|| { tiene_encabezados_relevantes = true; 0 }));
            }
        }

        // Mapear encabezados con índices exactos
        let th_list: Vec<_> = table.select(&th_selector).collect();
        for (i, th) in th_list.iter().enumerate() {
            let t = th.text().collect::<String>().to_lowercase();
            if t.contains("código") || t.contains("codigo") || t.contains("cod.") {
                map.col_codigo = Some(i);
                tiene_encabezados_relevantes = true;
            } else if t.contains("materia") || t.contains("actividad") || t.contains("asignatura") || t.contains("nombre") {
                map.col_materia = Some(i);
                tiene_encabezados_relevantes = true;
            } else if t.contains("año") || t.contains("anio") || t.contains("período") || t.contains("periodo") || t.contains("nivel") {
                map.col_anio = Some(i);
                tiene_encabezados_relevantes = true;
            } else if t.contains("estado") || t.contains("resultado") || t.contains("condición") || t.contains("condicion") || t.contains("situación") || t.contains("situacion") {
                map.col_estado = Some(i);
                tiene_encabezados_relevantes = true;
            } else if t.contains("nota") || t.contains("calificación") || t.contains("calificacion") {
                map.col_nota = Some(i);
                tiene_encabezados_relevantes = true;
            } else if t.contains("fecha") || t.contains("vencimiento") {
                map.col_fecha = Some(i);
                tiene_encabezados_relevantes = true;
            }
        }

        for row in table.select(&row_selector) {
            let celdas: Vec<_> = row.select(&cell_selector).collect();
            if celdas.len() < 3 {
                continue;
            }

            let mut codigo = String::new();
            let mut nombre = String::new();
            let mut anio: u8 = 1;
            let mut estado = EstadoMateria::Pendiente;
            let mut nota: Option<String> = None;
            let mut fecha_str_encontrada: Option<String> = None;

            if tiene_encabezados_relevantes {
                // Extracción estructurada por cabecera
                if let Some(idx) = map.col_codigo {
                    if idx < celdas.len() {
                        codigo = celdas[idx].text().collect::<String>().trim().to_string();
                    }
                }
                if let Some(idx) = map.col_materia {
                    if idx < celdas.len() {
                        nombre = celdas[idx].text().collect::<String>().trim().to_string();
                    }
                }

                // Si código o nombre están combinados en "(042) Nombre"
                if codigo.is_empty() && !nombre.is_empty() {
                    let (c, n) = separar_codigo_y_nombre(&nombre);
                    if !c.is_empty() {
                        codigo = c;
                        nombre = n;
                    }
                }

                if let Some(idx) = map.col_anio {
                    if idx < celdas.len() {
                        let txt = celdas[idx].text().collect::<String>();
                        let digits: String = txt.chars().filter(|c| c.is_ascii_digit()).collect();
                        if let Ok(val) = digits.parse::<u8>() {
                            if (1..=6).contains(&val) {
                                anio = val;
                            }
                        }
                    }
                }

                if let Some(idx) = map.col_estado {
                    if idx < celdas.len() {
                        let txt = celdas[idx].text().collect::<String>();
                        estado = parsear_estado_str(&txt);
                    }
                }

                if let Some(idx) = map.col_nota {
                    if idx < celdas.len() {
                        let txt = celdas[idx].text().collect::<String>().trim().to_string();
                        if !txt.is_empty() && txt != "-" && !txt.to_lowercase().contains("ausente") {
                            nota = Some(txt);
                        }
                    }
                }

                if let Some(idx) = map.col_fecha {
                    if idx < celdas.len() {
                        fecha_str_encontrada = Some(celdas[idx].text().collect::<String>());
                    }
                }
            } else {
                // Fallback heurístico para tablas sin thead estándar
                let c0 = celdas[0].text().collect::<String>().trim().to_string();
                let c1 = celdas[1].text().collect::<String>().trim().to_string();

                if c0.is_empty() && c1.is_empty() {
                    continue;
                }

                if celdas.len() >= 5 {
                    codigo = c0;
                    nombre = c1;
                    anio = celdas[2].text().collect::<String>().trim().parse().unwrap_or(1);
                    estado = parsear_estado_str(&celdas[3].text().collect::<String>());
                    let n = celdas[4].text().collect::<String>().trim().to_string();
                    if !n.is_empty() && n != "-" {
                        nota = Some(n);
                    }
                } else if celdas.len() == 4 {
                    codigo = c0;
                    nombre = c1;
                    estado = parsear_estado_str(&celdas[2].text().collect::<String>());
                    let n = celdas[3].text().collect::<String>().trim().to_string();
                    if !n.is_empty() && n != "-" {
                        nota = Some(n);
                    }
                } else {
                    let (c, n) = separar_codigo_y_nombre(&c0);
                    codigo = c;
                    nombre = if n.is_empty() { c1 } else { n };
                    estado = parsear_estado_str(&celdas[2].text().collect::<String>());
                }
            }

            // Si el nombre quedó vacío o contiene encabezados repetidos
            if nombre.is_empty() || nombre.to_lowercase().contains("materia") || nombre.to_lowercase().contains("actividad") {
                continue;
            }

            // Si el código está vacío, generarlo a partir del nombre para la clave primaria SQLite
            if codigo.is_empty() {
                codigo = format!("MAT_{}", materias.len() + 1);
            }

            // Calcular días de regularidad si corresponde
            let dias_vencimiento = if estado == EstadoMateria::Regular {
                let mut dias = None;
                if let Some(ref f) = fecha_str_encontrada {
                    dias = calcular_dias_vencimiento(f, hoy_ref);
                }
                if dias.is_none() {
                    for celda in celdas.iter() {
                        let txt = celda.text().collect::<String>();
                        if let Some(calc) = calcular_dias_vencimiento(&txt, hoy_ref) {
                            dias = Some(calc);
                            break;
                        }
                    }
                }
                dias
            } else {
                None
            };

            // Evitar duplicados si la tabla se repite en el HTML expandido
            let key = format!("{}:{}", codigo.to_lowercase(), nombre.to_lowercase());
            if !codigos_vistos.contains(&key) {
                codigos_vistos.insert(key);
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
    }

    materias
}

/// Parsea específicamente la respuesta de Historia Académica (únicamente aprobadas y regulares oficiales)
pub fn parsear_historia_academica(html: &str) -> Vec<Materia> {
    parsear_tabla_plan(html)
}

/// Unifica la lista general del Plan de Estudios con la Historia Académica oficial del alumno
/// asegurando que cada materia tenga su estado real, nota oficial y fecha de regularidad correcta.
pub fn unificar_plan_e_historia(plan: Vec<Materia>, historia: Vec<Materia>) -> Vec<Materia> {
    if plan.is_empty() {
        return historia;
    }
    if historia.is_empty() {
        return plan;
    }

    use std::collections::HashMap;
    let mut historia_map: HashMap<String, Materia> = HashMap::new();
    let mut historia_nombre_map: HashMap<String, Materia> = HashMap::new();

    for h in historia {
        if !h.codigo.is_empty() && !h.codigo.starts_with("MAT_") {
            historia_map.insert(h.codigo.to_lowercase(), h.clone());
        }
        let norm_nombre = h.nombre.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect::<String>();
        if !norm_nombre.is_empty() {
            historia_nombre_map.insert(norm_nombre, h);
        }
    }

    let mut resultado = Vec::with_capacity(plan.len() + 10);
    let mut usadas = std::collections::HashSet::new();

    for mut p in plan {
        let key_cod = p.codigo.to_lowercase();
        let key_nom = p.nombre.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect::<String>();

        let h_match = if !p.codigo.starts_with("MAT_") {
            historia_map.get(&key_cod).or_else(|| historia_nombre_map.get(&key_nom))
        } else {
            historia_nombre_map.get(&key_nom)
        };

        if let Some(h) = h_match {
            p.estado = h.estado.clone();
            p.nota = h.nota.clone();
            p.dias_vencimiento = h.dias_vencimiento;
            if (p.codigo.is_empty() || p.codigo.starts_with("MAT_")) && !h.codigo.is_empty() {
                p.codigo = h.codigo.clone();
            }
            usadas.insert(h.codigo.to_lowercase());
        }
        resultado.push(p);
    }

    // Agregar materias rendidas que no estuvieran en el plan base (ej: optativas rendidas)
    for (cod, h) in historia_map {
        if !usadas.contains(&cod) && !resultado.iter().any(|r| r.codigo.to_lowercase() == cod) {
            resultado.push(h);
        }
    }

    resultado
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

        let html_ok = r#"
            <html>
                <body>
                    <div class="user-panel">Bienvenido Alumno UNJu</div>
                    <a href="/acceso/desconectar">Cerrar Sesión</a>
                </body>
            </html>
        "#;
        assert!(!es_pantalla_login(html_ok));
    }

    #[test]
    fn test_extraer_pagelets_chulupi() {
        let html_con_script = r#"
            <html>
                <body>
                    <div id="contenedor"></div>
                    <script type="text/javascript">
                        kernel.renderer.on_arrival({
                            "id": "cuadro_plan",
                            "content": "<table class=\"table\"><thead><tr><th>Código</th><th>Materia</th><th>Año</th><th>Estado</th><th>Nota</th></tr></thead><tbody><tr><td>042</td><td>Álgebra</td><td>1</td><td>Aprobada</td><td>8</td></tr></tbody></table>"
                        });
                    </script>
                </body>
            </html>
        "#;
        let materias = parsear_tabla_plan(html_con_script);
        assert_eq!(materias.len(), 1);
        assert_eq!(materias[0].codigo, "042");
        assert_eq!(materias[0].nombre, "Álgebra");
        assert_eq!(materias[0].estado, EstadoMateria::Aprobada);
        assert_eq!(materias[0].nota.as_deref(), Some("8"));
    }

    #[test]
    fn test_parsear_historia_academica_unju() {
        let html_historia = r#"
            <table class="table table-bordered">
                <thead>
                    <tr>
                        <th>Actividad</th>
                        <th>Fecha</th>
                        <th>Nota</th>
                        <th>Resultado</th>
                        <th>Tipo</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>(042) Análisis Matemático I</td>
                        <td>15/12/2023</td>
                        <td>8 (Ocho)</td>
                        <td>Promocionado</td>
                        <td>Promoción</td>
                    </tr>
                    <tr>
                        <td>(043) Álgebra Lineal</td>
                        <td>20/11/2024</td>
                        <td>-</td>
                        <td>Regular</td>
                        <td>Cursada</td>
                    </tr>
                </tbody>
            </table>
        "#;
        let materias = parsear_tabla_plan_con_fecha(html_historia, Some(NaiveDate::from_ymd_opt(2024, 12, 1).unwrap()));
        assert_eq!(materias.len(), 2);
        assert_eq!(materias[0].codigo, "042");
        assert_eq!(materias[0].nombre, "Análisis Matemático I");
        assert_eq!(materias[0].estado, EstadoMateria::Aprobada);
        assert_eq!(materias[0].nota.as_deref(), Some("8 (Ocho)"));

        assert_eq!(materias[1].codigo, "043");
        assert_eq!(materias[1].nombre, "Álgebra Lineal");
        assert_eq!(materias[1].estado, EstadoMateria::Regular);
        assert!(materias[1].dias_vencimiento.is_some());
    }

    #[test]
    fn test_unificar_plan_e_historia() {
        let plan = vec![
            Materia {
                codigo: "042".to_string(),
                nombre: "Análisis Matemático I".to_string(),
                anio: 1,
                estado: EstadoMateria::Pendiente,
                nota: None,
                dias_vencimiento: None,
            },
            Materia {
                codigo: "044".to_string(),
                nombre: "Física I".to_string(),
                anio: 1,
                estado: EstadoMateria::Pendiente,
                nota: None,
                dias_vencimiento: None,
            },
        ];

        let historia = vec![
            Materia {
                codigo: "042".to_string(),
                nombre: "Análisis Matemático I".to_string(),
                anio: 1,
                estado: EstadoMateria::Aprobada,
                nota: Some("9".to_string()),
                dias_vencimiento: None,
            },
        ];

        let unificadas = unificar_plan_e_historia(plan, historia);
        assert_eq!(unificadas.len(), 2);
        assert_eq!(unificadas[0].codigo, "042");
        assert_eq!(unificadas[0].estado, EstadoMateria::Aprobada);
        assert_eq!(unificadas[0].nota.as_deref(), Some("9"));

        assert_eq!(unificadas[1].codigo, "044");
        assert_eq!(unificadas[1].estado, EstadoMateria::Pendiente);
        assert_eq!(unificadas[1].nota, None);
    }

    #[test]
    fn test_calculo_vencimiento_regularidad_fi_unju() {
        let hoy = NaiveDate::from_ymd_opt(2024, 10, 15).unwrap();
        // Regularizada hace 10 días -> vigencia total 912 días -> quedan 902 días
        let fecha_reciente = "05/10/2024";
        let dias = calcular_dias_vencimiento(fecha_reciente, Some(hoy)).unwrap();
        assert_eq!(dias, 902);

        // Fecha de vencimiento futura explícita: 31/03/2025
        let fecha_futura = "31/03/2025";
        let dias_futuro = calcular_dias_vencimiento(fecha_futura, Some(hoy)).unwrap();
        assert_eq!(dias_futuro, 167);
    }
}
