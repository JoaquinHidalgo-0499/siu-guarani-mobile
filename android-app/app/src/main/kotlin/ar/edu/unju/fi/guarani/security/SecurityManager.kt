package ar.edu.unju.fi.guarani.security

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import java.util.Base64

private val Context.dataStore by preferencesDataStore(name = "guarani_secure_prefs")

class SecurityManager(private val context: Context) {

    private val aead: Aead? by lazy {
        try {
            AeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(context, "guarani_master_keyset", "guarani_master_prefs")
                .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
                .withMasterKeyUri("android-keystore://guarani_master_key")
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        } catch (e: Exception) {
            Log.e("SecurityManager", "No se pudo inicializar AndroidKeyStore TEE", e)
            null
        }
    }

    companion object {
        private val KEY_DNI = stringPreferencesKey("enc_dni")
        private val KEY_CLAVE = stringPreferencesKey("enc_clave")
    }

    suspend fun guardarCredenciales(dni: String, clave: String): Boolean {
        val crypto = aead ?: return false
        return try {
            val encryptedDni = Base64.getEncoder().encodeToString(crypto.encrypt(dni.toByteArray(), null))
            val encryptedClave = Base64.getEncoder().encodeToString(crypto.encrypt(clave.toByteArray(), null))

            context.dataStore.edit { prefs ->
                prefs[KEY_DNI] = encryptedDni
                prefs[KEY_CLAVE] = encryptedClave
            }
            true
        } catch (e: Exception) {
            Log.e("SecurityManager", "Error al guardar credenciales en Keystore", e)
            false
        }
    }

    suspend fun obtenerCredenciales(): Pair<String, String>? {
        return try {
            val prefs = context.dataStore.data.first()
            val encDni = prefs[KEY_DNI] ?: return null
            val encClave = prefs[KEY_CLAVE] ?: return null
            val crypto = aead ?: return null

            val dni = String(crypto.decrypt(Base64.getDecoder().decode(encDni), null))
            val clave = String(crypto.decrypt(Base64.getDecoder().decode(encClave), null))
            Pair(dni, clave)
        } catch (e: Exception) {
            Log.e("SecurityManager", "Error al descifrar credenciales desde Keystore", e)
            null
        }
    }

    suspend fun borrarCredenciales() {
        try {
            context.dataStore.edit { prefs ->
                prefs.remove(KEY_DNI)
                prefs.remove(KEY_CLAVE)
            }
        } catch (e: Exception) {
            Log.e("SecurityManager", "Error al borrar credenciales", e)
        }
    }
}
