package ar.edu.unju.fi.guarani

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ar.edu.unju.fi.guarani.security.BiometricHelper
import ar.edu.unju.fi.guarani.security.SecurityManager
import ar.edu.unju.fi.guarani.ui.PlanEstudioViewModel
import ar.edu.unju.fi.guarani.ui.PlanUiState
import ar.edu.unju.fi.guarani.util.PdfDownloader
import ar.edu.unju.fi.guarani.worker.GuaraniSyncWorker
import kotlinx.coroutines.launch
import uniffi.guarani_core.EstadoMateria
import uniffi.guarani_core.Materia

class MainActivity : FragmentActivity() {

    private lateinit var viewModel: PlanEstudioViewModel
    private lateinit var securityManager: SecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        securityManager = SecurityManager(this)
        viewModel = PlanEstudioViewModel(cacheDir = cacheDir, securityManager = securityManager)

        // Programar sincronización periódica silenciosa en segundo plano
        GuaraniSyncWorker.programarSincronizacionPeriodica(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF006699),
                    secondary = Color(0xFF009966),
                    surface = Color(0xFFF7F9FB),
                    background = Color(0xFFF0F4F8)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GuaraniApp(
                        viewModel = viewModel,
                        securityManager = securityManager,
                        onBiometricAuth = { onCredentialsUnlocked ->
                            if (BiometricHelper.puedeAutenticarConBiometria(this@MainActivity)) {
                                BiometricHelper.autenticar(
                                    activity = this@MainActivity,
                                    onSuccess = {
                                        lifecycleScope.launch {
                                            val creds = securityManager.obtenerCredenciales()
                                            if (creds != null) {
                                                onCredentialsUnlocked(creds.first, creds.second)
                                            } else {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "No hay credenciales guardadas en el dispositivo.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onError = { err ->
                                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Biometría no disponible o no configurada en este dispositivo.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GuaraniApp(
    viewModel: PlanEstudioViewModel,
    securityManager: SecurityManager,
    onBiometricAuth: (onSuccess: (String, String) -> Unit) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is PlanUiState.Success -> {
            AlumnoMainScreen(
                state = state,
                viewModel = viewModel,
                onCerrarSesion = { viewModel.cerrarSesion() }
            )
        }
        else -> {
            LoginScreen(
                uiState = uiState,
                viewModel = viewModel,
                securityManager = securityManager,
                onBiometricAuth = onBiometricAuth
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    uiState: PlanUiState,
    viewModel: PlanEstudioViewModel,
    securityManager: SecurityManager,
    onBiometricAuth: (onSuccess: (String, String) -> Unit) -> Unit
) {
    var dni by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var guardarCredencialesCheck by remember { mutableStateOf(true) }
    var tieneCredencialesGuardadas by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val creds = securityManager.obtenerCredenciales()
        tieneCredencialesGuardadas = (creds != null)
        if (creds != null) {
            dni = creds.first
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SIU Guaraní - FI UNJu",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Autogestión de Alumnos",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Facultad de Ingeniería - Universidad Nacional de Jujuy",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                    )

                    OutlinedTextField(
                        value = dni,
                        onValueChange = { dni = it },
                        label = { Text("DNI de Alumno") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = clave,
                        onValueChange = { clave = it },
                        label = { Text("Contraseña SIU") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    text = if (passwordVisible) "Ocultar" else "Ver",
                                    fontSize = 11.sp
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = guardarCredencialesCheck,
                            onCheckedChange = { guardarCredencialesCheck = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Recordar credenciales (Biometría / Keystore)",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }

                    if (uiState is PlanUiState.Error) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = uiState.mensaje,
                                    color = Color(0xFFC62828),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = { viewModel.limpiarError() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Entendido", fontSize = 11.sp, color = Color(0xFFC62828))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.sincronizarConSiu(
                                dni = dni,
                                clave = clave,
                                guardarEnKeystore = guardarCredencialesCheck
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = dni.isNotBlank() && clave.isNotBlank() && uiState !is PlanUiState.Loading
                    ) {
                        if (uiState is PlanUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Iniciando sesión...")
                        } else {
                            Text("Iniciar Sesión", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (tieneCredencialesGuardadas) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                onBiometricAuth { bioDni, bioClave ->
                                    dni = bioDni
                                    clave = bioClave
                                    viewModel.sincronizarConSiu(bioDni, bioClave, guardarEnKeystore = false)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = uiState !is PlanUiState.Loading
                        ) {
                            Text("Ingresar con Huella / PIN", fontSize = 13.sp)
                        }
                    }

                    if (viewModel.tieneDatosEnCache()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = { viewModel.cargarDesdeCache() },
                            enabled = uiState !is PlanUiState.Loading
                        ) {
                            Text("Ver datos guardados (Modo Offline)", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlumnoMainScreen(
    state: PlanUiState.Success,
    viewModel: PlanEstudioViewModel,
    onCerrarSesion: () -> Unit
) {
    var tabSeleccionada by remember { mutableIntStateOf(0) }
    var mostrarDialogoLogout by remember { mutableStateOf(false) }

    if (mostrarDialogoLogout) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoLogout = false },
            title = { Text("Cerrar Sesión") },
            text = { Text("¿Desea salir de su cuenta de SIU Guaraní?") },
            confirmButton = {
                TextButton(onClick = {
                    mostrarDialogoLogout = false
                    onCerrarSesion()
                }) {
                    Text("Cerrar Sesión", color = Color(0xFFC62828))
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogoLogout = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SIU Guaraní - FI UNJu",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (state.esOffline) "Modo Offline (Caché local)" else "Alumno: ${state.dniAlumno}",
                            fontSize = 11.sp,
                            color = Color(0xFFE0F2FE)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { mostrarDialogoLogout = true }) {
                        Text("Salir", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = tabSeleccionada,
                containerColor = Color.White,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = tabSeleccionada == 0,
                    onClick = { tabSeleccionada = 0 },
                    text = { Text("Plan de Estudio", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = tabSeleccionada == 1,
                    onClick = { tabSeleccionada = 1 },
                    text = { Text("Certificados y Trámites", fontWeight = FontWeight.SemiBold) }
                )
            }

            when (tabSeleccionada) {
                0 -> PlanEstudioTab(materias = state.materias)
                1 -> CertificadosTab(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun PlanEstudioTab(materias: List<Materia>) {
    var filtroEstado by remember { mutableStateOf<EstadoMateria?>(null) }

    val aprobadas = materias.count { it.estado == EstadoMateria.APROBADA }
    val regulares = materias.count { it.estado == EstadoMateria.REGULAR }
    val pendientes = materias.count { it.estado == EstadoMateria.PENDIENTE }

    val materiasFiltradas = remember(materias, filtroEstado) {
        if (filtroEstado == null) materias else materias.filter { it.estado == filtroEstado }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Semáforo resumen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SemaforoChip(
                label = "Aprobadas: $aprobadas",
                color = Color(0xFF2E7D32),
                seleccionado = filtroEstado == EstadoMateria.APROBADA,
                onClick = {
                    filtroEstado = if (filtroEstado == EstadoMateria.APROBADA) null else EstadoMateria.APROBADA
                }
            )
            SemaforoChip(
                label = "Regulares: $regulares",
                color = Color(0xFFF57F17),
                seleccionado = filtroEstado == EstadoMateria.REGULAR,
                onClick = {
                    filtroEstado = if (filtroEstado == EstadoMateria.REGULAR) null else EstadoMateria.REGULAR
                }
            )
            SemaforoChip(
                label = "Pendientes: $pendientes",
                color = Color(0xFF616161),
                seleccionado = filtroEstado == EstadoMateria.PENDIENTE,
                onClick = {
                    filtroEstado = if (filtroEstado == EstadoMateria.PENDIENTE) null else EstadoMateria.PENDIENTE
                }
            )
        }

        if (filtroEstado != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mostrando solo: ${when(filtroEstado) {
                        EstadoMateria.APROBADA -> "Aprobadas"
                        EstadoMateria.REGULAR -> "Regulares"
                        else -> "Pendientes"
                    }} (${materiasFiltradas.size})",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = "Ver todas",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { filtroEstado = null }
                )
            }
        }

        if (materiasFiltradas.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay materias en esta categoría.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(materiasFiltradas, key = { it.codigo }) { materia ->
                    MateriaItem(materia = materia)
                }
            }
        }
    }
}

@Composable
fun CertificadosTab(viewModel: PlanEstudioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var descargandoEndpoint by remember { mutableStateOf<String?>(null) }

    fun descargar(endpoint: String, nombreArchivo: String) {
        descargandoEndpoint = endpoint
        scope.launch {
            val result = viewModel.descargarCertificado(endpoint)
            descargandoEndpoint = null
            result.onSuccess { bytes ->
                PdfDownloader.guardarYAbrirPdf(
                    context = context,
                    bytes = bytes,
                    nombreArchivo = nombreArchivo
                )
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "Error al descargar certificado: ${err.localizedMessage ?: err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Trámites y Constancias Oficiales",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Descargue constancias oficiales emitidas por SIU Guaraní con firma y validación digital directa de la UNJu.",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(4.dp))

        CertificadoCard(
            titulo = "Certificado de Alumno Regular",
            descripcion = "Válido para el BEGU, obra social, asignaciones familiares y trámites en organismos públicos.",
            estaDescargando = descargandoEndpoint == "tramites/alumnos_regular/pdf",
            onDescargar = {
                descargar("tramites/alumnos_regular/pdf", "Certificado_Alumno_Regular_UNJu.pdf")
            }
        )

        CertificadoCard(
            titulo = "Historia Académica Oficial",
            descripcion = "Constancia detallada de asignaturas aprobadas, notas de exámenes, actas y promedio general.",
            estaDescargando = descargandoEndpoint == "tramites/solicitud_certificados/pdf",
            onDescargar = {
                descargar("tramites/solicitud_certificados/pdf", "Historia_Academica_UNJu.pdf")
            }
        )

        CertificadoCard(
            titulo = "Constancia de Actividades Aprobadas",
            descripcion = "Comprobante de materias regularizadas y aprobadas para trámites académicos internos o pases.",
            estaDescargando = descargandoEndpoint == "tramites/constancia_actividades/pdf",
            onDescargar = {
                descargar("tramites/constancia_actividades/pdf", "Constancia_Actividades_UNJu.pdf")
            }
        )
    }
}

@Composable
fun CertificadoCard(
    titulo: String,
    descripcion: String,
    estaDescargando: Boolean,
    onDescargar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = titulo,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Color(0xFF1E293B)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = descripcion,
                fontSize = 12.sp,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDescargar,
                enabled = !estaDescargando,
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (estaDescargando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Generando PDF...", fontSize = 12.sp)
                } else {
                    Text("Descargar PDF", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SemaforoChip(
    label: String,
    color: Color,
    seleccionado: Boolean = false,
    onClick: () -> Unit = {}
) {
    Surface(
        color = if (seleccionado) color else color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        border = if (seleccionado) null else borderStrokeFor(color),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (seleccionado) Color.White else color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private fun borderStrokeFor(color: Color): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
}

@Composable
fun MateriaItem(materia: Materia) {
    val (estadoColor, estadoTexto) = when (materia.estado) {
        EstadoMateria.APROBADA -> Color(0xFF2E7D32) to "Aprobada"
        EstadoMateria.REGULAR -> Color(0xFFF57F17) to "Regular"
        EstadoMateria.PENDIENTE -> Color(0xFF757575) to "Pendiente"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .background(estadoColor, RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (materia.codigo.startsWith("MAT_")) materia.nombre else "${materia.codigo} - ${materia.nombre}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color(0xFF1E293B),
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${materia.anio}° Año",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF475569),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Estado: $estadoTexto",
                        color = estadoColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    materia.nota?.let {
                        Text(
                            text = "  •  Nota: $it",
                            color = Color(0xFF334155),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                val dias = materia.diasVencimiento
                if (dias != null) {
                    val (vencColor, vencTexto) = when {
                        dias <= 0L -> Color(0xFFC62828) to "¡Regularidad Vencida!"
                        dias <= 90L -> Color(0xFFE65100) to "Vence pronto: $dias días"
                        else -> Color(0xFF00796B) to "Vigente: $dias días restantes"
                    }
                    Text(
                        text = vencTexto,
                        color = vencColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
