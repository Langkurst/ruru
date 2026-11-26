package io.github.langkurst.ruru.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.DemoHeadsets
import io.github.langkurst.ruru.app.PairingRequests
import androidx.compose.runtime.CompositionLocalProvider
import io.github.langkurst.ruru.ui.pages.LocalLabAutoStart
import kotlinx.coroutines.launch
import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.desktop.chrome.ChromeKind
import io.github.langkurst.ruru.desktop.chrome.RuruWindowFrame
import io.github.langkurst.ruru.desktop.chrome.WindowChromeState
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.PhoneTab
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.app.UnsupportedPlatform
import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.Updates
import io.github.langkurst.ruru.app.eq.EqBackendStatus
import io.github.langkurst.ruru.app.eq.EqStatuses
import io.github.langkurst.ruru.app.eq.FakeEqBackend
import io.github.langkurst.ruru.app.eq.ProEqModel
import io.github.langkurst.ruru.app.night.NightRunner
import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.dsp.BuiltInPresets
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.sleep.DayMode
import io.github.langkurst.ruru.sleep.NightSchedule
import io.github.langkurst.ruru.sleep.SleepStorage
import kotlinx.datetime.LocalTime
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.RuruScreen
import io.github.langkurst.ruru.ui.pages.PageAvailability
import io.github.langkurst.ruru.ui.pages.SystemKind
import io.github.langkurst.ruru.ui.res.*
import io.github.langkurst.ruru.ui.RuruTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Renders the real app off-screen to a PNG, for checking the design and for README screenshots.
 * Usage: Snapshot <out.png> [--demo[=<maker>]] [--light] [--width=<dp>] [--height=<dp>] [--scale=<n>] [--page=sound|equaliser|night|devices|settings|tools|about] [--tab=headphones|sound|sleep|more] [--system=windows|linux|mac|android|iphone] [--all-pages] [--frame] [--mode=<index>] [--eq=notinstalled|off|on] [--eq-view=graphic|parametric] [--night=on|off|aware] [--none] [--lab] [--pairing[=plain]] [--update=checking|downloading|ready|installing|uptodate|failed]. Without --demo it connects to the
 * headphones like the app does and waits for them.
 */
