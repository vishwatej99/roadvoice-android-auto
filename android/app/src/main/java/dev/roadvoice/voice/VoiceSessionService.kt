package dev.roadvoice.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import androidx.car.app.CarContext
import dev.roadvoice.car.CarVoiceAudio
import dev.roadvoice.ConnectionSettings
import dev.roadvoice.ConnectionSettingsStore
import dev.roadvoice.MainActivity
import dev.roadvoice.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitCancellation

/** One native WebRTC session, registered with Telecom on the phone and on Android Auto alike. */
class VoiceSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var callControl: CallControlScope? = null
    private var callTasks: CallSessionTasks? = null
    private var voiceClient: LiveVoiceClient? = null
    private var closingClient: LiveVoiceClient? = null
    private var endingFailureMessage: String? = null
    private var isEnding = false
    private var isSystemMuted = false
    private var isLocallyMuted = false
    private var isHeld = false
    private var isConnected = false
    private var hasNotification = false
    private var carAudio: CarVoiceAudio? = null

    inner class LocalBinder : Binder() {
        fun sendAction(action: String) = handleAction(action)
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice conversation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Active voice conversation and microphone controls" })
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification actions may arrive as a started service. The call itself uses binding.
        intent?.action?.let(::handleAction)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handleAction(action: String) {
        when (action) {
            ACTION_START -> {
                val requestedCarContext = pendingCarContext
                pendingCarContext = null
                if (sessionJob?.isActive == true) return
                val settings = ConnectionSettingsStore(this).read()
                if (settings == null || !hasMicrophonePermission(this)) {
                    failBeforeStart("Finish connection setup and microphone permission on your phone while parked.")
                    return
                }
                isEnding = false
                closingClient = null
                endingFailureMessage = null
                isSystemMuted = false
                isLocallyMuted = false
                isHeld = false
                isConnected = false
                VoiceSessionStore.mutableState.value = VoiceSessionState(
                    phase = VoiceSessionPhase.CONNECTING, message = "Connecting to voice")
                if (requestedCarContext != null && CarVoiceAudio.isPreviewEnabled(this)) {
                    activeCarContext = requestedCarContext
                    startCarSession(settings, requestedCarContext)
                } else startSession(settings)
            }
            ACTION_END -> serviceScope.launch { endSession() }
            ACTION_MUTE -> {
                if (sessionJob?.isActive == true && !isEnding) {
                    isLocallyMuted = !isLocallyMuted
                    applyAudioState()
                } else if (sessionJob?.isActive != true) finishService()
            }
            else -> finishService()
        }
    }

    private fun startCarSession(settings: ConnectionSettings, carContext: CarContext) {
        sessionJob = serviceScope.launch {
            try {
                carAudio = CarVoiceAudio(carContext) {
                    if (!isEnding) serviceScope.launch {
                        endSession("Car microphone or audio focus ended. Tap Talk to reconnect.")
                    }
                }
                ServiceCompat.startForeground(this@VoiceSessionService, NOTIFICATION_ID,
                    buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                hasNotification = true
                carAudio?.acquireFocus()
                VoiceSessionStore.mutableState.update { it.copy(endpoint = "Car microphone preview") }
                connectVoice(settings)
                awaitCancellation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure("car_microphone_preview", error)
                if (!isEnding) endingFailureMessage =
                    "Car microphone preview could not start. Turn it off on the phone to use call audio."
            } finally {
                isEnding = true
                val client = closeAudio()
                removeNotification()
                showEndingState()
                withContext(NonCancellable) { withTimeoutOrNull(16_000) { client?.awaitClosed() } }
                publishEndedState(client)
                closingClient = null
                carAudio = null
                activeCarContext = null
                finishService()
            }
        }
    }

    private fun startSession(settings: ConnectionSettings) {
        sessionJob = serviceScope.launch {
            var stage = "register_telecom"
            try {
                val callsManager = CallsManager(this@VoiceSessionService)
                callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                val attributes = CallAttributesCompat(
                    displayName = getString(R.string.app_name),
                    // Display identity only: no SIP trunk, phone number or PSTN call is used.
                    address = Uri.parse("sip:assistant@roadvoice.invalid"),
                    direction = CallAttributesCompat.DIRECTION_OUTGOING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
                )
                stage = "add_call"
                callsManager.addCall(
                    callAttributes = attributes,
                    onAnswer = { withContext(Dispatchers.Main.immediate) { resumeAudio() } },
                    onDisconnect = {
                        withContext(Dispatchers.Main.immediate) {
                            isEnding = true
                            val client = closeAudio()
                            showEndingState()
                            // addCall is a coroutineScope. Its endpoint/mute channels can remain
                            // open after remote hang-up, so our collectors otherwise keep addCall
                            // suspended forever. Do not cancel the callback's own Telecom scope:
                            // it must return normally to acknowledge the car's disconnect request.
                            callTasks?.cancel()
                            // Telecom requires the callback within five seconds. Audio stops
                            // immediately; server finalization continues in the service finally.
                            try {
                                withTimeout(4_000) { client?.awaitAudioStopped() }
                            } finally {
                                callControl = null
                                removeNotification()
                            }
                        }
                    },
                    onSetActive = { withContext(Dispatchers.Main.immediate) { resumeAudio() } },
                    onSetInactive = {
                        withContext(Dispatchers.Main.immediate) {
                            isHeld = true
                            applyAudioState()
                            withTimeout(4_000) { voiceClient?.awaitAudioStateApplied() }
                        }
                    },
                ) {
                    callControl = this
                    val tasks = CallSessionTasks(this)
                    callTasks = tasks
                    // An outgoing CallStyle needs an actual foreground-service notification.
                    // Promote before audio starts; Telecom still supplies microphone capability.
                    // Posting inside addCall lets its notification observer see the active call.
                    stage = "promote_phone_call_service"
                    ServiceCompat.startForeground(this@VoiceSessionService, NOTIFICATION_ID,
                        buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                    hasNotification = true
                    stage = "call_active"
                    // Telecom owns routing. Never call startBluetoothSco/setCommunicationDevice.
                    tasks.launch {
                        currentCallEndpoint.collect { endpoint ->
                            VoiceSessionStore.mutableState.update { it.copy(endpoint = endpoint.name.toString()) }
                            updateNotification()
                        }
                    }
                    tasks.launch {
                        isMuted.collect { muted ->
                            isSystemMuted = muted
                            applyAudioState()
                        }
                    }
                    tasks.launch { connectVoice(settings) }
                }
            } catch (error: TimeoutCancellationException) {
                logFailure(stage, error)
                if (!isEnding) endingFailureMessage = "Android did not complete the voice setup in time. Try again."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure(stage, error)
                if (!isEnding) endingFailureMessage =
                    if (stage == "promote_phone_call_service")
                        "Android could not start the call notification. Check app permissions and try again."
                    else "Android could not open the voice session. End other calls, check the connection and try again."
            } finally {
                isEnding = true
                // addCall has returned: its call may already be removed. Never repost CallStyle
                // from this cleanup path or mask the original failure with a notification error.
                callControl = null
                callTasks?.cancel()
                callTasks = null
                removeNotification()
                val client = closeAudio()
                showEndingState()
                // Preserve the binding while GPT-Live acknowledges session.close. Telecom may
                // already have disconnected after a remote End, so this is best-effort then.
                withContext(NonCancellable) {
                    withTimeoutOrNull(16_000) { client?.awaitClosed() }
                }
                publishEndedState(client)
                closingClient = null
                finishService()
            }
        }
    }

    private suspend fun connectVoice(settings: ConnectionSettings) {
        // Grant Telecom audio focus before WebRTC constructs its native audio devices.
        // The app remains in CONNECTING and capture is disabled until the model is ready.
        if (carAudio == null && callControl?.setActive() !is CallControlResult.Success) {
            endSession("Android could not activate the audio route. End other calls and try again.")
            return
        }
        val connected = CompletableDeferred<Unit>()
        val client = LiveVoiceClient(this, object : LiveVoiceClient.Listener {
            override fun onConnected() {
                if (!isEnding) connected.complete(Unit)
            }

            override fun onFailure(message: String) {
                if (!isEnding) {
                    val safeMessage = safeVoiceFailure(message)
                    connected.completeExceptionally(IllegalStateException(safeMessage))
                    serviceScope.launch { endSession(safeMessage) }
                }
            }

            override fun onSessionEnded() {
                if (!isEnding) serviceScope.launch { endSession() }
            }

            override fun onNotice(message: String) {
                if (!isEnding) {
                    VoiceSessionStore.mutableState.update { it.copy(message = message) }
                    updateNotification()
                }
            }
        }, carAudio)
        voiceClient = client
        client.setMuted(true)
        client.setPlaybackEnabled(false)
        try {
            withTimeout(65_000) {
                client.connect(settings.backendUrl, settings.bearerToken)
                connected.await()
            }
            if (isEnding) return
            isConnected = true
            applyAudioState()
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException && !isEnding) {
                endSession("Voice connection timed out. Check your mobile data and backend, then try again.")
            } else throw error
        } catch (error: Exception) {
            logFailure("connect_voice", error)
            if (!isEnding) endSession(safeVoiceFailure(error.message))
        }
    }

    private suspend fun resumeAudio() {
        isHeld = false
        applyAudioState()
        withTimeout(4_000) { voiceClient?.awaitAudioStateApplied() }
    }

    private fun applyAudioState() {
        val isMuted = isLocallyMuted || isSystemMuted
        try {
            carAudio?.setEnabled(!isMuted && !isHeld && isConnected && !isEnding)
        } catch (error: Exception) {
            logFailure("car_microphone_capture", error)
            serviceScope.launch { endSession("Car microphone preview stopped. Turn it off on the phone to use call audio.") }
            return
        }
        voiceClient?.setMuted(isMuted || isHeld || !isConnected || isEnding)
        voiceClient?.setPlaybackEnabled(isConnected && !isHeld && !isEnding)
        if (isEnding) return
        VoiceSessionStore.mutableState.update {
            it.copy(
                phase = when {
                    !isConnected -> VoiceSessionPhase.CONNECTING
                    isHeld -> VoiceSessionPhase.HELD
                    else -> VoiceSessionPhase.ACTIVE
                },
                isMuted = isMuted,
                isLocallyMuted = isLocallyMuted,
                message = when {
                    !isConnected -> "Connecting to voice"
                    isHeld -> "On hold — microphone and speaker paused"
                    isSystemMuted -> "Microphone muted by Android"
                    isLocallyMuted -> "Microphone muted"
                    else -> "Listening — speak naturally"
                },
            )
        }
        updateNotification()
    }

    private suspend fun endSession(failureMessage: String? = null) {
        if (isEnding) {
            // A second End must still break an orphaned call scope; it cannot be a no-op.
            callTasks?.cancel()
            sessionJob?.cancel()
            if (sessionJob == null) finishService()
            return
        }
        isEnding = true
        endingFailureMessage = failureMessage
        val client = closeAudio()
        showEndingState()
        // Stop capture first, then release the car's call UI/audio route. Server finalization
        // runs in startSession's finally block and must never keep the car in an active call.
        withTimeoutOrNull(4_000) { client?.awaitAudioStopped() }
        withTimeoutOrNull(3_000) {
            runCatching { callControl?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
        }
        sessionJob?.cancel()
        if (sessionJob == null) finishService()
    }

    private fun closeAudio(): LiveVoiceClient? {
        carAudio?.close()
        val client = voiceClient ?: closingClient
        client?.setMuted(true)
        client?.setPlaybackEnabled(false)
        client?.close()
        closingClient = client
        voiceClient = null
        isConnected = false
        return client
    }

    private fun showEndingState() {
        VoiceSessionStore.mutableState.update {
            it.copy(phase = VoiceSessionPhase.ENDING, isMuted = true,
                message = "Ending conversation — microphone off")
        }
        updateNotification()
    }

    private fun publishEndedState(client: LiveVoiceClient?) {
        val isFinalizationMissing = client?.needsSessionFinalization == true && !client.hasFinalizedSession
        val message = when {
            isFinalizationMissing -> "Audio stopped. Server closure was not confirmed; check API usage before starting again."
            endingFailureMessage != null -> checkNotNull(endingFailureMessage)
            else -> "Conversation ended"
        }
        VoiceSessionStore.mutableState.value = VoiceSessionState(
            phase = if (isFinalizationMissing || endingFailureMessage != null) VoiceSessionPhase.FAILED else VoiceSessionPhase.IDLE,
            message = message,
            usageSeconds = client?.latestUsageSeconds,
        )
    }

    private fun failBeforeStart(message: String) {
        VoiceSessionStore.mutableState.value = VoiceSessionState(phase = VoiceSessionPhase.FAILED, message = message)
        removeNotification()
        finishService()
    }

    private fun updateNotification() {
        if (!hasNotification || (callControl == null && carAudio == null)) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (error: RuntimeException) {
            // Stop retrying a rejected notification. In particular, cleanup must never throw.
            logFailure("update_call_notification", error)
            removeNotification()
            if (!isEnding) serviceScope.launch {
                endSession("Android could not keep the call notification active. The conversation has ended.")
            }
        }
    }

    private fun removeNotification() {
        hasNotification = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            .onFailure { logFailure("stop_phone_call_service", it) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
            .onFailure { logFailure("remove_call_notification", it) }
    }

    private fun logFailure(stage: String, error: Throwable) {
        // Exception messages, bodies and stack traces can contain connection details.
        Log.w("RoadVoiceSession", "$stage: ${error.javaClass.simpleName}")
    }

    private fun safeVoiceFailure(message: String?): String = when {
        message in SAFE_VOICE_FAILURES -> checkNotNull(message)
        message?.matches(Regex("The voice server could not start a session \\(HTTP [1-5][0-9]{2}\\)\\.")) == true -> message
        else -> "Voice connection failed. Check the backend URL, access token and mobile data."
    }

    private fun finishService() {
        releaseBinding()
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val state = VoiceSessionStore.state.value
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode, Intent(this, VoiceSessionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(state.message)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (state.isLocallyMuted) "Unmute" else "Mute", actionIntent(ACTION_MUTE, 2))
        if (carAudio != null) notification
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "End conversation", actionIntent(ACTION_END, 1))
        else notification.setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(
                Person.Builder().setName(getString(R.string.app_name)).setImportant(true).build(),
                actionIntent(ACTION_END, 1)))
        return notification.build()
    }

    override fun onDestroy() {
        isEnding = true
        callTasks?.cancel()
        callTasks = null
        closeAudio()
        activeCarContext = null
        removeNotification()
        serviceScope.cancel()
        if (VoiceSessionStore.state.value.isRunning) {
            VoiceSessionStore.mutableState.value = VoiceSessionState(message = "Conversation ended")
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.roadvoice.START"
        const val ACTION_END = "dev.roadvoice.END"
        const val ACTION_MUTE = "dev.roadvoice.MUTE"
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 41
        private val SAFE_VOICE_FAILURES = setOf(
            "Cannot reach the voice server. Check its address and the USB or mobile connection.",
            "Voice server access was refused. Check the access token in phone settings.",
            "The voice server is busy. Wait before starting again.",
            "The voice server is not configured or the model is unavailable.",
            "The voice server returned an empty answer.",
            "The voice server returned an invalid answer.",
            "The voice server returned an invalid audio answer.",
            "Could not prepare voice audio.",
            "The voice audio negotiation failed.",
            "Unable to create the audio connection.",
            "Microphone or speaker audio could not start. Check Android permissions and the active car audio route.",
            "Voice audio could not connect. Check your mobile data and reconnect.",
            "Voice audio disconnected. Start a new conversation when your connection returns.",
            "The voice connection ended before finalization. Tap Talk to reconnect.",
            "GPT-Live could not start or change microphone state. End and try again.",
            "Could not update the voice microphone. End and reconnect.",
            "GPT-Live did not confirm the microphone change. End and reconnect.",
        )
        private var bindingContext: Context? = null
        private var serviceConnection: ServiceConnection? = null
        private var serviceBinder: LocalBinder? = null
        private val pendingActions = mutableListOf<String>()
        private var pendingCarContext: CarContext? = null
        private var activeCarContext: CarContext? = null

        fun startFromCar(context: CarContext) {
            pendingCarContext = context
            sendAction(context, ACTION_START)
        }

        fun onCarDisconnected(context: CarContext) {
            if (pendingCarContext === context) {
                pendingCarContext = null
                pendingActions.removeAll { it == ACTION_START }
                if (serviceBinder == null && pendingActions.isEmpty()) releaseBinding()
            }
            if (activeCarContext === context) sendAction(context, ACTION_END)
        }

        fun hasMicrophonePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun sendAction(context: Context, action: String) {
            // Binding is permitted for a user action from the car host, even when the phone
            // Activity is not visible. The service promotes its call notification after addCall;
            // Telecom supplies microphone delegation. Car-host background eligibility is tested
            // separately from the foreground phone path.
            serviceBinder?.let { it.sendAction(action); return }
            if (action != ACTION_START && serviceConnection == null) return
            pendingActions.add(action)
            if (serviceConnection != null) return
            val applicationContext = context.applicationContext
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val localBinder = binder as LocalBinder
                    serviceBinder = localBinder
                    val actions = pendingActions.toList()
                    pendingActions.clear()
                    actions.forEach(localBinder::sendAction)
                }

                override fun onServiceDisconnected(name: ComponentName) = handleBindingFailure()
                override fun onBindingDied(name: ComponentName) = handleBindingFailure()
                override fun onNullBinding(name: ComponentName) = handleBindingFailure()
            }
            bindingContext = applicationContext
            serviceConnection = connection
            try {
                val intent = Intent(context, VoiceSessionService::class.java).setAction(action)
                if (!applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    handleBindingFailure()
                }
            } catch (_: RuntimeException) {
                handleBindingFailure()
            }
        }

        private fun handleBindingFailure() {
            releaseBinding()
            VoiceSessionStore.mutableState.value = VoiceSessionState(
                phase = VoiceSessionPhase.FAILED,
                message = "Android could not start voice. Check microphone access on your phone while parked and try again.")
        }

        private fun releaseBinding() {
            val context = bindingContext
            val connection = serviceConnection
            bindingContext = null
            serviceConnection = null
            serviceBinder = null
            pendingActions.clear()
            pendingCarContext = null
            if (context != null && connection != null) runCatching { context.unbindService(connection) }
        }
    }
}
