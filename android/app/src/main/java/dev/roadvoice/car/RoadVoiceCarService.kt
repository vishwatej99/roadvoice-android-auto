package dev.roadvoice.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.roadvoice.ConnectionSettingsStore
import dev.roadvoice.R
import dev.roadvoice.voice.VoiceSessionPhase
import dev.roadvoice.voice.VoiceSessionService
import dev.roadvoice.voice.VoiceSessionStore
import kotlinx.coroutines.launch

class RoadVoiceCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = object : Session() {
        init {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    VoiceSessionService.onCarDisconnected(carContext)
                }
            })
        }
        override fun onCreateScreen(intent: Intent): Screen = VoiceScreen(carContext)
    }
}

/** Android Auto owns the in-call display; this screen is the simple launcher/reconnect surface. */
private class VoiceScreen(carContext: CarContext) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceSessionStore.state.collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = VoiceSessionStore.state.value
        val isConfigured = ConnectionSettingsStore(carContext).read() != null
        val hasMicrophone = VoiceSessionService.hasMicrophonePermission(carContext)
        val message = when {
            !isConfigured -> "Finish ChatGPT Voice connection setup on your phone while parked."
            !hasMicrophone -> "Allow ChatGPT Voice microphone access on your phone while parked."
            state.phase == VoiceSessionPhase.IDLE -> "Talk to your AI voice companion."
            else -> state.message
        }
        val template = MessageTemplate.Builder(message).setTitle(carContext.getString(R.string.app_name)).setHeaderAction(Action.APP_ICON)
        if (isConfigured && hasMicrophone) {
            if (state.phase == VoiceSessionPhase.ENDING) {
                // Keep an exit available during bounded cleanup or an interrupted hang-up.
                template.addAction(Action.Builder().setTitle("Close")
                    .setOnClickListener {
                        VoiceSessionService.sendAction(carContext, VoiceSessionService.ACTION_END)
                        carContext.finishCarApp()
                    }.build())
            } else if (state.isRunning) {
                template.addAction(Action.Builder()
                    .setTitle(if (state.isLocallyMuted) "Unmute" else "Mute")
                    .setOnClickListener { VoiceSessionService.sendAction(carContext, VoiceSessionService.ACTION_MUTE) }
                    .build())
                template.addAction(Action.Builder().setTitle("End")
                    .setBackgroundColor(CarColor.RED)
                    .setOnClickListener { VoiceSessionService.sendAction(carContext, VoiceSessionService.ACTION_END) }
                    .build())
            } else {
                template.addAction(Action.Builder()
                    .setTitle(if (state.phase == VoiceSessionPhase.FAILED) "Try again" else "Talk")
                    .setOnClickListener { VoiceSessionService.startFromCar(carContext) }
                    .build())
            }
        }
        return template.build()
    }
}
