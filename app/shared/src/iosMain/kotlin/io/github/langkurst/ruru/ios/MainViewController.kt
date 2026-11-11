package io.github.langkurst.ruru.ios

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.ui.RuruScreen
import io.github.langkurst.ruru.ui.RuruTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIViewController

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private val model by lazy { AppModel(IosHeadsetPlatform(), scope).also { it.start() } }

/** Called from the Swift app (iosApp/Ruru/RuruApp.swift). */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    RuruTheme(reduceMotion = UIAccessibilityIsReduceMotionEnabled()) {
        RuruScreen(model, RURU_VERSION, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
    }
}
