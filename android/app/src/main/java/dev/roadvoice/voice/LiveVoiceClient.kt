package dev.roadvoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import dev.roadvoice.BuildConfig
import dev.roadvoice.car.CarVoiceAudio
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/** One native audio conversation. Telecom, not this client, owns device routing. */
class LiveVoiceClient(context: Context, private val listener: Listener, private val carAudio: CarVoiceAudio? = null) {
    interface Listener {
        fun onConnected()
        fun onFailure(message: String)
        fun onSessionEnded()
        fun onNotice(message: String)
    }

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val isClosed = AtomicBoolean(false)
    private val hasFailed = AtomicBoolean(false)
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(java.time.Duration.ofSeconds(35))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val sessionReady = CompletableDeferred<Unit>()
    private val iceComplete = CompletableDeferred<Unit>()
    private val resourcesReleased = CompletableDeferred<Unit>()
    private val audioStopped = CompletableDeferred<Unit>()
    private val sessionFinalized = CompletableDeferred<Unit>()
    @Volatile var hasFinalizedSession = false
        private set
    @Volatile var needsSessionFinalization = false
        private set
    @Volatile var latestUsageSeconds: Double? = null
        private set
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var microphone: AudioTrack? = null
    private var peer: PeerConnection? = null
    private var events: DataChannel? = null
    private var httpCall: Call? = null
    private var disconnectDeadline: Job? = null
    private var isMuted = false
    private var isPlaybackEnabled = true
    private var hasStarted = false
    private var requestedInputMute: Boolean? = null
    private var pendingInputEventId: String? = null
    private var inputCommandDeadline: Job? = null

