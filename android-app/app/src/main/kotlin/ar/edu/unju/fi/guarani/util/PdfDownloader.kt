package ar.edu.unju.fi.guarani.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object PdfDownloader {

    fun guardarYAbrirPdf(context: Context, bytes: ByteArray, nombreArchivo: String) {
        try {
            // 1. Guardar en carpeta privada/cache para apertura inmediata con FileProvider
            val certDir = File(context.cacheDir, "certificates")
            if (!certDir.exists()) certDir.mkdirs()

            val tempFile = File(certDir, nombreArchivo)
            FileOutputStream(tempFile).use { it.write(bytes) }

            // 2. Exportar a Descargas públicas para que el alumno conserve el PDF
            guardarEnDescargasPublicas(context, bytes, nombreArchivo)

            // 3. Abrir visor de PDF
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Abrir certificado con...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Error al abrir el PDF: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun guardarEnDescargasPublicas(context: Context, bytes: ByteArray, nombreArchivo: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nombreArchivo)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val downloadUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (downloadUri != null) {
                    resolver.openOutputStream(downloadUri)?.use { it.write(bytes) }
                }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val destFile = File(downloadDir, nombreArchivo)
                FileOutputStream(destFile).use { it.write(bytes) }
            }
        } catch (_: Exception) {
            // Si falla el guardado en Descargas públicas (ej. permisos), el archivo temporal en cache sigue funcionando
        }
    }
}
