package dev.roadvoice.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.telecom.DisconnectCause
import android.view.View
import android.widget.Button
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import dev.roadvoice.MainActivity
import dev.roadvoice.voice.VoiceSessionPhase
import dev.roadvoice.voice.VoiceSessionService
import dev.roadvoice.voice.VoiceSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Debug-only test APK. Uses two brief live sessions; never prints credentials or voice data. */
class VoiceLifecycleDiagnostic(private val instrumentation: Instrumentation) {
    @Suppress("UNCHECKED_CAST")
    fun run() {
        val context = instrumentation.targetContext
        val result = Bundle()
        var activity: Activity? = null
        var connection: ServiceConnection? = null
        var passed = false
        try {
            check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen = activity
            runBlocking {
                val ready = CompletableDeferred<VoiceSessionService>()
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        try {
                            val outer = binder.javaClass.declaredFields.first { it.type == VoiceSessionService::class.java }
                            outer.isAccessible = true
                            ready.complete(outer.get(binder) as VoiceSessionService)
                        } catch (error: Exception) { ready.completeExceptionally(error) }
                    }
                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
                withContext(Dispatchers.Main) {
                    check(context.bindService(Intent(context, VoiceSessionService::class.java),
                        checkNotNull(connection), Context.BIND_AUTO_CREATE))
                }
                val service = withTimeout(5_000) { ready.await() }
                withContext(Dispatchers.Main) { VoiceSessionService.sendAction(screen, VoiceSessionService.ACTION_START) }
                val first = withTimeout(65_000) { VoiceSessionStore.state.first {
                    it.phase == VoiceSessionPhase.ACTIVE || it.phase == VoiceSessionPhase.FAILED
                } }
                check(first.phase == VoiceSessionPhase.ACTIVE)
                result.putBoolean("firstSessionConnected", true)

                val started = System.nanoTime()
                withContext(Dispatchers.Main) {
                    val controlField = service.javaClass.getDeclaredField("callControl").apply { isAccessible = true }
                    val control = controlField.get(service) as CallControlScope
                    val sessionField = control.javaClass.getDeclaredField("session").apply { isAccessible = true }
                    val session = sessionField.get(control)
                    val callback = session.javaClass.getMethod("getOnDisconnectCallback").invoke(session)
                        as (suspend (DisconnectCause) -> Unit)
                    val cause = DisconnectCause(DisconnectCause.REMOTE)
                    // Invoke the exact app callback registered with Core-Telecom, then complete
                    // platform disconnection. Do not use ACTION_END to mask the remote-path bug.
                    callback(cause)
                    check(control.disconnect(cause) is CallControlResult.Success)
                }
                val ended = withTimeout(20_000) { VoiceSessionStore.state.first { !it.isRunning } }
                check(ended.phase == VoiceSessionPhase.IDLE)
                result.putBoolean("remoteHangupEnded", true)
                result.putLong("remoteHangupMs", (System.nanoTime() - started) / 1_000_000)

                // Allow the old service's finally block to finish before the next user action.
                withContext(Dispatchers.Main) { }
                withContext(Dispatchers.Main) { VoiceSessionService.sendAction(screen, VoiceSessionService.ACTION_START) }
                val second = withTimeout(65_000) { VoiceSessionStore.state.first {
                    it.phase == VoiceSessionPhase.ACTIVE || it.phase == VoiceSessionPhase.FAILED
                } }
                check(second.phase == VoiceSessionPhase.ACTIVE)
                result.putBoolean("secondSessionConnected", true)
                withContext(Dispatchers.Main) {
                    val views = ArrayList<View>()
                    screen.window.decorView.findViewsWithText(views, "End and close app", View.FIND_VIEWS_WITH_TEXT)
                    check(views.filterIsInstance<Button>().single().performClick())
                }
                val closed = withTimeout(20_000) { VoiceSessionStore.state.first { !it.isRunning } }
                check(closed.phase == VoiceSessionPhase.IDLE)
                withTimeout(5_000) { while (!withContext(Dispatchers.Main) { screen.isDestroyed }) delay(50) }
                result.putBoolean("closeReturnedIdle", true)
                result.putBoolean("activityClosed", true)
                passed = true
            }
        } catch (error: Exception) {
            result.putString("errorClass", error.javaClass.simpleName)
        } finally {
            instrumentation.runOnMainSync {
                VoiceSessionService.sendAction(context, VoiceSessionService.ACTION_END)
                activity?.finishAndRemoveTask()
                connection?.let { runCatching { context.unbindService(it) } }
            }
            instrumentation.finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
