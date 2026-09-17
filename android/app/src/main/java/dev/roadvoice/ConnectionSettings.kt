package dev.roadvoice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionSettings(val backendUrl: String, val bearerToken: String)

/** Only the authenticated broker token is stored here; an OpenAI key never belongs in the app. */
class ConnectionSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    fun read(): ConnectionSettings? {
        val payload = preferences.getString("encrypted", null) ?: return null
        return runCatching {
            val parts = payload.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, decode(parts[0])))
            val json = JSONObject(String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8))
            ConnectionSettings(json.getString("url"), json.getString("token"))
        }.getOrNull()
    }

    fun save(backendUrl: String, bearerToken: String) {
        val address = URI(backendUrl.trim())
        val isSecureBackend = address.scheme == "https" && !address.host.isNullOrBlank()
        val isUsbBackend = BuildConfig.ALLOW_USB && address.scheme == "http" &&
            address.host == "127.0.0.1" && address.port in 1..65535
        require(isSecureBackend || isUsbBackend) {
            if (BuildConfig.ALLOW_USB) "Use HTTPS, or http://127.0.0.1:<port> for a local USB test."
            else "Enter an HTTPS backend URL."
        }
        require(address.userInfo == null && address.query == null && address.fragment == null) {
            "Use a backend URL without credentials, query parameters or a fragment."
        }
        require(bearerToken.trim().length >= 32 && bearerToken.none { it.isWhitespace() }) {
            "Enter the broker access token (at least 32 characters, without spaces)."
        }
        require(!bearerToken.startsWith("sk-")) {
            "Use the broker access token, not an OpenAI API key."
        }
        val json = JSONObject().put("url", backendUrl.trim().trimEnd('/'))
            .put("token", bearerToken.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val payload = encode(cipher.iv) + ":" + encode(cipher.doFinal(json.toString().toByteArray()))
        check(preferences.edit().putString("encrypted", payload).commit()) {
            "Unable to save connection settings."
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val keyAlias = "roadvoice_connection_v1"
    }
}
