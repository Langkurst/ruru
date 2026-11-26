# DESIGN.md: Ruru

Feel: a quiet, exact control panel for headphones. It reads like a system utility (a tray flyout,
a settings pane), not a landing page. Dark by default, light follows the system.

Main reference: Raycast (`sites/raycast.com.md` in the design library). Compact dark panels, an odd
11px radius used everywhere, ease-out-quint motion, named status colours.
Borrowed detail: Apple's alpha text ramp (secondary text is the ink colour at lower alpha, not a
separate grey).

## Type

- Face: **Mona Sans** (GitHub, SIL OFL 1.1), static Regular 400, Medium 500, SemiBold 600.
  Why: legible at the small sizes a tray flyout needs, clear figures for battery and levels, and
  not one of the faces that now read as AI defaults.
- Sizes (desktop and phone alike, sp): device name 18 / 600; section title 13 / 600 in ink-2,
  sentence case; body 14 / 400, line height 20, tracking +0.2sp (0.014em); small 12 / 400 in ink-3.
- Figures use Mona Sans' normal digits. Its tabular set looks like a code font; changing values
  (levels, EQ) sit in fixed-width, end-aligned slots instead so nothing jumps.
- No headline over 20sp anywhere. No mono, no uppercase labels, no italics.

## Colour

Named after the ruru (morepork): its night bush, its bark-brown feathers and its yellow eyes.

| Name | Dark | Light | Use |
|---|---|---|---|
| night (page) | `#0B0C0A` | `#F4F5F3` | window background |
| bough (panel) | `#131512` | `#FFFFFF` | section panels |
| bark (raised) | `#1C1E1A` | `#EAECE8` | selected segment, pressed row |
| ink | `#F2F3EE` | `#1A1C17` | main text; never `#000` or `#FFF` |
| ink-2 / ink-3 / ink-4 | ink at 72% / 52% / 32% | ink at 72% / 56% / 40% | secondary text, hints, inactive tracks |
| line | white 8% | black 8% | 1px panel borders |
| line-soft | white 5% | black 5% | dividers inside a panel |
| eye (accent) | `#E8C547` | `#E2B91F` with ink text | the active listening mode only, and the owl's eyes |
| alarm | `#FF6161` | `#C23B3B` | battery at 15% or less, errors |
| calm | `#59D499` | `#1E8A5A` | the connected dot |

The accent appears in at most two places on a screen. Switches and sliders are ink, not accent.

## Shape and surfaces

- Radius: **11** for panels, **7** for rows, buttons and segments, full pill for switch tracks only.
- Borders: 1px at the line alpha. No shadows anywhere. No glass, no blur, no gradients.

## Motion

- Signature curve: ease-out-quint `cubic-bezier(.23, 1, .32, 1)`.
- 300ms for things that move: the selected-mode marker sliding to the new row, a segment sliding,
  a list opening. 160ms for hover and press tints.
- Nothing animates unless the user did something. Battery and pushed changes from the headset
  update in place without animation.
- Reduced motion: Windows "Show animations" off, macOS "Reduce motion", or Android animator scale
  0 sets every duration to 0.

## Layout

- Under 880dp (phones, narrow windows): one column per tab, 360 to 520dp wide, panels stacked with 12dp
  gaps, 16dp padding, rows 40dp minimum (48dp on touch), and a bottom bar (see "Pages"). Headphones tab:
  headset header, listening mode, noise, equaliser. More tab: shortcut button, settings, about the
  headphones, then the switches for this system, version, updates, report and support. Each tab opens at its top.
- From a window about 1280dp wide the whole interface (title bar, sidebar, pages) is drawn larger by
  clamp(1 + (width - 1280) / 1280 * 0.35, 1, 1.35), so a big screen does not look tiny. The 880dp switch below
  is checked before this scale, which is 1 under 1280dp.
- From 880dp (a computer window, the default is 1100 x 720): a 264dp sidebar on night, and the page on
  bough beside it (1px line on its top and left edges, 11dp top-left radius, padding 28 / 32, the content
  fills the window; hint text stops at 460dp and the equaliser sliders at 480dp). Page title 20 / 600 and one plain sentence in ink-3.
  - Sidebar, top to bottom: owl 52dp, device name 15 / 600, a calm dot and the status, battery label
    and a 4dp bar, nav rows (36dp, 7dp radius, 17dp line icons, selected row on bark): the pages that
    have content, in the order given under "Pages". At the bottom: the update card (only while an update is checking, downloading,
    ready or failed; otherwise a "Check for updates" row) and "Ruru <version>  About  Support Ruru".
    About opens a page with the version, licence, demo switch, connection report and support.
  - Only what the headphones report is drawn. The sidebar's battery gets a second line, "about 14 h left", when
    the headphones give a time left (the QC Ultra 1st Gen does not). A small "Mic muted" chip (7dp radius, 1px
    line, bark fill, ink-2 text) sits after the connected status only while the mic is muted. The phone header
    shows the same two, on one line.
  - Sound: a now playing strip first, one panel, one row: previous, play or pause and next (36dp buttons, 7dp
    radius, 1px line, glyphs drawn in the app, ink), what the headphones are doing in ink-2, then the volume
    slider with "8 of 31" in a fixed-width slot. A button the headphones do not list is not drawn, and there
    is no track title because the headphones send none. On a phone the buttons and the volume stack. Below it: Listening mode and Noise in two equal columns, the Equaliser full width below, with the
    response curve (2dp ink line, a hairline at 0 dB) on the left half and the sliders on the right.
    Panels here are one shade darker than the page (night in dark, #FAFBF9 in light).
  - No headphones (looking, not paired, Bluetooth off, an error): the owl, a 20 / 600 title, the
    sentence and the buttons, centred in the content area; the sidebar keeps the owl and the status.
  - Pages change with a cross-fade: the old page fades out in 200ms, the new one fades in over 350ms
    starting 100ms later and rises 8dp, ease-out-quint. Reduced motion changes pages instantly.
  - Keyboard: Ctrl+1 to 6 (Command on a Mac) open the pages in the sidebar, top to bottom, whichever are
    listed; Tab goes through the sidebar first, then the page. The chosen page is kept while the app runs, also when the window is closed.
- No rows of equal cards, no stat tiles, no hero.
- The first window is 1100 x 720, or 92% of the screen's work area (the screen without the taskbar or
  menu bar) when that is smaller, centred. A remembered window is also kept inside the work area.

