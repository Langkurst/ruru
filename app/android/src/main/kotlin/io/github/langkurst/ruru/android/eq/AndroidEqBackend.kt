package io.github.langkurst.ruru.android.eq

import io.github.langkurst.ruru.android.service.ServiceNeed
import io.github.langkurst.ruru.android.service.ServiceNeeds
import io.github.langkurst.ruru.app.eq.EqBackend
import io.github.langkurst.ruru.app.eq.EqBackendStatus
import io.github.langkurst.ruru.app.eq.EqSetupAction
import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.dsp.DynamicsProcessingPlan
import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Pro EQ on Android: one DynamicsProcessing effect on each audio session a music or video app
 * announces, kept in step with the Equaliser page. It only filters while the sound goes to Bluetooth
 * headphones (the same curve on the phone's speaker sounds wrong) and says so in [paused]. Switching it on
 * tells [needs] that [RuruService] is wanted, so Android keeps Ruru alive; switching it off lets go.
 *
 * Each pair of headphones' preset is kept by the Equaliser page's model, by name; this class only plays
 * the sound it is given. The global output mix (session 0) is the experimental [useGlobalSession] option.
 */
class AndroidEqBackend internal constructor(
    private val settings: SettingsStore,
    scope: CoroutineScope,
    private val registry: SessionRegistry,
    private val needs: ServiceNeeds,
    private val route: OutputRoute,
    private val engine: DynamicsProcessingEngine,
    private val elapsedMs: () -> Long,
    private val io: CoroutineDispatcher,
) : EqBackend {
    constructor(
        settings: SettingsStore,
        scope: CoroutineScope,
        registry: SessionRegistry,
        needs: ServiceNeeds,
        route: OutputRoute,
    ) : this(settings, scope, registry, needs, route, DynamicsProcessingEngine(), android.os.SystemClock::elapsedRealtime, Dispatchers.IO)

    private val current = MutableStateFlow<EqBackendStatus>(EqBackendStatus.Ready)
    override val status: StateFlow<EqBackendStatus> = current

    /** Android's effect has a limiter; the preamp is not held at the bands' headroom. */
    override val hasLimiter: Boolean = true

    override val setupNote: Words = Words("eq_android_note")

    private val pausedFlow = MutableStateFlow(false)

    /** The equaliser is on but the sound is not going to Bluetooth headphones, so nothing is filtered. */
    val paused: StateFlow<Boolean> = pausedFlow

    private val warningFlow = MutableStateFlow<EngineWarning?>(null)

    /** Something the person may want to know (another app's effect holds a player), or null. */
    val warning: StateFlow<EngineWarning?> = warningFlow

    private val globalFlow = MutableStateFlow(settings.getBoolean(KEY_GLOBAL_SESSION, false))

    /** The experimental option: also try the global output mix, for apps that do not announce their sound. */
    val useGlobalSession: StateFlow<Boolean> = globalFlow

    private val mutex = Mutex()
    private val aging = SessionAging(registry)
    private val scope = scope

    /** What the equaliser should do, or null while it is off. */
    private var wanted: ProEqState? = null

    init {
        scope.launch {
            combine(registry.sessions, route.bluetooth, globalFlow) { _, _, _ -> }.collect {
                mutex.withLock { reconcile() }
            }
        }
    }

    override suspend fun apply(state: ProEqState) {
        mutex.withLock {
            wanted = state.takeIf { it.enabled }
            reconcile()
            // Only a switch-on asks for the service (a late session event must not bring it back after Stop).
            if (wanted != null && current.value == EqBackendStatus.On) needs.set(ServiceNeed.Eq, true)
        }
    }

    override suspend fun off() {
        mutex.withLock {
            wanted = null
            reconcile()
        }
    }

    override fun perform(action: EqSetupAction) = Unit

    override fun refresh() {
        route.refresh()
        scope.launch { mutex.withLock { reconcile() } }
    }

    fun setUseGlobalSession(on: Boolean) {
        settings.putBoolean(KEY_GLOBAL_SESSION, on)
        if (!on) engine.clearWarning()
        globalFlow.value = on
    }

    /** Bring the effects in line with (what the page wants x announced sessions x the output route x the global option). Call with [mutex] held. */
    private suspend fun reconcile() {
        val state = wanted
        val bluetooth = route.bluetooth.value
        // A player the system kills never says it has closed: forget stale sessions (see SessionAging).
        aging.prune(bluetooth, elapsedMs())
        val decision = EqRunPolicy.decide(state != null, bluetooth, registry.sessions.value, globalFlow.value)
        val planner = state?.let { EqPlanner.planner(it, route.sampleRate ?: DynamicsProcessingPlan.DEFAULT_SAMPLE_RATE) }
        withContext(io) {
            // Also stops trying sessions another app took once they are gone.
            engine.retain(decision.targets)
            if (planner != null) for (id in decision.targets) engine.attach(id, planner, enabled = true)
        }
        aging.noteAttached(engine.attached)
        val engineStatus = engine.status.value
        pausedFlow.value = decision.pausedByRoute
        warningFlow.value = engineStatus.warning
        val unsupported = state != null && !engineStatus.isAvailable
        // The service is let go as soon as the equaliser is off or the phone cannot run it; [apply] asks for it.
        if (state == null || unsupported) needs.set(ServiceNeed.Eq, false)
        current.value = when {
            unsupported -> EqBackendStatus.Unavailable(Words("eq_android_unsupported"))
            state != null -> EqBackendStatus.On
            else -> EqBackendStatus.Ready
        }
    }

    companion object {
        const val KEY_GLOBAL_SESSION = "eq.android.global"
    }
}
