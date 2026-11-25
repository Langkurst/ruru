package io.github.langkurst.ruru.platform.windows

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * A Windows 10/11 window whose title bar is drawn by the app but which still behaves like a normal
 * window: drag, double-click to maximise, snap layouts when hovering the maximise button, the system
 * shadow and rounded corners, edge resizing, and a maximised size that stays inside the screen.
 *
 * The AWT window is made undecorated by the caller. [attach] then gives its native window the styles
 * of a normal overlapped window and subclasses its window procedure (and the procedure of the child
 * window that holds the drawn content) through JNA:
 *  - WM_NCCALCSIZE makes the client area cover the whole window, so no system caption is drawn;
 *    maximised, it is clipped to the monitor's work area.
 *  - WM_NCHITTEST answers "caption" over the title bar's drag area, "maximise button" over the
 *    maximise button (so Windows 11 shows its snap layouts) and the resize codes along the edges.
 *    The content child window answers "transparent" for those spots, so the question reaches the
 *    top-level window.
 *
 * Everything is best effort: [attach] returns null when anything fails, and the caller then uses
 * the normal system title bar. 64-bit Windows 10 or later only.
 */
class WinWindowChrome private constructor(
    private val hwnd: Long,
    private val geometry: Geometry,
    private val listener: Listener,
) {
    /** Sizes of the drawn title bar, in device-independent pixels (96 per inch). */
    class Geometry(val titleBarDp: Int = 40, val buttonDp: Int = 46, val buttonCount: Int = 3, val resizeDp: Int = 6)

    interface Listener {
        /** The pointer is over (or left) the maximise button, which the system hit-tests. */
        fun maxButtonHover(hover: Boolean)

        /** The maximise button is held down (or let go). */
        fun maxButtonPressed(pressed: Boolean)

        /**
         * The window changed size or state. [insetRightPx] and [insetBottomPx] say how much of the
         * window's outer size lies off screen, so the drawn content can leave that out.
         */
        fun frameChanged(maximised: Boolean, insetRightPx: Int, insetBottomPx: Int)
    }

    private val api = Api.user32
    private var previous = 0L
    private val childPrevious = ConcurrentHashMap<Long, Long>()
    private var maxHover = false
    private var maxDown = false

    // JNA callbacks must stay referenced for as long as the native window can call them.
    private val topProc = object : WndProc {
        override fun callback(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Long = try {
            onTop(hwnd, msg, wParam, lParam)
        } catch (t: Throwable) {
            api.CallWindowProcW(previous, hwnd, msg, wParam, lParam)
        }
    }
    private val childProc = object : WndProc {
        override fun callback(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Long = try {
            onChild(hwnd, msg, wParam, lParam)
        } catch (t: Throwable) {
            api.CallWindowProcW(childPrevious[hwnd] ?: 0L, hwnd, msg, wParam, lParam)
        }
    }
    private val enumProc = object : EnumProc {
        override fun callback(hwnd: Long, lParam: Long): Int {
            val child = hwnd
            if (!childPrevious.containsKey(child)) {
                val old = api.SetWindowLongPtrW(child, GWLP_WNDPROC, childProc)
                if (old != 0L) childPrevious[child] = old
            }
            return 1
        }
    }

    private fun install() {
        // A normal overlapped window's styles: the frame is what gives resize, snap and the shadow.
        val style = api.GetWindowLongPtrW(hwnd, GWL_STYLE)
        val wanted = (style and WS_POPUP.inv()) or WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX
        previous = api.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, topProc)
        check(previous != 0L) { "could not subclass the window" }
        api.EnumChildWindows(hwnd, enumProc, 0L)
        if (wanted != style) api.SetWindowLongPtrW(hwnd, GWL_STYLE, wanted)
        setInt(DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
        api.SetWindowPos(hwnd, 0L, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        reportFrame()
    }

    /** The system's title bar, borders and menus follow the app's light or dark theme. */
    fun setDark(dark: Boolean) {
        setInt(DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
    }

    /** The 1px line Windows 11 draws around the window. */
    fun setBorderColor(red: Int, green: Int, blue: Int) {
        setInt(DWMWA_BORDER_COLOR, (blue shl 16) or (green shl 8) or red)
    }

    fun minimise() { api.PostMessageW(hwnd, WM_SYSCOMMAND, SC_MINIMIZE, 0L) }

    fun toggleMaximise() {
        api.PostMessageW(hwnd, WM_SYSCOMMAND, if (api.IsZoomed(hwnd)) SC_RESTORE else SC_MAXIMIZE, 0L)
    }

    val maximised: Boolean get() = api.IsZoomed(hwnd)

    private fun setInt(attribute: Int, value: Int) {
        val dwm = Api.dwm ?: return
        dwm.DwmSetWindowAttribute(hwnd, attribute, Memory(4).apply { setInt(0, value) }, 4)
    }

    // ---- window procedures --------------------------------------------------------------------

    private fun onTop(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Long {
        when (msg) {
            WM_NCCALCSIZE -> {
                if (wParam != 0L && api.IsZoomed(hwnd)) clipToWorkArea(Pointer(lParam))
                return 0L // the client area is the whole window: no system caption
            }
            WM_NCHITTEST -> {
                val code = hitTest(hwnd, signedLow(lParam), signedHigh(lParam))
                if (code != HTCLIENT) return code.toLong()
            }
            WM_NCMOUSEMOVE -> {
                // The system tracks this pointer itself (that is what opens the snap layouts), so no
                // TrackMouseEvent here: a second request would replace its own.
                setMaxHover(wParam == HTMAXBUTTON.toLong())
            }
            WM_NCMOUSELEAVE -> {
                setMaxHover(false)
                setMaxDown(false)
            }
            WM_NCLBUTTONDOWN -> if (wParam == HTMAXBUTTON.toLong()) {
                setMaxDown(true)
                return 0L
            }
            WM_NCLBUTTONUP -> if (wParam == HTMAXBUTTON.toLong()) {
                val wasDown = maxDown
                setMaxDown(false)
                if (wasDown) toggleMaximise()
                return 0L
            }
            WM_NCLBUTTONDBLCLK -> if (wParam == HTMAXBUTTON.toLong()) return 0L
        }
        val result = api.CallWindowProcW(previous, hwnd, msg, wParam, lParam)
        if (msg == WM_SIZE || msg == WM_WINDOWPOSCHANGED) reportFrame()
        return result
    }

    private fun onChild(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Long {
        // The pointer is over the drawn content, so it has left the maximise button.
        if (msg == WM_MOUSEMOVE && (maxHover || maxDown)) {
            setMaxHover(false)
            setMaxDown(false)
        }
        if (msg == WM_NCHITTEST) {
            // Over the drag area and the edges the child steps aside, and the top-level window decides.
            if (hitTest(this.hwnd, signedLow(lParam), signedHigh(lParam)) != HTCLIENT) return HTTRANSPARENT.toLong()
        }
        return api.CallWindowProcW(childPrevious[hwnd] ?: 0L, hwnd, msg, wParam, lParam)
    }

    private fun setMaxHover(hover: Boolean) {
        if (maxHover != hover) {
            maxHover = hover
            listener.maxButtonHover(hover)
        }
    }

    private fun setMaxDown(down: Boolean) {
        if (maxDown != down) {
            maxDown = down
            listener.maxButtonPressed(down)
        }
    }

    private fun reportFrame() {
        val outer = Memory(16)
        val inner = Memory(16)
        if (!api.GetWindowRect(hwnd, outer) || !api.GetClientRect(hwnd, inner)) return
        val outerW = outer.getInt(8) - outer.getInt(0)
        val outerH = outer.getInt(12) - outer.getInt(4)
        val innerW = inner.getInt(8)
        val innerH = inner.getInt(12)
        listener.frameChanged(api.IsZoomed(hwnd), (outerW - innerW).coerceAtLeast(0), (outerH - innerH).coerceAtLeast(0))
    }

    // ---- geometry -----------------------------------------------------------------------------

    private fun scale(hwnd: Long): Double = (api.GetDpiForWindow(hwnd).takeIf { it > 0 } ?: 96) / 96.0

    /** Maximised, the window is as big as the monitor's work area (the screen minus the taskbar). */
    private fun clipToWorkArea(rect: Pointer) {
        val monitor = api.MonitorFromRect(rect, MONITOR_DEFAULTTONEAREST)
        val info = Memory(40).apply { clear(); setInt(0, 40) }
        if (monitor == 0L || !api.GetMonitorInfoW(monitor, info)) return
        var left = info.getInt(20)
        var top = info.getInt(24)
        var right = info.getInt(28)
        var bottom = info.getInt(32)
        // A taskbar that hides itself only comes back when something does not cover the screen edge.
        val fullLeft = info.getInt(4)
        val fullTop = info.getInt(8)
        val fullRight = info.getInt(12)
        val fullBottom = info.getInt(16)
        if (autoHideEdge(ABE_LEFT, info)) left = fullLeft + 1
        if (autoHideEdge(ABE_TOP, info)) top = fullTop + 1
        if (autoHideEdge(ABE_RIGHT, info)) right = fullRight - 1
        if (autoHideEdge(ABE_BOTTOM, info)) bottom = fullBottom - 1
        rect.setInt(0, left)
        rect.setInt(4, top)
        rect.setInt(8, right)
        rect.setInt(12, bottom)
    }

    private fun autoHideEdge(edge: Int, monitorInfo: Pointer): Boolean {
        val data = Memory(48).apply {
            clear()
            setInt(0, 48)
            setInt(20, edge) // uEdge
            for (i in 0 until 4) setInt(24L + i * 4, monitorInfo.getInt(4L + i * 4)) // rc: the monitor
        }
        return Api.shell?.SHAppBarMessage(ABM_GETAUTOHIDEBAREX, data) != 0L
    }

    /** What the system should treat the point (screen pixels) as: the drag area, an edge, or content. */
    private fun hitTest(hwnd: Long, screenX: Int, screenY: Int): Int {
        val outer = Memory(16)
        val inner = Memory(16)
        val origin = Memory(8).apply { setInt(0, 0); setInt(4, 0) }
        if (!api.GetWindowRect(hwnd, outer) || !api.GetClientRect(hwnd, inner) || !api.ClientToScreen(hwnd, origin)) return HTCLIENT
        val k = scale(hwnd)
        val zoomed = api.IsZoomed(hwnd)
        if (!zoomed) {
            val b = (geometry.resizeDp * k).roundToInt().coerceAtLeast(2)
            val x = screenX - outer.getInt(0)
            val y = screenY - outer.getInt(4)
            val w = outer.getInt(8) - outer.getInt(0)
            val h = outer.getInt(12) - outer.getInt(4)
            val onLeft = x < b
            val onRight = x >= w - b
            val onTop = y < b
            val onBottom = y >= h - b
            when {
                onTop && onLeft -> return HTTOPLEFT
                onTop && onRight -> return HTTOPRIGHT
                onBottom && onLeft -> return HTBOTTOMLEFT
                onBottom && onRight -> return HTBOTTOMRIGHT
                onLeft -> return HTLEFT
                onRight -> return HTRIGHT
                onTop -> return HTTOP
                onBottom -> return HTBOTTOM
            }
        }
        val x = screenX - origin.getInt(0)
        val y = screenY - origin.getInt(4)
        val width = inner.getInt(8)
        if (x < 0 || x >= width || y < 0 || y >= (geometry.titleBarDp * k).roundToInt()) return HTCLIENT
        val button = (geometry.buttonDp * k).roundToInt()
        val fromRight = width - x
        return when {
            fromRight <= button -> HTCLIENT // close: the drawn button handles it
            fromRight <= button * 2 -> HTMAXBUTTON // maximise or restore: the system shows snap layouts
            fromRight <= button * geometry.buttonCount -> HTCLIENT // minimise
            else -> HTCAPTION
        }
    }

    private fun signedLow(l: Long) = (l and 0xFFFF).toShort().toInt()
    private fun signedHigh(l: Long) = ((l shr 16) and 0xFFFF).toShort().toInt()

    // ---- native bindings ----------------------------------------------------------------------

    internal interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Long
    }

    internal interface EnumProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Long, lParam: Long): Int
    }

    @Suppress("FunctionName")
    internal interface User32 : Library {
        fun SetWindowLongPtrW(hwnd: Long, index: Int, value: Callback): Long
        fun SetWindowLongPtrW(hwnd: Long, index: Int, value: Long): Long
        fun GetWindowLongPtrW(hwnd: Long, index: Int): Long
        fun CallWindowProcW(previous: Long, hwnd: Long, msg: Int, wParam: Long, lParam: Long): Long
        fun PostMessageW(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Boolean
        fun SetWindowPos(hwnd: Long, after: Long, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hwnd: Long, rect: Pointer): Boolean
        fun GetClientRect(hwnd: Long, rect: Pointer): Boolean
        fun ClientToScreen(hwnd: Long, point: Pointer): Boolean
        fun IsZoomed(hwnd: Long): Boolean
        fun GetDpiForWindow(hwnd: Long): Int
        fun EnumChildWindows(hwnd: Long, proc: Callback, lParam: Long): Boolean
        fun MonitorFromRect(rect: Pointer, flags: Int): Long
        fun GetMonitorInfoW(monitor: Long, info: Pointer): Boolean
    }

    @Suppress("FunctionName")
    internal interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: Long, attribute: Int, value: Pointer, size: Int): Int
    }

    @Suppress("FunctionName")
    internal interface Shell32 : Library {
        fun SHAppBarMessage(message: Int, data: Pointer): Long
    }

    private object Api {
        val user32: User32 = Native.load("user32", User32::class.java)
        val dwm: Dwmapi? = runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
        val shell: Shell32? = runCatching { Native.load("shell32", Shell32::class.java) }.getOrNull()
    }

    companion object {
        /** Windows 10 or later, 64-bit. Older systems and 32-bit Java keep the system title bar. */
        val supported: Boolean
            get() = System.getProperty("os.name").orEmpty().startsWith("Windows") &&
                System.getProperty("os.arch").orEmpty().let { it == "amd64" || it == "aarch64" } &&
                (System.getProperty("os.version").orEmpty().substringBefore('.').toIntOrNull() ?: 0) >= 10

        /**
         * Turns the (undecorated, already showing) AWT [window] into a window with a drawn title bar.
         * Returns null, having changed nothing that matters, if any native call fails.
         */
        fun attach(window: Window, geometry: Geometry, listener: Listener): WinWindowChrome? {
            if (!supported) return null
            return runCatching {
                val handle = Native.getWindowID(window)
                check(handle != 0L) { "no native window yet" }
                WinWindowChrome(handle, geometry, listener).also { it.install() }
            }.getOrNull()
        }

        private const val GWL_STYLE = -16
        private const val GWLP_WNDPROC = -4
        private const val WS_POPUP = 0x80000000L
        private const val WS_CAPTION = 0x00C00000L
        private const val WS_SYSMENU = 0x00080000L
        private const val WS_THICKFRAME = 0x00040000L
        private const val WS_MINIMIZEBOX = 0x00020000L
        private const val WS_MAXIMIZEBOX = 0x00010000L

        private const val WM_SIZE = 0x0005
        private const val WM_MOUSEMOVE = 0x0200
        private const val WM_NCCALCSIZE = 0x0083
        private const val WM_NCHITTEST = 0x0084
        private const val WM_NCMOUSEMOVE = 0x00A0
        private const val WM_NCLBUTTONDOWN = 0x00A1
        private const val WM_NCLBUTTONUP = 0x00A2
        private const val WM_NCLBUTTONDBLCLK = 0x00A3
        private const val WM_WINDOWPOSCHANGED = 0x0047
        private const val WM_NCMOUSELEAVE = 0x02A2
        private const val WM_SYSCOMMAND = 0x0112
        private const val SC_MINIMIZE = 0xF020L
        private const val SC_MAXIMIZE = 0xF030L
        private const val SC_RESTORE = 0xF120L

        private const val HTCLIENT = 1
        private const val HTCAPTION = 2
        private const val HTMAXBUTTON = 9
        private const val HTLEFT = 10
        private const val HTRIGHT = 11
        private const val HTTOP = 12
        private const val HTTOPLEFT = 13
        private const val HTTOPRIGHT = 14
        private const val HTBOTTOM = 15
        private const val HTBOTTOMLEFT = 16
        private const val HTBOTTOMRIGHT = 17
        private const val HTTRANSPARENT = -1

        private const val SWP_NOSIZE = 0x0001
        private const val SWP_NOMOVE = 0x0002
        private const val SWP_NOZORDER = 0x0004
        private const val SWP_NOACTIVATE = 0x0010
        private const val SWP_FRAMECHANGED = 0x0020
        private const val MONITOR_DEFAULTTONEAREST = 2

        private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
        private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
        private const val DWMWA_BORDER_COLOR = 34
        private const val DWMWCP_ROUND = 2

        private const val ABM_GETAUTOHIDEBAREX = 0xB
        private const val ABE_LEFT = 0
        private const val ABE_TOP = 1
        private const val ABE_RIGHT = 2
        private const val ABE_BOTTOM = 3
    }
}
