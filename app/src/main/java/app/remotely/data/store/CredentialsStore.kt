package app.remotely.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Credentials(val login: String, val password: String)

interface CredentialsSource {
    suspend fun load(): Credentials?
    suspend fun save(c: Credentials)
    suspend fun clear()
}

interface Cipherer {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** AES-GCM with a non-exportable Android Keystore key; output = iv(12) + ciphertext. */
class KeystoreCipherer(private val alias: String = "remotely-credentials") : Cipherer {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}

class CredentialsStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: Cipherer,
) : CredentialsSource {
    private val key = stringPreferencesKey("credentials")

    override suspend fun load(): Credentials? {
        val raw = dataStore.data.first()[key] ?: return null
        return try {
            Json.decodeFromString<Credentials>(String(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP))))
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(c: Credentials) {
        val enc = Base64.encodeToString(cipher.encrypt(Json.encodeToString(Credentials.serializer(), c).toByteArray()), Base64.NO_WRAP)
        dataStore.edit { it[key] = enc }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }
}
