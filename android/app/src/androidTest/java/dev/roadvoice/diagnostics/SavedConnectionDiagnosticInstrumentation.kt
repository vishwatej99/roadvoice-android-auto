package dev.roadvoice.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.roadvoice.ConnectionSettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Debug test APK only. Never prints a credential, request, response body, or exception message. */
class SavedConnectionDiagnosticInstrumentation : Instrumentation() {
    private var expectedTokenDigest: ByteArray? = null
    private var shouldApplySetup = false
    private var shouldTestLifecycle = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedTokenDigest = arguments?.getString("expectedTokenSha256")
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        shouldApplySetup = arguments?.getString("applySetup") == "true"
        shouldTestLifecycle = arguments?.getString("testLifecycle") == "true"
        start()
    }

    override fun onStart() {
        if (shouldTestLifecycle) {
            VoiceLifecycleDiagnostic(this).run()
            return
        }
        val result = Bundle().apply {
            putInt("httpStatus", -1)
            putInt("tokenLength", 0)
            putBoolean("isWorkerUnauthorized", false)
            putBoolean("isWorkerOriginRejected", false)
            putBoolean("contentTypeIsJson", false)
            putBoolean("setupApplied", false)
        }
        val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        try {
            val settingsStore = ConnectionSettingsStore(targetContext)
            if (shouldApplySetup) {
                val setupFile = File(targetContext.filesDir, "roadvoice-setup.json")
                try {
                    val setupBytes = ByteArray(4097)
                    val setupLength = setupFile.inputStream().use { input ->
                        var bytesRead = 0
                        while (bytesRead < setupBytes.size) {
                            val count = input.read(setupBytes, bytesRead, setupBytes.size - bytesRead)
                            if (count == -1) break
                            bytesRead += count
                        }
                        bytesRead
                    }
                    require(setupLength in 1..4096)
                    val setup = JSONObject(String(setupBytes, 0, setupLength, Charsets.UTF_8))
                    settingsStore.save(setup.getString("url"), setup.getString("token"))
                    result.putBoolean("setupApplied", true)
                } finally {
                    check(!setupFile.exists() || setupFile.delete())
                }
            }
            val settings = checkNotNull(settingsStore.read())
            result.putInt("tokenLength", settings.bearerToken.length)
            result.putBoolean("tokenIsHex", settings.bearerToken.matches(Regex("[0-9a-fA-F]+")))
            result.putBoolean("tokenIsRepeatedCharacter", settings.bearerToken.isNotEmpty() &&
                settings.bearerToken.all { it == settings.bearerToken.first() })
            expectedTokenDigest?.let { expectedDigest ->
                fun matchesExpected(token: String): Boolean = MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
                    expectedDigest)
                result.putBoolean("tokenMatchesExpected", matchesExpected(settings.bearerToken))
                result.putBoolean("tokenMatchesExpectedWhenLowercase",
                    matchesExpected(settings.bearerToken.lowercase(Locale.ROOT)))
                result.putBoolean("tokenMatchesExpectedWhenUppercase",
                    matchesExpected(settings.bearerToken.uppercase(Locale.ROOT)))
            }
            val request = Request.Builder()
                .url(settings.backendUrl.trimEnd('/') + "/session")
                .header("Authorization", "Bearer " + settings.bearerToken)
                // This literal cannot pass the worker's SDP validation or create an OpenAI session.
                .post("invalid-sdp".toRequestBody("application/sdp".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                result.putInt("httpStatus", response.code)
                val contentTypeIsJson = response.body?.contentType()?.let {
                    it.type == "application" && it.subtype == "json"
                } ?: false
                result.putBoolean("contentTypeIsJson", contentTypeIsJson)
                if (contentTypeIsJson) {
                    val workerMessage = runCatching {
                        JSONObject(response.peekBody(1024).string()).optString("error")
                    }.getOrNull()
                    result.putBoolean("isWorkerUnauthorized",
                        response.code == 401 && workerMessage == "Unauthorized.")
                    result.putBoolean("isWorkerOriginRejected",
                        response.code == 403 && workerMessage == "Browser requests are not supported.")
                }
            }
        } catch (error: Exception) {
            result.putString("errorClass", error.javaClass.simpleName)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            finish(Activity.RESULT_OK, result)
        }
    }
}
