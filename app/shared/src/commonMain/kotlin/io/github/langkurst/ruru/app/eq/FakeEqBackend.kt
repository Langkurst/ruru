package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A backend that only remembers what it was asked to do: for demo mode, snapshots and tests. Like
 * Equalizer APO it has no limiter unless [hasLimiter] says so.
 */
class FakeEqBackend(
    initial: EqBackendStatus = EqBackendStatus.Ready,
    override val hasLimiter: Boolean = false,
    override val setupNote: Words? = Words("eq_apo_note"),
) : EqBackend {
    private val current = MutableStateFlow(initial)
    override val status: StateFlow<EqBackendStatus> = current

    private val log = MutableStateFlow<List<ProEqState>>(emptyList())
    private val offs = MutableStateFlow(0)
    private val refreshes = MutableStateFlow(0)
    private val buttons = MutableStateFlow<List<EqSetupAction>>(emptyList())

    /** Every state [apply] ran, oldest first. */
    val applied: List<ProEqState> get() = log.value

    /** How many times [off] and [refresh] ran. */
    val offCount: Int get() = offs.value
    val refreshCount: Int get() = refreshes.value

    /** The buttons the person pressed ([perform]), oldest first. */
    val performed: List<EqSetupAction> get() = buttons.value

    override suspend fun apply(state: ProEqState) {
        if (!current.value.switchable) return
        log.update { it + state }
        current.value = if (state.enabled) EqBackendStatus.On else EqBackendStatus.Ready
    }

    override suspend fun off() {
        offs.update { it + 1 }
        if (current.value == EqBackendStatus.On) current.value = EqBackendStatus.Ready
    }

    override fun perform(action: EqSetupAction) {
        buttons.update { it + action }
        if (action.kind == EqSetupKind.ALLOW) current.value = EqBackendStatus.Ready
    }

    override fun refresh() {
        refreshes.update { it + 1 }
    }

    /** Test control: move to any status. */
    fun setStatus(status: EqBackendStatus) {
        current.value = status
    }
}
