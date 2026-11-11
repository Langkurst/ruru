package io.github.langkurst.ruru.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.ui.RuruScreen
import io.github.langkurst.ruru.ui.RuruTheme

class MainActivity : ComponentActivity() {
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.model.controller.value.retryNow()
    }

    private val app get() = application as RuruApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        app.model.start()
        // Android's "Remove animations" (animator duration scale 0) turns Ruru's motion off too.
        val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            RuruTheme(reduceMotion = reduceMotion) {
                RuruScreen(app.model, RURU_VERSION, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            }
        }
    }
}
