package ar.edu.unju.fi.guarani.worker

import android.content.Context
import androidx.work.*
import ar.edu.unju.fi.guarani.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.guarani_core.GuaraniClient
import java.io.File
import java.util.concurrent.TimeUnit

class GuaraniSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val securityManager = SecurityManager(applicationContext)
        val creds = securityManager.obtenerCredenciales() ?: return@withContext Result.success()

        val dbPath = File(applicationContext.cacheDir, "guarani_offline.db").absolutePath
        val client = GuaraniClient("https://alumnos.unju.edu.ar/unju")

        try {
            val loginExitoso = client.autenticar(creds.first, creds.second)
            if (loginExitoso) {
                val materias = client.obtenerPlanEstudios()
                client.guardarEnCache(dbPath, materias)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "GuaraniSilentSyncWork"

        fun programarSincronizacionPeriodica(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<GuaraniSyncWorker>(
                12, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }
    }
}
