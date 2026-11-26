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
import io.github.langkurst.ruru.app.tools.AndroidAudio
import io.github.langkurst.ruru.ui.RuruScreen
import io.github.langkurst.ruru.ui.RuruTheme

class MainActivity : ComponentActivity() {
    private val askNearby = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) app.model.controller.value.retryNow()
    }

    // Mic check asks for the microphone only when the person taps it.
    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AndroidAudio.micPermissionAnswered(granted)
    }

    private val app get() = application as RuruApplication

    /** The scan permission is asked for once per run when a scan was skipped for want of it. */
    private var askedForScan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidAudio.requestMicPermission = { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
        // Android 12 and newer: "Nearby devices" covers connecting and scanning, so one dialog asks for both.
        // The scan is only ever used to hear AirPods and Beats battery adverts and to find a headset's LE address.
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                askedForScan = true
                askNearby.launch(missing.toTypedArray())
            }
        }
        // Older Android needs the location permission for any scan (and says nothing when it is missing), so
        // it is asked for only when an advert listen was skipped: never at start-up, never used for location.
        app.platform.onScanAccessNeeded = { permission ->
            runOnUiThread {
                if (!askedForScan && !isFinishing) {
                    askedForScan = true
                    askNearby.launch(arrayOf(permission))
                }
            }
        }
        // "--ez demo true" on the launch intent starts in demo mode (CI uses it on an emulator).
        if (intent?.getBooleanExtra("demo", false) == true) app.model.setDemo(true)
        app.model.start()
        // Android's "Remove animations" (animator duration scale 0) turns Ruru's motion off too.
        val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            RuruTheme(reduceMotion = reduceMotion) {
                RuruScreen(app.model, RURU_VERSION, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            }
        }
    }

    // Adverts are listened to only while Ruru is on screen.
    override fun onStart() {
        super.onStart()
        app.platform.inFront = true
    }

    override fun onStop() {
        app.platform.inFront = false
        super.onStop()
    }
}
