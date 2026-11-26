package io.github.langkurst.ruru.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.SUPPORT_URL
import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.Updates
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.ui.pages.Columns
import io.github.langkurst.ruru.ui.pages.DevicesPage
import io.github.langkurst.ruru.ui.pages.EqualiserPage
import io.github.langkurst.ruru.ui.pages.LocalAudioTools
import io.github.langkurst.ruru.ui.pages.NavIcon
import io.github.langkurst.ruru.ui.pages.NavIconView
import io.github.langkurst.ruru.ui.pages.NightPage
import io.github.langkurst.ruru.ui.pages.PageAvailability
import io.github.langkurst.ruru.ui.pages.PageFrame
import io.github.langkurst.ruru.ui.pages.SettingsPage
import io.github.langkurst.ruru.ui.pages.SleepPage
import io.github.langkurst.ruru.ui.pages.SoundPage
import io.github.langkurst.ruru.ui.pages.ToolsPage
import io.github.langkurst.ruru.ui.pages.column
import io.github.langkurst.ruru.ui.pages.hasDevices
import io.github.langkurst.ruru.ui.pages.hasTools
import io.github.langkurst.ruru.ui.pages.pageIcon
import io.github.langkurst.ruru.ui.pages.pageTitle
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Ctrl+1 to Ctrl+6 (Command on a Mac): the keys for the first six pages of the sidebar. */
private val pageKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six)

/** From this window width the app draws the sidebar layout; below it, the single column. */
internal val DeskMinWidth = 880.dp

private val SidebarWidth = 264.dp

/**
 * The computer layout (docs/DESIGN.md, "Desktop layout"): a sidebar with the headphones and the
 * pages, and the chosen page on a raised surface beside it. It reuses the same panels as the single
 * column; only the way they are arranged is different.
 */
@Composable
internal fun DeskLayout(
    model: AppModel,
    state: AppState,
    controller: HeadsetController,
    appVersion: String,
    extras: List<PlatformSwitch>,
    updates: Updates,
    onCopyReport: () -> Unit,
    pages: PageAvailability,
) {
    val requested by model.page.collectAsState()
    val h = state.headset
    val connected = state.phase == Phase.Connected
    // The pages in the sidebar. With no headphones there is nothing to tune, so only what belongs to the app,
    // and a page whose feature is not built yet is not listed (see PageAvailability).
    val navPages = pages.deskPages(
        sound = connected && hasSound(h),
        settings = (connected && (hasHeadphoneSettings(h) || hasShortcutChoices(h))) || extras.isNotEmpty(),
        devices = connected && hasDevices(h),
        tools = hasTools(LocalAudioTools.current, connected),
    )
    val shown = when {
        requested == DeskPage.About -> DeskPage.About
        requested in navPages -> requested
        connected -> navPages.firstOrNull() ?: DeskPage.About
        else -> DeskPage.Status
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            // Ctrl+1 to 6 (Command on a Mac) open the pages in the sidebar, top to bottom.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !(e.isCtrlPressed || e.isMetaPressed)) return@onPreviewKeyEvent false
                val index = pageKeys.indexOf(e.key)
                if (index < 0) return@onPreviewKeyEvent false
                val target = navPages.getOrNull(index) ?: return@onPreviewKeyEvent false
                model.page.value = target
                true
            },
    ) {
        Sidebar(model, state, navPages, shown, updates, appVersion)
        val c = Ruru.colors
        Box(Modifier.weight(1f).fillMaxHeight().deskSurface(c.bough, c.line)) {
            val reduce = LocalReduceMotion.current
            val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
            AnimatedContent(
                targetState = shown,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    // The old page fades out quickly, the new one starts a little later and rises into place.
                    if (reduce) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(350, delayMillis = 100, easing = Motion.Quint)) +
                            slideInVertically(tween(350, delayMillis = 100, easing = Motion.Quint)) { rise }) togetherWith
                            fadeOut(tween(200, easing = Motion.Quint))
                    }
                },
                label = "page",
            ) { page ->
                when (page) {
                    DeskPage.Sound -> SoundPage(state, controller)
                    DeskPage.Equaliser -> EqualiserPage(state, controller)
                    DeskPage.Night -> NightPage(state, controller, extras)
                    DeskPage.Devices -> DevicesPage(state, controller)
                    DeskPage.Settings -> SettingsPage(state, controller, extras)
                    DeskPage.Tools -> ToolsPage(state, controller)
                    // The phone's tabs: never chosen in the sidebar, but a remembered page may name one.
                    DeskPage.Sleep -> SleepPage(state, controller)
                    DeskPage.More -> SettingsPage(state, controller, extras)
                    DeskPage.About -> AboutPage(model, state, appVersion, updates, onCopyReport)
                    DeskPage.Status -> StatusPage(state, controller, model)
                }
            }
        }
    }
}