    suspend fun connect(backendUrl: String, bearerToken: String) = withContext(dispatcher) {
        check(!hasStarted && !isClosed.get()) { "A voice client can only connect once." }
        hasStarted = true
        val address = URI(backendUrl)
        val isUsbLocal = BuildConfig.ALLOW_USB && address.scheme == "http" &&
            address.host == "127.0.0.1" && address.port in 1..65535
        require((address.scheme == "https" && !address.host.isNullOrBlank() || isUsbLocal) &&
            address.userInfo == null && address.query == null && address.fragment == null) {
            if (BuildConfig.ALLOW_USB) "Use HTTPS, or the USB loopback address."
            else "Use an HTTPS voice server."
        }
        require(bearerToken.isNotBlank()) { "A voice server access token is required." }
        try {
            withTimeout(60_000) {
                createPeerConnection()
                val connection = checkNotNull(peer)
                val offer = createOffer(connection)
                setDescription(connection, offer, isLocal = true)
                // The broker sends one SDP exchange; gather before posting so it needs no trickle API.
                withTimeout(10_000) { iceComplete.await() }
                ensureOpen()
                val localOffer = checkNotNull(connection.localDescription).description
                val answer = exchangeOffer(backendUrl, bearerToken, localOffer)
                ensureOpen()
                setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer), false)
                sessionReady.await()
                ensureOpen()
                mainHandler.post { if (!isClosed.get() && !hasFailed.get()) listener.onConnected() }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            fail(error.message ?: "The voice connection could not be started.")
            throw error
        }
    }

    fun setMuted(isMuted: Boolean) = runOnWorker {
        this.isMuted = isMuted
        updateAudioState()
    }

    fun setPlaybackEnabled(isEnabled: Boolean) = runOnWorker {
        isPlaybackEnabled = isEnabled
        updateAudioState()
    }

    /** Barrier for Telecom callbacks: audio state must be applied before acknowledging hold. */
    suspend fun awaitAudioStateApplied() {
        if (isClosed.get()) audioStopped.await()
        else withContext(dispatcher) { }
    }

    suspend fun awaitAudioStopped() = audioStopped.await()

    suspend fun awaitClosed() = resourcesReleased.await()

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        scope.launch {
            try {
                httpCall?.cancel()
                sessionReady.cancel()
                iceComplete.cancel()
                disconnectDeadline?.cancel()
                inputCommandDeadline?.cancel()
                // Stop native audio immediately, while keeping the event channel alive for
                // GPT-Live's final usage event. Telecom must not wait for this network drain.
                peer?.setAudioRecording(false)
                peer?.setAudioPlayout(false)
                microphone?.setEnabled(false)
                audioModule?.setMicrophoneMute(true)
                audioModule?.setSpeakerMute(true)
                audioStopped.complete(Unit)
                if (needsSessionFinalization && !hasFinalizedSession && events?.state() == DataChannel.State.OPEN) {
                    if (sendEvent(JSONObject().put("type", "session.close"))) {
                        withTimeoutOrNull(15_000) { sessionFinalized.await() }
                    }
                }
                events?.unregisterObserver()
                events?.close()
                events?.dispose()
                events = null
                peer?.close()
                peer?.dispose()
                peer = null
                microphone?.dispose()
                microphone = null
                audioSource?.dispose()
                audioSource = null
                factory?.dispose()
                factory = null
                audioModule?.release()
                audioModule = null
                httpClient.connectionPool.evictAll()
                httpClient.dispatcher.executorService.shutdown()
            } finally {
                audioStopped.complete(Unit)
                resourcesReleased.complete(Unit)
                scope.cancel()
                dispatcher.close()
            }
        }
    }

    private fun createPeerConnection() {
        initializeWebRtc(applicationContext)
        val audioBuilder = JavaAudioDeviceModule.builder(applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(if (carAudio != null) CarVoiceAudio.playbackAttributes() else AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setEnableVolumeLogger(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) = audioFailed()
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) = audioFailed()
                override fun onWebRtcAudioRecordError(error: String) = audioFailed()
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(error: String) = audioFailed()
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, error: String) = audioFailed()
                override fun onWebRtcAudioTrackError(error: String) = audioFailed()
            })
        if (carAudio != null) audioBuilder
            .setInputSampleRate(16_000)
            .setAudioBufferCallback(carAudio.bufferCallback)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
        audioModule = audioBuilder.createAudioDeviceModule().also {
            if (carAudio != null) it.setAudioRecordEnabled(false)
        }
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule).createPeerConnectionFactory()
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        peer = checkNotNull(factory?.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = runOnWorker {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> disconnectDeadline?.cancel()
                    PeerConnection.IceConnectionState.FAILED -> fail("Voice audio could not connect. Check your mobile data and reconnect.")
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        disconnectDeadline?.cancel()
                        disconnectDeadline = scope.launch {
                            delay(8_000)
                            fail("Voice audio disconnected. Start a new conversation when your connection returns.")
                        }
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        })) { "Unable to create the audio connection." }
        audioSource = factory?.createAudioSource(MediaConstraints())
        microphone = factory?.createAudioTrack("roadvoice-microphone", audioSource)
        checkNotNull(peer?.addTrack(checkNotNull(microphone), listOf("roadvoice-audio")))
        events = peer?.createDataChannel("oai-events", DataChannel.Init())
        checkNotNull(events).registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = runOnEvent {
                if (events?.state() == DataChannel.State.CLOSED && !isClosed.get())
                    fail("The voice connection ended before finalization. Tap Talk to reconnect.")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 256 * 1024) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                runOnEvent {
                    val event = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return@runOnEvent
                    when (event.optString("type")) {
                        "session.started" -> {
                            needsSessionFinalization = true
                            sessionReady.complete(Unit)
                            if (!isClosed.get()) updateAudioState()
                        }
                        "session.input_audio.muted", "session.input_audio.unmuted" -> {
                            if (event.optString("client_event_id") == pendingInputEventId) {
                                pendingInputEventId = null
                                inputCommandDeadline?.cancel()
                            }
                        }
                        "session.usage.updated" -> readUsage(event)
                        "session.closed" -> {
                            readUsage(event)
                            hasFinalizedSession = true
                            sessionFinalized.complete(Unit)
                            if (!isClosed.get()) mainHandler.post {
                                if (!isClosed.get()) listener.onSessionEnded()
                            }
                        }
                        "error" -> {
                            val error = event.optJSONObject("error")
                            val isInputCommandError = pendingInputEventId != null &&
                                error?.optString("client_event_id") == pendingInputEventId
                            if (!needsSessionFinalization || isInputCommandError) {
                                fail("GPT-Live could not start or change microphone state. End and try again.")
                            } else if (!isClosed.get()) {
                                // Moderation and delegated-work errors need not end a live session.
                                mainHandler.post { if (!isClosed.get()) listener.onNotice(
                                    "GPT-Live interrupted a reply or reported an error. You can continue speaking.") }
                            }
                        }
                    }
                }
            }
        })
        updateAudioState()
    }

    private fun updateAudioState() {
        val canRecord = !isMuted && isPlaybackEnabled
        microphone?.setEnabled(canRecord)
        audioModule?.setMicrophoneMute(!canRecord)
        peer?.setAudioRecording(canRecord)
        audioModule?.setSpeakerMute(!isPlaybackEnabled)
        peer?.setAudioPlayout(isPlaybackEnabled)
        synchronizeInputMute(!canRecord)
    }

    private fun synchronizeInputMute(shouldMute: Boolean) {
        if (!needsSessionFinalization || isClosed.get() || hasFinalizedSession ||
            events?.state() != DataChannel.State.OPEN || requestedInputMute == shouldMute) return
        val eventId = "input_" + UUID.randomUUID().toString()
        requestedInputMute = shouldMute
        pendingInputEventId = eventId
        inputCommandDeadline?.cancel()
        if (!sendEvent(JSONObject().put("type", if (shouldMute) "session.input_audio.mute" else "session.input_audio.unmute")
                .put("event_id", eventId))) {
            fail("Could not update the voice microphone. End and reconnect.")
            return
        }
        inputCommandDeadline = scope.launch {
            delay(10_000)
            if (pendingInputEventId == eventId) fail("GPT-Live did not confirm the microphone change. End and reconnect.")
        }
    }

    private fun readUsage(event: JSONObject) {
        val seconds = event.optJSONObject("usage")?.optDouble("seconds", Double.NaN)
        if (seconds != null && seconds.isFinite() && seconds >= 0) latestUsageSeconds = seconds
    }

    private fun sendEvent(event: JSONObject): Boolean =
        events?.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false)) == true

    private suspend fun createOffer(connection: PeerConnection): SessionDescription = suspendCancellableCoroutine { continuation ->
        connection.createOffer(object : EmptySdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }
            override fun onCreateFailure(error: String) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Could not prepare voice audio."))
            }
        }, MediaConstraints())
    }

    private suspend fun setDescription(connection: PeerConnection, description: SessionDescription, isLocal: Boolean) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : EmptySdpObserver() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("The voice audio negotiation failed."))
                }
            }
            if (isLocal) connection.setLocalDescription(observer, description) else connection.setRemoteDescription(observer, description)
        }

    private suspend fun exchangeOffer(backendUrl: String, token: String, sdp: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(backendUrl.trimEnd('/') + "/session")
            .header("Authorization", "Bearer $token")
            .post(sdp.toRequestBody("application/sdp".toMediaType())).build()
        val call = httpClient.newCall(request)
        httpCall = call
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Cannot reach the voice server. Check its address and the USB or mobile connection."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw IOException(when (response.code) {
                            401, 403 -> "Voice server access was refused. Check the access token in phone settings."
                            429 -> "The voice server is busy. Wait before starting again."
                            503 -> "The voice server is not configured or the model is unavailable."
                            else -> "The voice server could not start a session (HTTP ${response.code})."
                        })
                        val body = response.body ?: throw IOException("The voice server returned an empty answer.")
                        if (body.contentLength() > 128 * 1024) throw IOException("The voice server returned an invalid answer.")
                        // Bound chunked responses too, without loading arbitrary server content into memory.
                        val bytes = ByteArray(128 * 1024 + 1)
                        val input = body.byteStream()
                        var length = 0
                        while (length < bytes.size) {
                            val readCount = input.read(bytes, length, bytes.size - length)
                            if (readCount < 0) break
                            length += readCount
                        }
                        val answer = String(bytes, 0, length, Charsets.UTF_8)
                        if (length > 128 * 1024 || !answer.startsWith("v=0") || !answer.contains("m=audio"))
                            throw IOException("The voice server returned an invalid audio answer.")
                        if (continuation.isActive) continuation.resume(answer)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun audioFailed() = runOnWorker { fail("Microphone or speaker audio could not start. Check Android permissions and the active car audio route.") }

    private fun fail(message: String) {
        if (isClosed.get() || !hasFailed.compareAndSet(false, true)) return
        sessionReady.completeExceptionally(IOException(message))
        mainHandler.post { if (!isClosed.get()) listener.onFailure(message) }
    }

    private fun ensureOpen() { check(!isClosed.get()) { "The voice session has ended." } }

    private fun runOnWorker(action: () -> Unit) {
        if (!isClosed.get()) scope.launch { if (!isClosed.get()) action() }
    }

    /** Terminal GPT-Live events still arrive after local audio is stopped. */
    private fun runOnEvent(action: () -> Unit) {
        if (!resourcesReleased.isCompleted) scope.launch { if (!resourcesReleased.isCompleted) action() }
    }

    private open class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private var isInitialized = false
        @Synchronized private fun initializeWebRtc(context: Context) {
            if (!isInitialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                isInitialized = true
            }
        }
    }
}
