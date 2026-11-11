package io.github.langkurst.ruru.android

import android.app.Application
import io.github.langkurst.ruru.app.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the one connection for the whole app, so turning the screen doesn't reconnect. */
class RuruApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model: AppModel by lazy { AppModel(AndroidHeadsetPlatform(this), scope) }
}