/** The content surface: bough, rounded at the top left, with a 1px line along the top and left edges only. */
private fun Modifier.deskSurface(fill: Color, line: Color): Modifier = this
    .clip(RoundedCornerShape(topStart = 11.dp))
    .background(fill)
    .drawBehind {
        val half = 0.5.dp.toPx()
        val radius = 11.dp.toPx() - half
        val edge = Path().apply {
            moveTo(half, size.height)
            lineTo(half, half + radius)
            arcTo(Rect(half, half, half + 2 * radius, half + 2 * radius), 180f, 90f, false)
            lineTo(size.width, half)
        }
        drawPath(edge, line, style = Stroke(1.dp.toPx()))
    }

// ---------------------------------------------------------------- sidebar

@Composable
private fun Sidebar(
    model: AppModel,
    state: AppState,
    navPages: List<DeskPage>,
    shown: DeskPage,
    updates: Updates,
    appVersion: String,
) {
    val c = Ruru.colors
    val t = Ruru.type
    val h = state.headset
    val connected = state.phase == Phase.Connected
    val browser = LocalUriHandler.current
    val update by updates.state.collectAsState()
    Column(Modifier.width(SidebarWidth).fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            OwlMark(52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val name = deviceName(state)
                Txt(name, t.device, c.ink, Modifier.semantics { contentDescription = name }, maxLines = 1)
                val line = statusLine(state, stringResource(Res.string.header_connected))
                val dot = when (state.phase) {
                    Phase.Connected -> c.calm
                    is Phase.Failed -> c.alarm
                    else -> c.ink4
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Txt(if (state.demo) stringResource(Res.string.header_with_demo, line) else line, t.small, c.ink3, Modifier.weight(1f, fill = false), maxLines = 1)
                    if (connected && showMicChip(h)) {
                        Spacer(Modifier.width(8.dp))
                        MicChip()
                    }
                }
            }
        }
        if (connected) h.batteryPercent?.let { SidebarBattery(it, h.isCharging == true, minutesLeft(h)) }
        val pagesLabel = stringResource(Res.string.nav_pages)
        Column(Modifier.selectableGroup().semantics { contentDescription = pagesLabel }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            navPages.forEach { page ->
                NavRow(pageTitle(page), pageIcon(page), selected = shown == page) { model.page.value = page }
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            update == UpdateState.Idle -> {
                NavRow(stringResource(Res.string.footer_check_updates), NavIcon.Refresh, selected = false, quiet = true) { updates.check() }
            }
            update is UpdateState.Ready -> ReadyCard(update as UpdateState.Ready, updates)
            else -> UpdateCard(update, updates, fill = c.bough)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.side_version, appVersion), t.small, c.ink4, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            LinkText(stringResource(Res.string.footer_about)) { model.page.value = DeskPage.About }
            Spacer(Modifier.width(10.dp))
            LinkText(stringResource(Res.string.footer_support)) { runCatching { browser.openUri(SUPPORT_URL) } }
        }
    }
}

@Composable
private fun SidebarBattery(percent: Int, charging: Boolean, minutesLeft: Int?) {
    val c = Ruru.colors
    val low = percent <= 15 && !charging
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(if (charging) Res.string.battery_percent_charging else Res.string.battery_percent, percent), Ruru.type.small, if (low) c.alarm else c.ink3, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.lineSoft)) {
                Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (low) c.alarm else c.ink2))
            }
        }
        if (minutesLeft != null) Txt(hoursLeftText(minutesLeft), Ruru.type.small, c.ink3, Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

/** The "ready" card: a downloaded, checked update waiting for the person to restart into it. */
@Composable
private fun ReadyCard(state: UpdateState.Ready, updates: Updates) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bough)
            .border(1.dp, c.line, shape)
            .padding(14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OwlMark(36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Txt(stringResource(Res.string.update_ready_title, state.version), Ruru.type.section, c.ink)
                Txt(stringResource(Res.string.update_ready_detail), Ruru.type.small, c.ink3)
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(Res.string.update_ready_note), Ruru.type.small, c.ink3)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(stringResource(Res.string.update_install), updates::installAndRestart, Modifier.fillMaxWidth().heightIn(min = 34.dp))
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            LinkText(stringResource(Res.string.update_later), updates::dismiss)
        }
    }
}

/** Small text that does something when pressed: ink-3, ink on hover, a ring on keyboard focus. */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    val c = Ruru.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colour by animateColorAsState(if (hovered) c.ink else c.ink3, hoverSpec())
    val shape = RoundedCornerShape(4.dp)
    Txt(
        text,
        Ruru.type.small,
        colour,
        Modifier
            .focusRing(interaction, shape)
            .clip(shape)
            .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        maxLines = 1,
    )
}

// ---------------------------------------------------------------- navigation rows and icons

