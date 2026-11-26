package io.github.langkurst.ruru.android.sleep

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.android.RuruApplication
import io.github.langkurst.ruru.app.night.formatClockTime
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.ui.PrimaryButton
import io.github.langkurst.ruru.ui.QuietButton
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.RuruTheme
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.sleep_ringing_snooze
import io.github.langkurst.ruru.ui.res.sleep_ringing_title
import io.github.langkurst.ruru.ui.res.sleep_stop
import org.jetbrains.compose.resources.stringResource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The alarm screen: shows over a locked phone and turns the screen on, with one big Stop and a Snooze. It closes
 * itself when the alarm is stopped, snoozed or has run out (the machine in [SleepRuntime] is the truth).
 */
class AlarmActivity : ComponentActivity() {
    private val app get() = application as RuruApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = app.sleepModel.engine
        setContent {
            RuruTheme {
                val state by engine.session.collectAsState()
                val ringing = state as? SleepSessionState.Ringing
                LaunchedEffect(ringing == null) { if (ringing == null) finish() }
                val c = Ruru.colors
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
                    Txt(formatClockTime(now.hour, now.minute), Ruru.type.page, c.ink)
                    Txt(ringing?.label ?: stringResource(Res.string.sleep_ringing_title), Ruru.type.body, c.ink3)
                    Spacer(Modifier.height(40.dp))
                    PrimaryButton(stringResource(Res.string.sleep_stop), engine::stopAlarm, Modifier.fillMaxWidth().height(64.dp))
                    val minutes = ringing?.snoozeMinutes
                    if (ringing != null && ringing.canSnooze && minutes != null) {
                        Spacer(Modifier.height(16.dp))
                        QuietButton(stringResource(Res.string.sleep_ringing_snooze, minutes), engine::snoozeAlarm, Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }
        }
    }
}
