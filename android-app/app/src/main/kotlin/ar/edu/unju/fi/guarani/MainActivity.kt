package ar.edu.unju.fi.guarani

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

        viewModel = PlanEstudioViewModel(cacheDir = cacheDir)
        securityManager = SecurityManager(this)

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
                                                    "No hay credenciales guardadas",
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
                                    "Biometría no disponible en este dispositivo",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuaraniApp(
    viewModel: PlanEstudioViewModel,
    securityManager: SecurityManager,
    onBiometricAuth: (onSuccess: (String, String) -> Unit) -> Unit
) {
    var dni by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var guardarCredencialesCheck by remember { mutableStateOf(true) }
    var tieneCredencialesGuardadas by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val creds = securityManager.obtenerCredenciales()
        tieneCredencialesGuardadas = (creds != null)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Formulario de Credenciales / Sincronización
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Acceso Seguro",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = dni,
                        onValueChange = { dni = it },
                        label = { Text("DNI de Alumno") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = clave,
                        onValueChange = { clave = it },
                        label = { Text("Contraseña SIU") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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
                            text = "Guardar credenciales en Keystore",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (guardarCredencialesCheck) {
                                scope.launch {
                                    securityManager.guardarCredenciales(dni, clave)
                                    tieneCredencialesGuardadas = true
                                }
                            }
                            viewModel.sincronizarConSiu(dni, clave)
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                            Text("Sincronizando...")
                        } else {
                            Text("Sincronizar Plan de Estudios")
                        }
                    }

                    if (tieneCredencialesGuardadas) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onBiometricAuth { bioDni, bioClave ->
                                    dni = bioDni
                                    clave = bioClave
                                    viewModel.sincronizarConSiu(bioDni, bioClave)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Desbloquear con Huella / PIN")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Estado y Lista de Materias
            when (val state = uiState) {
                is PlanUiState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Ingrese sus credenciales o use datos locales.",
                            color = Color.Gray
                        )
                    }
                }
                is PlanUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Procesando con motor Rust nativo...")
                        }
                    }
                }
                is PlanUiState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Text(
                            text = state.mensaje,
                            color = Color(0xFFC62828),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                is PlanUiState.Success -> {
                    val context = LocalContext.current
                    MateriasList(
                        materias = state.materias,
                        onDescargarCertificado = { endpoint, nombreArchivo ->
                            scope.launch {
                                val result = viewModel.descargarCertificado(endpoint)
                                result.onSuccess { bytes ->
                                    PdfDownloader.guardarYAbrirPdf(
                                        context = context,
                                        bytes = bytes,
                                        nombreArchivo = nombreArchivo
                                    )
                                }.onFailure { error ->
                                    Toast.makeText(context, "Error al descargar: ${error.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MateriasList(
    materias: List<Materia>,
    onDescargarCertificado: (endpoint: String, nombreArchivo: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var descargandoPdf by remember { mutableStateOf(false) }

    val aprobadas = materias.count { it.estado == EstadoMateria.APROBADA }
    val regulares = materias.count { it.estado == EstadoMateria.REGULAR }
    val pendientes = materias.count { it.estado == EstadoMateria.PENDIENTE }

    Column(modifier = Modifier.fillMaxSize()) {
        // Semáforo resumen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SemaforoChip(label = "Aprobadas: $aprobadas", color = Color(0xFF2E7D32))
            SemaforoChip(label = "Regulares: $regulares", color = Color(0xFFF57F17))
            SemaforoChip(label = "Pendientes: $pendientes", color = Color(0xFF616161))
        }

        // Acciones de Certificados
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Certificados Oficiales",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color(0xFF1B5E20)
                )

                OutlinedButton(
                    onClick = {
                        descargandoPdf = true
                        onDescargarCertificado(
                            "tramites/alumnos_regular/pdf",
                            "Certificado_Alumno_Regular_UNJu.pdf"
                        )
                        descargandoPdf = false
                    },
                    enabled = !descargandoPdf,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Alumno Regular", fontSize = 12.sp)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(materias, key = { it.codigo }) { materia ->
                MateriaItem(materia = materia)
            }
        }
    }
}

@Composable
fun SemaforoChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(16.dp),
        border = null
    ) {
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
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
        shape = RoundedCornerShape(8.dp)
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
                    .height(36.dp)
                    .background(estadoColor, RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${materia.codigo} - ${materia.nombre}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Año: ${materia.anio} | Estado: $estadoTexto${materia.nota?.let { " (Nota: $it)" } ?: ""}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

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
