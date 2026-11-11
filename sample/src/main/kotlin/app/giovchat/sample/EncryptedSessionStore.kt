package app.giovchat.sample

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.giovchat.core.GiovSession
import app.giovchat.core.GiovSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the chat session (a bearer secret that proves this device owns the conversation) encrypted with AES-GCM under
 * an Android Keystore key, in plain SharedPreferences. No extra library: `androidx.security:security-crypto` is
 * deprecated. The preferences file is excluded from backup and device transfer (res/xml/data_extraction_rules.xml):
 * the Keystore key never leaves the device, so a restored copy could not be read anyway.
 */
class EncryptedSessionStore(context: Context) : GiovSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun read(scope: String): GiovSession? = withContext(Dispatchers.IO) {
        val name = entry(scope)
        val stored = prefs.getString(name, null) ?: return@withContext null
        val parts = try {
            decrypt(stored).split('\n', limit = 2)
        } catch (_: Exception) {
            // Written under a key this device no longer has (reinstall, restore): forget it.
            prefs.edit().remove(name).apply()
            return@withContext null
        }
        if (parts.size == 2) GiovSession(parts[0], parts[1]) else null
    }

    override suspend fun write(scope: String, session: GiovSession) = withContext(Dispatchers.IO) {
        prefs.edit().putString(entry(scope), encrypt("${session.conversationId}\n${session.sessionToken}")).apply()
    }

    override suspend fun delete(scope: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(entry(scope)).apply()
    }

    /** The scope names the signed-in customer; only its hash is written to disk. */
    private fun entry(scope: String): String =
        MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val FILE = "giov_chat_sessions"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "giov_chat_sessions"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
