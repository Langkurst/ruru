package io.github.langkurst.ruru.android

import android.app.Application
import io.github.langkurst.ruru.android.eq.AndroidEqBackend
import io.github.langkurst.ruru.android.eq.AndroidOutputRoute
import io.github.langkurst.ruru.android.eq.SessionRegistry
import io.github.langkurst.ruru.android.service.RuruService
import io.github.langkurst.ruru.android.sleep.AndroidSleepEngine
import io.github.langkurst.ruru.android.sleep.NightDriver
import io.github.langkurst.ruru.android.sleep.SleepRuntime
import io.github.langkurst.ruru.app.night.NightRunner
import io.github.langkurst.ruru.app.sleep.SleepModel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.github.langkurst.ruru.android.service.ServiceNeeds
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.eq.FakeEqBackend
import io.github.langkurst.ruru.app.eq.ProEqModel
import io.github.langkurst.ruru.app.settings.createSettingsStore
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Holds the one connection for the whole app, so turning the screen doesn't reconnect. */
class RuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        io.github.langkurst.ruru.app.settings.AndroidSettings.init(this)
        io.github.langkurst.ruru.app.tools.AndroidAudio.init(this)
        // RuruService runs while any feature needs it (the equaliser now, the night schedule later) and not otherwise.
        startSleep()
        scope.launch {
            serviceNeeds.needs.map { it.isNotEmpty() }.distinctUntilChanged().collect { wanted ->
                if (wanted) RuruService.start(this@RuruApplication) else RuruService.stop(this@RuruApplication)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startSleep() {
        // Every restart of Ruru's process (a reboot, an alarm going off, an update) sets the alarms and the night schedule again.
        sleepModel.armAlarms()
        nightDriver.start()
        // Settings a night changed are put back when the headphones are next connected and read.
        scope.launch { model.controller.flatMapLatest { it.state }.collect { sleepRuntime.restoreWhenReady() } }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val platform: AndroidHeadsetPlatform by lazy { AndroidHeadsetPlatform(this) }
    val model: AppModel by lazy { AppModel(platform, scope) }

    /** What the Sleep tab, the sleep service and the receivers share: the machine, the sounds, the alarm setter. */
    val sleepRuntime: SleepRuntime by lazy { SleepRuntime(this, createSettingsStore(), { model }) }

    /** The Sleep tab's model. */
    val sleepModel: SleepModel by lazy { SleepModel(createSettingsStore(), AndroidSleepEngine(this, sleepRuntime)) }

    /** The night schedule's runner: the same one the desktop uses, kept running by [nightDriver]. */
    val night: NightRunner by lazy { NightRunner(createSettingsStore(), scope, model.nightHeadset()) }
    val nightDriver: NightDriver by lazy { NightDriver(scope, night, serviceNeeds, sleepRuntime.armer) { model.start() } }

    /** The reasons the foreground service should run. Features add theirs; the notification's Stop tells them to let go. */
    val serviceNeeds = ServiceNeeds()

    /** The Android equaliser: effects on the sessions music apps announce, only while sound goes to Bluetooth headphones. */
    val eqBackend: AndroidEqBackend by lazy {
        AndroidEqBackend(createSettingsStore(), scope, SessionRegistry.shared, serviceNeeds, AndroidOutputRoute(this))
    }

    /**
     * The Equaliser page's model. Built at start-up and when an app announces its sound, so what was on
     * when Ruru last closed runs again. Demo mode swaps in a fake backend and touches nothing real.
     */
    val proEq: ProEqModel by lazy {
        ProEqModel(createSettingsStore(), scope, eqBackend, demoBackend = { FakeEqBackend(hasLimiter = true, setupNote = Words("eq_android_note")) })
            .also { eq -> serviceNeeds.onStopRequested { eq.setEnabled(false) } }
    }
}