fun main(args: Array<String>) {
    val out = File(args.first())
    // --demo shows Bose's simulated headphones; --demo=<maker> (sony, sennheiser, soundcore, jbl, skullcandy, airpods) another maker's.
    val demoArg = args.firstOrNull { it == "--demo" || it.startsWith("--demo=") }
    val demo = demoArg != null
    val demoFamily = DemoHeadsets.familyOf(demoArg?.substringAfter('=', ""))
    val dark = "--light" !in args
    val heightDp = args.firstOrNull { it.startsWith("--height=") }?.substringAfter('=')?.toInt() ?: 1600
    // 400 is the single column; from 880 up the sidebar layout is drawn. --frame adds Ruru's own title bar above it,
    // which is how the window looks on Windows.
    val widthDp = args.firstOrNull { it.startsWith("--width=") }?.substringAfter('=')?.toInt() ?: 400
    val scale = args.firstOrNull { it.startsWith("--scale=") }?.substringAfter('=')?.toFloat() ?: 2f
    val frame = "--frame" in args
    val page = when (args.firstOrNull { it.startsWith("--page=") }?.substringAfter('=')) {
        "equaliser" -> DeskPage.Equaliser
        "night" -> DeskPage.Night
        "devices" -> DeskPage.Devices
        "settings" -> DeskPage.Settings
        "tools" -> DeskPage.Tools
        "about" -> DeskPage.About
        else -> DeskPage.Sound
    }
    val tab = when (args.firstOrNull { it.startsWith("--tab=") }?.substringAfter('=')) {
        "sound" -> PhoneTab.Sound
        "sleep" -> PhoneTab.Sleep
        "more" -> PhoneTab.More
        else -> PhoneTab.Headphones
    }
    // --all-pages shows the pages that are not built yet too (as placeholders), as on the system named by --system.
    val system = args.firstOrNull { it.startsWith("--system=") }?.substringAfter('=')?.let { name -> SystemKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    val pages = when {
        "--all-pages" in args -> PageAvailability(DeskPage.entries.toSet(), system ?: SystemKind.Windows, hasNightRunner = true)
        system != null -> PageAvailability(system = system)
        else -> null
    }
    val update = when (args.firstOrNull { it.startsWith("--update=") }?.substringAfter('=')) {
        "checking" -> UpdateState.Checking
        "downloading" -> UpdateState.Downloading("0.2.1", 0.42f)
        "ready" -> UpdateState.Ready("0.2.1")
        "installing" -> UpdateState.Installing("0.2.1")
        "uptodate" -> UpdateState.UpToDate
        "failed" -> UpdateState.Failed(Words("update_failed_check"))
        else -> null
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // --none shows the screen for a system with no headphones to control, without touching real Bluetooth.
    val none = "--none" in args
    val model = AppModel(if (none) UnsupportedPlatform("this system") else WindowsHeadsetPlatform(), scope, startInDemo = demo, demoFamily = demoFamily)
    model.page.value = page
    model.tab.value = tab
    model.start()
    val connected = runBlocking {
        withTimeoutOrNull(if (none) 1_000 else 30_000) { model.controller.value.state.first { it.phase == Phase.Connected } }
    }
    println(if (connected != null) "connected" else "not connected, rendering the current state")
    // --mode=<n> picks a listening mode in demo mode first (3 is Commute, one of the editable ones).
    args.firstOrNull { it.startsWith("--mode=") }?.substringAfter('=')?.toIntOrNull()?.let { index ->
        model.controller.value.setMode(index)
        runBlocking { withTimeoutOrNull(5_000) { model.controller.value.state.first { it.headset.currentModeIndex == index } } }
    }
    // The switches a real Windows window offers, so the wide layout's "This computer" panel is drawn as people see it.
    val extras = if (widthDp >= 880) listOf(
        PlatformSwitch(Res.string.desktop_start_with_windows, Res.string.desktop_start_with_windows_hint, { false }, {}, id = PlatformSwitch.START_AT_SIGN_IN),
        PlatformSwitch(Res.string.desktop_keep_in_tray, Res.string.desktop_keep_in_tray_hint, { true }, {}),
    ) else emptyList()
    val chrome = WindowChromeState(if (frame) ChromeKind.Windows else ChromeKind.None) {}
    // The Equaliser page runs on a stand-in backend and in-memory settings, so a snapshot never touches the real system.
    // --eq=notinstalled|off|on (default on) picks its state, --eq-view=graphic|parametric (default graphic) its editor.
    val eqState = args.firstOrNull { it.startsWith("--eq=") }?.substringAfter('=') ?: "on"
    val fake = FakeEqBackend(initial = if (eqState == "notinstalled") EqStatuses.apoNotInstalled() else EqBackendStatus.Ready)
    val proEq = ProEqModel(MemorySettingsStore(), scope, real = fake, demoBackend = { fake }, saveMs = 0, debounceMs = 0)
    if (demo) proEq.setDemo(true)
    if (args.firstOrNull { it.startsWith("--eq-view=") }?.substringAfter('=') == "parametric") {
        proEq.applyPreset(BuiltInPresets.QC_ULTRA_ID)
    } else {
        listOf(4.0, 3.5, 2.0, 0.0, -1.5, 0.0, 1.0, 2.5, 3.0, 1.5).forEachIndexed { i, g -> proEq.setGraphicGain(i, g) }
    }
    if (eqState == "on") proEq.setEnabled(true)
    // The Night page runs on in-memory settings and is not started, so a snapshot never changes the headset or the real schedule.
    // --night=on (default) shows a typical schedule, off shows it switched off, aware a schedule that goes to Aware and back to Quiet.
    val nightState = args.firstOrNull { it.startsWith("--night=") }?.substringAfter('=') ?: "on"
    val nightSettings = MemorySettingsStore().also {
        val schedule = NightSchedule(
            on = nightState != "off", bedtime = LocalTime(22, 30), wake = LocalTime(7, 0),
            nightMode = ModeRef(if (nightState == "aware") ModeMeaning.AWARE else ModeMeaning.QUIET),
            dayMode = if (nightState == "aware") DayMode.Mode(ModeRef(ModeMeaning.QUIET)) else DayMode.WhatItWas,
        )
        it.putString(SleepStorage.NIGHT_SCHEDULE_KEY, SleepStorage.encodeSchedule(schedule))
    }
    val night = NightRunner(nightSettings, scope, model.nightHeadset())
    // --pairing[=plain] shows the question a Skullcandy Crusher asks when it wants to pair (with a code, or a plain yes or no).
    args.firstOrNull { it == "--pairing" || it.startsWith("--pairing=") }?.let { arg ->
        val plain = arg.substringAfter('=', "") == "plain"
        scope.launch { PairingRequests.shared.ask(if (plain) null else 40213, if (plain) 0 else 6) }
        Thread.sleep(200)
    }
    val scene = ImageComposeScene(width = (widthDp * scale).toInt(), height = (heightDp * scale).toInt(), density = Density(scale)) {
        RuruTheme(dark = dark) {
            RuruWindowFrame(chrome) {
                CompositionLocalProvider(LocalLabAutoStart provides ("--lab" in args)) {
                    RuruScreen(model, RURU_VERSION, extras = extras, updates = update?.let(::StillUpdates), pages = pages, proEq = proEq, night = night)
                }
            }
        }
    }
    var image = scene.render(0)
    for (i in 1..12) {
        Thread.sleep(80)
        image = scene.render(i * 80_000_000L)
    }
    out.parentFile?.mkdirs()
    out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("wrote ${out.path}")
    scene.close()
    model.stop()
    exitProcess(0)
}

/** An updater frozen in one state, to draw that state of the card. */
private class StillUpdates(state: UpdateState) : Updates {
    override val state: StateFlow<UpdateState> = MutableStateFlow(state)
    override fun check() = Unit
    override fun installAndRestart() = Unit
    override fun dismiss() = Unit
}
