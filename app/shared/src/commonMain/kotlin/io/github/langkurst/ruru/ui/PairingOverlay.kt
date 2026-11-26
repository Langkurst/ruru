package io.github.langkurst.ruru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.langkurst.ruru.app.PairingRequest
import io.github.langkurst.ruru.app.PairingRequests
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The question a Skullcandy Crusher puts to the person when it asks to pair with Ruru: the code both sides
 * worked out (or a plain yes or no), and Pair or Not now. Nothing pairs until Pair is pressed. Drawn over the
 * whole window, so it cannot be missed and the page behind it is out of reach until it is answered.
 */
@Composable
internal fun PairingOverlay(requests: PairingRequests, modifier: Modifier = Modifier) {
    val request by requests.pending.collectAsState()
    request?.let { PairingCard(it, { pair -> requests.answer(it, pair) }, modifier) }
}

@Composable
internal fun PairingCard(request: PairingRequest, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(14.dp)
    // Taps on the dimmed window go nowhere.
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 380.dp).fillMaxWidth()
                .background(c.bough, shape).border(1.dp, c.line, shape).padding(20.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Txt(stringResource(Res.string.pairing_title), Ruru.type.title, c.ink, Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            val code = request.passcode
            Txt(stringResource(if (code != null) Res.string.pairing_question_code else Res.string.pairing_question_plain), Ruru.type.body, c.ink2)
            if (code != null) {
                Spacer(Modifier.height(14.dp))
                Txt(passcodeText(code, request.digits), Ruru.type.page.copy(letterSpacing = 6.sp), c.ink, Modifier.fillMaxWidth(), align = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(stringResource(Res.string.pairing_not_now), { onAnswer(false) })
                PrimaryButton(stringResource(Res.string.pairing_pair), { onAnswer(true) })
            }
        }
    }
}

/** The passcode as the digits the headphones announce: padded with zeros to [digits] places. */
internal fun passcodeText(code: Int, digits: Int): String = code.toString().padStart(digits, '0')
