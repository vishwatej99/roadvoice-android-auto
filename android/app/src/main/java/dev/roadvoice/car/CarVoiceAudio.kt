package dev.roadvoice.car

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat
import dev.roadvoice.voice.PcmAudioBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import org.webrtc.audio.JavaAudioDeviceModule

/** Experimental host microphone route. Never opens Bluetooth SCO or a Telecom call. */
class CarVoiceAudio(private val carContext: CarContext, private val onStopped: () -> Unit) {
    private val audioManager = carContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isClosed = AtomicBoolean(false)
    private val hasReportedStop = AtomicBoolean(false)
    private val queue = PcmAudioBuffer()
    private val reader = Executors.newSingleThreadExecutor()
    private var recorder: CarAudioRecord? = null
    private var hasFocus = false
    private var hasStarted = false
    private var nextFrameNs = 0L
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(playbackAttributes())
        .setOnAudioFocusChangeListener({ focus ->
            // Never fight navigation, another assistant, or a real call for the microphone.
            if (focus != AudioManager.AUDIOFOCUS_GAIN) {
                close()
                reportStopped()
            }
        }, mainHandler).build()

    fun acquireFocus() {
        check(carContext.carAppApiLevel >= 5) { "Car microphone requires Car App API 5." }
        check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        hasFocus = true
    }

    fun setEnabled(enabled: Boolean) {
        queue.setEnabled(enabled && !isClosed.get())
        if (!enabled || hasStarted || isClosed.get()) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required.")
        }
        hasStarted = true
        val input = CarAudioRecord.create(carContext)
        recorder = input
        input.startRecording()
        reader.execute {
            val data = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            try {
                while (!isClosed.get()) {
                    val count = input.read(data, 0, data.size)
                    if (count < 0) break
                    if (count > 0) queue.offer(data, count)
                    else LockSupport.parkNanos(1_000_000)
                }
            } catch (_: Exception) {
                // Remote host may close its pipe on disconnect; no raw error or audio is logged.
            } finally {
                if (!isClosed.get()) mainHandler.post { close(); reportStopped() }
            }
        }
    }

    val bufferCallback = JavaAudioDeviceModule.AudioBufferCallback { buffer, format, channels, rate, _, _ ->
        // With AudioRecord disabled, this callback owns the 10 ms capture clock.
        val now = System.nanoTime()
        if (nextFrameNs == 0L || now - nextFrameNs > 100_000_000) nextFrameNs = now
        val deadline = nextFrameNs
        var remaining = deadline - System.nanoTime()
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            LockSupport.parkNanos(remaining)
            remaining = deadline - System.nanoTime()
        }
        nextFrameNs = deadline + 10_000_000
        if (format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && rate == 16_000 && buffer.capacity() == 320) {
            queue.fill(buffer)
        } else {
            buffer.clear()
            while (buffer.hasRemaining()) buffer.put(0.toByte())
            buffer.rewind()
            reportStopped()
        }
        System.nanoTime()
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        queue.setEnabled(false)
        runCatching { recorder?.stopRecording() }
        recorder = null
        reader.shutdownNow()
        if (hasFocus) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        hasFocus = false
    }

    private fun reportStopped() {
        if (hasReportedStop.compareAndSet(false, true)) mainHandler.post(onStopped)
    }

    companion object {
        fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

        fun isPreviewEnabled(context: Context): Boolean =
            context.getSharedPreferences("voice_preferences", Context.MODE_PRIVATE)
                .getBoolean("car_microphone_preview", false)

        fun setPreviewEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("voice_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean("car_microphone_preview", enabled).apply()
        }
    }
}
