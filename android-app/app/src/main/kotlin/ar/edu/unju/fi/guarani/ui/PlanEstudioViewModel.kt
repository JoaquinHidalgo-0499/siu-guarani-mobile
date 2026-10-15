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
    data class Success(
        val materias: List<Materia>,
        val esOffline: Boolean = false,
        val dniAlumno: String = ""
    ) : PlanUiState
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

    fun tieneDatosEnCache(): Boolean {
        return try {
            val f = File(dbPath)
            f.exists() && f.length() > 0
        } catch (_: Throwable) {
            false
        }
    }

    fun cargarDesdeCache() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PlanUiState.Loading
            try {
                val cached = client.obtenerDeCache(dbPath)
                if (cached.isNotEmpty()) {
                    _uiState.value = PlanUiState.Success(
                        materias = cached,
                        esOffline = true,
                        dniAlumno = "Modo Offline"
                    )
                } else {
                    _uiState.value = PlanUiState.Error("No hay materias almacenadas localmente. Inicie sesión para sincronizar.")
                }
            } catch (t: Throwable) {
                _uiState.value = PlanUiState.Error("No se pudo leer la base de datos local: ${t.localizedMessage ?: t.message}")
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
                    if (lista.isNotEmpty()) {
                        client.guardarEnCache(dbPath, lista)
                    }
                    _uiState.value = PlanUiState.Success(
                        materias = lista,
                        esOffline = false,
                        dniAlumno = dni
                    )
                } else {
                    _uiState.value = PlanUiState.Error("Credenciales rechazadas por SIU Guaraní. Verifique su DNI y contraseña.")
                }
            } catch (e: GuaraniException.CredencialesInvalidas) {
                _uiState.value = PlanUiState.Error("DNI o contraseña incorrectos en SIU Guaraní UNJu.")
            } catch (e: GuaraniException.RedException) {
                _uiState.value = PlanUiState.Error("Error de conexión con el portal de la UNJu. Verifique su conexión a Internet.")
            } catch (t: Throwable) {
                _uiState.value = PlanUiState.Error("Error al sincronizar: ${t.localizedMessage ?: t.message}")
            }
        }
    }

    fun cerrarSesion() {
        _uiState.value = PlanUiState.Idle
    }

    fun limpiarError() {
        if (_uiState.value is PlanUiState.Error) {
            _uiState.value = PlanUiState.Idle
        }
    }

    suspend fun descargarCertificado(endpoint: String): Result<ByteArray> {
        return runCatching {
            client.descargarCertificadoPdf(endpoint)
        }
    }
}
