package dev.roadvoice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import dev.roadvoice.car.CarVoiceAudio
import androidx.core.content.ContextCompat
import dev.roadvoice.voice.VoiceSessionService
import dev.roadvoice.voice.VoiceSessionState
import dev.roadvoice.voice.VoiceSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One-time phone setup; everyday interaction lives in the car's app tile. */
class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connectionStore: ConnectionSettingsStore
    private lateinit var status: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var backendInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var talkButton: Button
    private lateinit var muteButton: Button
    private lateinit var endButton: Button
    private lateinit var closeButton: Button
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button
    private lateinit var permissionButton: Button
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var carAudioSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Connection credentials must not appear in screenshots, recent-app previews or autofill.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        connectionStore = ConnectionSettingsStore(this)
        val saved = connectionStore.read()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            setBackgroundColor(Color.rgb(17, 24, 32))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 32))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        closeButton = Button(this).apply {
            text = "Close app"
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener {
                VoiceSessionService.sendAction(this@MainActivity, VoiceSessionService.ACTION_END)
                finishAndRemoveTask()
            }
        }
        screen.addView(closeButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(24), dp(8), dp(24), dp(8))
        })
        screen.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(screen)
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(233, 240, 247))
            setPadding(0, dp(12), 0, dp(8))
            content.addView(this)
        }
        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

        label("ChatGPT Voice", 30f).gravity = Gravity.CENTER
        label("for Android Auto", 16f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(153, 177, 199))
        }
        voiceOrb = VoiceOrbView(this)
        content.addView(voiceOrb, LinearLayout.LayoutParams(-1, dp(246)))
        status = label("Ready to talk", 18f).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        status.setTextColor(Color.rgb(174, 216, 255))
        talkButton = button("Talk") {
            if (!VoiceSessionService.hasMicrophonePermission(this)) requestVoicePermissions()
            else if (connectionStore.read() == null) showMessage("Save your connection settings first.")
            else VoiceSessionService.sendAction(this, VoiceSessionService.ACTION_START)
        }
        talkButton.setTextColor(Color.WHITE)
        talkButton.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(48, 117, 229), Color.rgb(47, 63, 82)))
        muteButton = button("Mute microphone") {
            VoiceSessionService.sendAction(this, VoiceSessionService.ACTION_MUTE)
        }
        endButton = button("End conversation") {
            VoiceSessionService.sendAction(this, VoiceSessionService.ACTION_END)
        }
        carAudioSwitch = Switch(this).apply {
            text = "Car microphone preview"
            setTextColor(Color.WHITE)
            minHeight = dp(56)
            isChecked = CarVoiceAudio.isPreviewEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled -> CarVoiceAudio.setPreviewEnabled(this@MainActivity, enabled) }
        }
        content.addView(carAudioSwitch, LinearLayout.LayoutParams(-1, -2))
        label("Experimental: when Talk is started from the car, use Android Auto's microphone without opening a call. Car compatibility is unverified. Turn this off to restore the working call audio. Media pauses during voice; losing audio focus ends the conversation.", 13f)
        label("Connect once, while parked", 22f)
        label("Save your private voice server and access token, allow the microphone, then open ChatGPT Voice on Android Auto and choose Talk. Audio uses your internet connection.")
        if (BuildConfig.ALLOW_USB) label("For a local USB test, use http://127.0.0.1:8787 after USB forwarding is configured. Keep the phone connected to the computer during the test.", 13f)
        connectionStatus = label("")
        label("Voice server")
        backendInput = EditText(this).apply {
            hint = "https://voice.example.com"
            contentDescription = "Voice server"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(saved?.backendUrl.orEmpty())
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label("Server access token")
        tokenInput = EditText(this).apply {
            hint = if (saved == null) "Paste your server token" else "Saved securely; leave blank to keep"
            contentDescription = "Server access token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setSingleLine(true)
            // setSingleLine replaces the transformation method, so restore masking last.
            transformationMethod = PasswordTransformationMethod.getInstance()
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        saveButton = button("Save connection") {
            val existing = connectionStore.read()
            val enteredToken = tokenInput.text.toString().trim()
            // A token is never reused for a different destination without re-entry.
            val sameServer = existing?.backendUrl == backendInput.text.toString().trim().trimEnd('/')
            val token = if (enteredToken.isBlank() && sameServer) existing?.bearerToken.orEmpty() else enteredToken
            runCatching { connectionStore.save(backendInput.text.toString(), token) }
                .onSuccess {
                    tokenInput.text.clear()
                    tokenInput.hint = "Saved securely; leave blank to keep"
                    showMessage("Connection saved")
                    renderState(VoiceSessionStore.state.value)
                }
                .onFailure { showMessage(it.message ?: "Unable to save settings") }
        }
        permissionButton = button("Allow microphone and car audio") { requestVoicePermissions() }
        clearButton = button("Remove saved connection") {
            connectionStore.clear()
            backendInput.text.clear()
            tokenInput.text.clear()
            tokenInput.hint = "Paste your server token"
            renderState(VoiceSessionStore.state.value)
        }
        label("Personal app using the OpenAI API through your private server. Your ChatGPT app history and subscription are separate. Conversations start when you choose Talk and stop when you choose End.", 13f)
        activityScope.launch { VoiceSessionStore.state.collect(::renderState) }
    }

    override fun onResume() {
        super.onResume()
        if (::voiceOrb.isInitialized) voiceOrb.setHostResumed(true)
        if (::status.isInitialized) renderState(VoiceSessionStore.state.value)
    }

    override fun onPause() {
        if (::voiceOrb.isInitialized) voiceOrb.setHostResumed(false)
        super.onPause()
    }

    private fun renderState(state: VoiceSessionState) {
        voiceOrb.setConversationPhase(state.phase)
        status.text = if (state.endpoint.isBlank() || !state.isRunning) state.message
            else "${state.message}\nAudio: ${state.endpoint}"
        val hasSettings = connectionStore.read() != null
        val hasMicrophone = VoiceSessionService.hasMicrophonePermission(this)
        connectionStatus.text = when {
            !hasSettings -> "Connection setup needed"
            !hasMicrophone -> "Connection saved · microphone permission needed"
            else -> "Connection saved · microphone ready"
        }
        talkButton.isEnabled = !state.isRunning
        talkButton.text = if (state.phase == dev.roadvoice.voice.VoiceSessionPhase.FAILED) "Try again" else "Talk"
        muteButton.visibility = if (state.isRunning) View.VISIBLE else View.GONE
        endButton.visibility = if (state.isRunning) View.VISIBLE else View.GONE
        muteButton.isEnabled = state.phase != dev.roadvoice.voice.VoiceSessionPhase.ENDING
        endButton.isEnabled = state.phase != dev.roadvoice.voice.VoiceSessionPhase.ENDING
        closeButton.text = if (state.isRunning) "End and close app" else "Close app"
        muteButton.text = if (state.isLocallyMuted) "Unmute microphone" else "Mute microphone"
        backendInput.isEnabled = !state.isRunning
        tokenInput.isEnabled = !state.isRunning
        saveButton.isEnabled = !state.isRunning
        clearButton.isEnabled = !state.isRunning && hasSettings
        permissionButton.isEnabled = !state.isRunning
        carAudioSwitch.isEnabled = !state.isRunning
    }

    private fun requestVoicePermissions() {
        val requested = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (requested.isEmpty()) showMessage("Permissions ready. Tap Talk to test voice.")
        else requestPermissions(requested.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            renderState(VoiceSessionStore.state.value)
            showMessage(if (VoiceSessionService.hasMicrophonePermission(this)) "Microphone ready. Tap Talk to test voice."
                else "Microphone permission is required. You can enable it in Android app settings.")
        }
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
