package dev.roadvoice.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceSessionPhase { IDLE, CONNECTING, ACTIVE, HELD, ENDING, FAILED }

data class VoiceSessionState(
    val phase: VoiceSessionPhase = VoiceSessionPhase.IDLE,
    val isMuted: Boolean = false,
    val isLocallyMuted: Boolean = false,
    val endpoint: String = "",
    val message: String = "Ready to talk",
    val usageSeconds: Double? = null,
) {
    val isRunning: Boolean get() = phase in setOf(
        VoiceSessionPhase.CONNECTING, VoiceSessionPhase.ACTIVE, VoiceSessionPhase.HELD,
        VoiceSessionPhase.ENDING)
}

object VoiceSessionStore {
    internal val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
}
