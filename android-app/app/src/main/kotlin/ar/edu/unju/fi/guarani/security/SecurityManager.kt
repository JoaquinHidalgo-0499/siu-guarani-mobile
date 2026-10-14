package ar.edu.unju.fi.guarani.security

import android.content.Context
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

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "guarani_master_keyset", "guarani_master_prefs")
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri("android-keystore://guarani_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    companion object {
        private val KEY_DNI = stringPreferencesKey("enc_dni")
        private val KEY_CLAVE = stringPreferencesKey("enc_clave")
    }

    suspend fun guardarCredenciales(dni: String, clave: String) {
        val encryptedDni = Base64.getEncoder().encodeToString(aead.encrypt(dni.toByteArray(), null))
        val encryptedClave = Base64.getEncoder().encodeToString(aead.encrypt(clave.toByteArray(), null))

        context.dataStore.edit { prefs ->
            prefs[KEY_DNI] = encryptedDni
            prefs[KEY_CLAVE] = encryptedClave
        }
    }

    suspend fun obtenerCredenciales(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val encDni = prefs[KEY_DNI] ?: return null
        val encClave = prefs[KEY_CLAVE] ?: return null

        return try {
            val dni = String(aead.decrypt(Base64.getDecoder().decode(encDni), null))
            val clave = String(aead.decrypt(Base64.getDecoder().decode(encClave), null))
            Pair(dni, clave)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun borrarCredenciales() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_DNI)
            prefs.remove(KEY_CLAVE)
        }
    }
}
