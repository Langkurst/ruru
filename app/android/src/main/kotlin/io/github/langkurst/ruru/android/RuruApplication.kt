package io.github.langkurst.ruru.android

import android.app.Application
import io.github.langkurst.ruru.android.eq.AndroidEqBackend
import io.github.langkurst.ruru.android.eq.AndroidOutputRoute
import io.github.langkurst.ruru.android.eq.SessionRegistry
import io.github.langkurst.ruru.android.service.RuruService
import io.github.langkurst.ruru.android.service.ServiceNeeds
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.DemoHeadsets
import io.github.langkurst.ruru.app.PairingRequests
import io.github.langkurst.ruru.app.settings.SettingsBragiStorage
import io.github.langkurst.ruru.vendors.bragi.BragiSecurity
import io.github.langkurst.ruru.vendors.bragi.PairingPrompt
import io.github.langkurst.ruru.vendors.bragi.StoredBragiTrustStore
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
        // Skullcandy pairing: the keys live in the settings, and a Crusher's request to pair is asked on screen.
        BragiSecurity.trustStore = StoredBragiTrustStore(SettingsBragiStorage(createSettingsStore()))
        BragiSecurity.pairingPrompt = PairingPrompt { code, digits -> PairingRequests.shared.ask(code, digits) }
        // RuruService runs while any feature needs it (the equaliser now, the night schedule later) and not otherwise.
        scope.launch {
            serviceNeeds.needs.map { it.isNotEmpty() }.distinctUntilChanged().collect { wanted ->
                if (wanted) RuruService.start(this@RuruApplication) else RuruService.stop(this@RuruApplication)
            }
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val platform: AndroidHeadsetPlatform by lazy { AndroidHeadsetPlatform(this) }
    val model: AppModel by lazy { AppModel(platform, scope, demoFamily = DemoHeadsets.familyOf(createSettingsStore().getString(DemoHeadsets.SETTING_KEY))) }

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
