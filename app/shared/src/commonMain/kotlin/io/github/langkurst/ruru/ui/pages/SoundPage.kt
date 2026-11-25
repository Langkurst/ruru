package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.ui.EqPanel
import io.github.langkurst.ruru.ui.ModesPanel
import io.github.langkurst.ruru.ui.NoisePanel
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** Sound: the listening modes, the noise settings and the headphones' own equaliser. */
@Composable
internal fun SoundPage(state: AppState, controller: HeadsetController) {
    val h = state.headset
    val caps = h.capabilities
    val modes = caps.listeningModes && h.modes.isNotEmpty()
    val noise = h.noise?.takeIf { caps.noiseCancelling }
    PageFrame(stringResource(Res.string.page_sound), stringResource(Res.string.page_sound_lead), state, controller) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Columns(listOf(modes, noise != null).count { it }) {
                if (modes) ModesPanel(h, controller, column())
                if (noise != null) NoisePanel(h, noise, controller, column())
            }
            h.eq?.let { EqPanel(it, h.eqRange ?: -10..10, controller) }
        }
    }
}
