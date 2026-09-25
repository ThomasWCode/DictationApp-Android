package com.thomaswcode.dictationapp.core.session

import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DictationState { Idle, Arming, Recording, Finalising, PostProcessing, Inserting }

enum class DictationTrigger {
    Press,
    Release,
    BeginReceived,
    ConnectTimeout,
    SocketFault,
    Cancel,
    CapReached,
    HandshakeComplete,
    NothingHeard,
    PostProcessed,
    Inserted,
    CopiedOnly,
    Failed,
}

/**
 * The dictation lifecycle as an explicit table. Unlisted (state, trigger) pairs are ignored, so an
 * unexpected event can never throw; a press while busy is reported through [onPressIgnored].
 *
 * Android difference from Windows: a quick tap on the bubble starts a hands-free dictation instead of
 * cancelling, so Release in Arming always finalises (the bubble only sends it after a real hold or a tap
 * on the check mark).
 */
class DictationStateMachine(
    var onPressIgnored: () -> Unit = {},
    var onTransition: (from: DictationState, trigger: DictationTrigger, to: DictationState) -> Unit = { _, _, _ -> },
) {
    @Volatile var state: DictationState = DictationState.Idle
        private set

    val isIdle: Boolean get() = state == DictationState.Idle

    @Synchronized
    fun fire(trigger: DictationTrigger): DictationState {
        val from = state
        val to = next(from, trigger)
        if (to == null) {
            if (trigger == DictationTrigger.Press && from != DictationState.Idle) onPressIgnored()
            return from
        }

        state = to
        onTransition(from, trigger, to)
        return to
    }

    companion object {
        fun next(from: DictationState, trigger: DictationTrigger): DictationState? = when (from) {
            DictationState.Idle -> when (trigger) {
                DictationTrigger.Press -> DictationState.Arming
                else -> null
            }
            DictationState.Arming -> when (trigger) {
                DictationTrigger.BeginReceived -> DictationState.Recording
                DictationTrigger.Release -> DictationState.Finalising
                DictationTrigger.Cancel, DictationTrigger.ConnectTimeout, DictationTrigger.SocketFault, DictationTrigger.Failed -> DictationState.Idle
                else -> null
            }
            DictationState.Recording -> when (trigger) {
                DictationTrigger.Release, DictationTrigger.CapReached -> DictationState.Finalising
                DictationTrigger.Cancel, DictationTrigger.SocketFault, DictationTrigger.Failed -> DictationState.Idle
                else -> null
            }
            DictationState.Finalising -> when (trigger) {
                DictationTrigger.HandshakeComplete -> DictationState.PostProcessing
                DictationTrigger.NothingHeard, DictationTrigger.Cancel, DictationTrigger.Failed -> DictationState.Idle
                else -> null
            }
            DictationState.PostProcessing -> when (trigger) {
                DictationTrigger.PostProcessed -> DictationState.Inserting
                DictationTrigger.Cancel, DictationTrigger.Failed -> DictationState.Idle
                else -> null
            }
            DictationState.Inserting -> when (trigger) {
                DictationTrigger.Inserted, DictationTrigger.CopiedOnly, DictationTrigger.Failed -> DictationState.Idle
                else -> null
            }
        }
    }
}

/** Immutable snapshot of what the bubble should show. */
data class DictationStatus(
    val state: DictationState = DictationState.Idle,
    val liveText: String = "",
    val level: Float = 0f,
    val tone: Tone = Tone.Neutral,
    val cleanupLevel: CleanupLevel = CleanupLevel.Light,
    /** Short outcome text ("cleanup skipped", "Nothing heard", "Failed"), shown briefly after a dictation. */
    val badge: String? = null,
    /** Increments with every published badge so a repeated identical badge still animates. */
    val badgeSeq: Int = 0,
    val startedAt: Long? = null,
    val appName: String? = null,
    /** True when the dictation was started with a tap and keeps running with nothing held. */
    val handsFree: Boolean = false,
) {
    val isActive: Boolean get() = state != DictationState.Idle
}

/**
 * Thread-safe pub/sub between the orchestrator (publisher) and the bubble (subscriber). Keeps the
 * orchestrator free of any UI dependency.
 */
class StatusHub {
    private val state = MutableStateFlow(DictationStatus())
    private val flashes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val status: StateFlow<DictationStatus> = state.asStateFlow()

    /** A brief shake: the user pressed the bubble while a dictation was already finishing. */
    val flashed: SharedFlow<Unit> = flashes.asSharedFlow()

    val current: DictationStatus get() = state.value

    fun publish(status: DictationStatus) {
        state.value = status
    }

    fun update(transform: (DictationStatus) -> DictationStatus) = state.update(transform)

    fun flash() {
        flashes.tryEmit(Unit)
    }
}
