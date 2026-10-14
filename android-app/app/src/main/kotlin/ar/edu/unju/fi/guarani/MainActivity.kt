package ar.edu.unju.fi.guarani

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ar.edu.unju.fi.guarani.ui.PlanEstudioViewModel
import ar.edu.unju.fi.guarani.ui.PlanUiState
import uniffi.guarani_core.EstadoMateria
import uniffi.guarani_core.Materia

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PlanEstudioViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = PlanEstudioViewModel(cacheDir = cacheDir)

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
                    GuaraniApp(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuaraniApp(viewModel: PlanEstudioViewModel) {
    var dni by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                        text = "Sincronización de Historial",
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

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.sincronizarConSiu(dni, clave) },
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
                            text = "Ingrese sus credenciales o cargue datos locales.",
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
                    MateriasList(materias = state.materias)
                }
            }
        }
    }
}

@Composable
fun MateriasList(materias: List<Materia>) {
    val aprobadas = materias.count { it.estado == EstadoMateria.Aprobada }
    val regulares = materias.count { it.estado == EstadoMateria.Regular }
    val pendientes = materias.count { it.estado == EstadoMateria.Pendiente }

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
        EstadoMateria.Aprobada -> Color(0xFF2E7D32) to "Aprobada"
        EstadoMateria.Regular -> Color(0xFFF57F17) to "Regular"
        EstadoMateria.Pendiente -> Color(0xFF757575) to "Pendiente"
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
            // Indicador de color vertical
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
            }
        }
    }
}
