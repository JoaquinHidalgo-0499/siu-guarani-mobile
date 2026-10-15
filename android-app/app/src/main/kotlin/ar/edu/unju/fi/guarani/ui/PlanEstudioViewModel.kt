package ar.edu.unju.fi.guarani.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.edu.unju.fi.guarani.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import uniffi.guarani_core.GuaraniClient
import uniffi.guarani_core.GuaraniException
import uniffi.guarani_core.Materia

sealed interface PlanUiState {
    data object Idle : PlanUiState
    data object Loading : PlanUiState
    data class Success(val materias: List<Materia>) : PlanUiState
    data class Error(val mensaje: String) : PlanUiState
}

class PlanEstudioViewModel(
    private val cacheDir: File,
    private val securityManager: SecurityManager? = null,
    baseUrl: String = "https://alumnos.unju.edu.ar/unju"
) : ViewModel() {

    private val client by lazy { GuaraniClient(baseUrl) }
    private val dbPath = File(cacheDir, "guarani_offline.db").absolutePath

    private val _uiState = MutableStateFlow<PlanUiState>(PlanUiState.Idle)
    val uiState: StateFlow<PlanUiState> = _uiState.asStateFlow()

    init {
        cargarDesdeCache()
    }

    fun cargarDesdeCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = client.obtenerDeCache(dbPath)
                if (cached.isNotEmpty()) {
                    _uiState.value = PlanUiState.Success(cached)
                }
            } catch (_: Throwable) {
                // Si la BD no existe aún, se mantiene en estado Idle
            }
        }
    }

    fun sincronizarConSiu(dni: String, clave: String, guardarEnKeystore: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PlanUiState.Loading
            try {
                val autenticado = client.autenticar(dni, clave)
                if (autenticado) {
                    if (guardarEnKeystore) {
                        securityManager?.guardarCredenciales(dni, clave)
                    }
                    val lista = client.obtenerPlanEstudios()
                    client.guardarEnCache(dbPath, lista)
                    _uiState.value = PlanUiState.Success(lista)
                } else {
                    _uiState.value = PlanUiState.Error("No se pudo iniciar sesión en SIU Guaraní.")
                }
            } catch (e: GuaraniException.CredencialesInvalidas) {
                _uiState.value = PlanUiState.Error("DNI o contraseña incorrectos en SIU Guaraní.")
            } catch (e: GuaraniException.RedException) {
                _uiState.value = PlanUiState.Error("Error de conexión con el portal de la UNJu. Mostrando datos offline.")
                cargarDesdeCache()
            } catch (t: Throwable) {
                _uiState.value = PlanUiState.Error("Error al procesar: ${t.localizedMessage ?: t.message}")
            }
        }
    }

    suspend fun descargarCertificado(endpoint: String): Result<ByteArray> {
        return runCatching {
            client.descargarCertificadoPdf(endpoint)
        }
    }
}