/** One 36dp sidebar row: a line icon and a label. [quiet] rows are actions rather than pages. */
@Composable
private fun NavRow(label: String, icon: NavIcon, selected: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> c.bark
            hovered -> c.bark.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        hoverSpec(),
    )
    val ink by animateColorAsState(if (selected) c.ink else if (quiet && !hovered) c.ink3 else c.ink2, hoverSpec())
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .background(fill)
            .selectable(selected, role = if (quiet) Role.Button else Role.Tab, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIconView(icon, ink.copy(alpha = ink.alpha * 0.8f), 17.dp)
        Spacer(Modifier.width(11.dp))
        Txt(label, if (selected) Ruru.type.bodyMedium else Ruru.type.body, ink, maxLines = 1)
    }
}

// ---------------------------------------------------------------- pages

@Composable
private fun AboutPage(model: AppModel, state: AppState, appVersion: String, updates: Updates, onCopyReport: () -> Unit) {
    val c = Ruru.colors
    val controller by model.controller.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val update by updates.state.collectAsState()
    val busy = update is UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Ready || update is UpdateState.Installing
    val browser = LocalUriHandler.current
    PageFrame(stringResource(Res.string.page_about_title), stringResource(Res.string.page_about_lead), state, controller) {
        val headphones = state.phase == Phase.Connected && state.headset.info != null
        Columns(if (headphones) 2 else 1) {
            Panel(stringResource(Res.string.panel_app), column()) {
                Txt(stringResource(Res.string.footer_version, appVersion), Ruru.type.body, c.ink)
                Spacer(Modifier.height(2.dp))
                Txt(stringResource(Res.string.about_licence), Ruru.type.small, c.ink3)
                Spacer(Modifier.height(12.dp))
                ButtonRow { QuietButton(stringResource(Res.string.footer_check_updates), updates::check, enabled = !busy) }
                Spacer(Modifier.height(8.dp))
                DemoSwitch(state, model)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(Res.string.footer_report_hint), Ruru.type.small, c.ink3, Modifier.weight(1f).padding(end = 8.dp))
                    QuietButton(stringResource(if (copied) Res.string.footer_report_copied else Res.string.footer_report_copy), { onCopyReport(); copied = true })
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(Res.string.footer_support_hint), Ruru.type.small, c.ink3, Modifier.weight(1f).padding(end = 8.dp))
                    QuietButton(stringResource(Res.string.footer_support), { runCatching { browser.openUri(SUPPORT_URL) } })
                }
            }
            if (headphones) AboutPanel(state.headset, column())
        }
    }
}

/** No headphones to control (looking, not paired, Bluetooth off, an error): the owl and what to do, in the middle. */
@Composable
private fun StatusPage(state: AppState, controller: HeadsetController, model: AppModel) {
    val c = Ruru.colors
    val (title, body) = notConnectedWords(state)
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
        state.message?.let {
            Box(Modifier.fillMaxWidth()) { MessageBar(it.text(), controller::dismissMessage) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OwlMark(96.dp)
                Spacer(Modifier.height(20.dp))
                Txt(title, Ruru.type.page, c.ink, Modifier.semantics { heading() }, align = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Txt(body, Ruru.type.body, c.ink2, align = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                NotConnectedButtons(state, controller, model)
            }
        }
    }
}

// ---------------------------------------------------------------- equaliser curve

/**
 * The equaliser's response as a line: bass at the left, mid in the middle, treble at the right, each
 * at the height of its setting, joined by a smooth curve that leaves and arrives level. A hairline marks 0 dB.
 */
@Composable
internal fun EqCurve(eq: HeadphoneEq, range: IntRange, modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val hairline = c.line
    val ink = c.ink
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val centre = h / 2f
        val reach = max(max(abs(range.first), abs(range.last)), 1).toFloat()
        val perDb = (centre - 6.dp.toPx()) / reach
        val ys = floatArrayOf(centre - eq.bass * perDb, centre - eq.mid * perDb, centre - eq.treble * perDb)
        drawLine(hairline, Offset(0f, centre), Offset(w, centre), 1.dp.toPx())
        // Slopes (per unit of t, which runs 0..1 across the width): level at both ends, and at the
        // middle the harmonic mean of the two sides, or level where the curve turns back.
        val d0 = (ys[1] - ys[0]) / 0.5f
        val d1 = (ys[2] - ys[1]) / 0.5f
        val slopes = floatArrayOf(0f, if (d0 * d1 <= 0f) 0f else 2f * d0 * d1 / (d0 + d1), 0f)
        fun yAt(t: Float): Float {
            val seg = if (t < 0.5f) 0 else 1
            val u = (t - seg * 0.5f) / 0.5f
            val u2 = u * u
            val u3 = u2 * u
            return (2 * u3 - 3 * u2 + 1) * ys[seg] + (u3 - 2 * u2 + u) * 0.5f * slopes[seg] +
                (-2 * u3 + 3 * u2) * ys[seg + 1] + (u3 - u2) * 0.5f * slopes[seg + 1]
        }
        val steps = (w / 2f).toInt().coerceAtLeast(8)
        val curve = Path()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            if (i == 0) curve.moveTo(0f, yAt(0f)) else curve.lineTo(t * w, yAt(t))
        }
        drawPath(curve, ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