## Pages

One file per page in `ui/pages` (`SoundPage.kt`, `EqualiserPage.kt` and so on); a feature edits only
its own page file and adds its page to `PageAvailability.ShippedPages`.

| Page | Holds | Where |
|---|---|---|
| Sound | listening modes, noise, the headphones' own 3-band equaliser | when the headphones have any |
| Equaliser | the Pro EQ | Windows, Linux, Android |
| Night | the night schedule | every system |
| Devices | paired devices and "Switch to this" | when the headphones report paired devices |
| Settings | the headphones' settings and shortcut button, then the settings for this computer | when there are any |
| Tools | Find my headphones (while connected), mic check, Lab | where a tool works |
| Sleep | timer, sounds and alarms | phones (a bottom bar tab) |
| More | settings, about the headphones, updates, report, support | bottom bar only |

- A page appears only when it has content. Until its feature is built, a page file holds a plain
  placeholder (title, one sentence of what will be there, "This page isn't ready yet.") and the page is
  left out of the sidebar and the bar. Nothing is drawn that does not work.
- Devices lists each paired device (name, then "Playing now", "Connected" or "Not connected", and "this device"
  for the one running Ruru). A connected device that is not playing has one quiet button, "Switch to this". There
  is no remove, forget or clear anywhere in Ruru, and the page says so and points to the system's Bluetooth
  settings. The multipoint switch stays in Settings with the touch controls switch (shown only when the
  headphones report it).
- Tools holds one panel per tool, each with a sentence, a note and one button. Find plays a rising tone that
  starts quiet, gets louder over 6 s and stops by itself after 30 s; it is listed only while connected and
  stops when the page is left. Mic check records 5 s, plays it back and keeps nothing; Windows (and often
  other systems) switch the headphones to call quality while it records, and the panel says so. On a phone
  both panels sit in More. The iPhone has neither yet, so it shows neither.
- About stays at the foot of the sidebar, next to the version.
- Bottom bar (phones and windows under 880dp): up to four tabs, in this order: Headphones, Sound (the
  Pro EQ, only where the Equaliser page exists), Sleep (phones), More. A tab appears only if it has
  content, so today the bar is Headphones and More, and it is hidden if only one tab is left. 56dp
  high on night, with a 1px line on top, an icon (22dp, drawn in the app, no icon font or emoji) over
  the name. The chosen tab is ink, the others ink-3. No accent colour and no marker. On a phone the
  bar sits above the system's gesture or navigation bar, on the same colour.

## Equaliser page

Top to bottom, up to 880dp wide: a status panel (the on/off switch, one line of state, and when something is
needed first, one sentence and one button), the response curve, then one panel with the preset menu (built in,
saved; Save as, Rename, Delete with a confirm, Import AutoEq file, Export), the Graphic / Parametric switch, the editor
and the preamp. The curve runs 20 Hz to 20 kHz on a log axis with a 0 dB line and labels at 50, 200, 1k, 5k and 10k. It shows at
least 12 dB either way and grows when the sum needs more. Graphic is ten vertical sliders in 0.5 dB steps, -12 to +12 dB.
Parametric is a list of bands (on, type, frequency, gain, Q, Remove) and an Add button, up to 20. The preamp is set
automatically (headroom, so boosts cannot clip) unless switched off; the limiter row appears only where the backend has one
(Windows has none). On a phone or in a narrow window it is the same in one column and the band rows take two lines.

On Windows the page is always listed, so people find out it exists. Without Equalizer APO it shows its plain
setup state with a button that opens APO's website (Ruru downloads nothing). On a Mac and an iPhone there is no page: one
sentence under the headphones' own 3-band equaliser says why.

## Favourite modes

Each mode row ends with a 16dp star (drawn in the app, ink at 50% until it is a favourite or hovered, filled
when it is a favourite; on the yellow selected row it is the row's dark ink). Never the accent. The tray's quick
list shows only the favourites once any are set, and every mode until then.

## Product animation

Switching listening mode: the marker slides from the old row to the new one (300ms quint) as the
command is sent, and settles when the headset confirms. That is the only "showing off" the app does.

## Words

Plain labels the Bose app's owners already know (Listening mode, Noise cancelling, Wind Block,
Immersive Audio, Self voice, Multipoint, Auto-off). One factual sentence where a control needs
explaining. NZ English. No slogans, no exclamation marks, no em dashes, no middle dots.

## Never

Inter, Geist, Instrument Serif, Space Grotesk, Fraunces or Newsreader; `#000` text; framework
default easing; shadows on cards; a cream page; italic or uppercase labels; mono for decoration;
01 / 02 / 03 markers; middle-dot strings; animation on load; purple to blue gradients; emoji icons;
Bose's logo, artwork or product photos.
