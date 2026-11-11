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

- One column, 360 to 480dp wide (desktop window default 400 x 760, phone full width).
- Panels stacked with 12dp gaps, 16dp padding. Rows 40dp minimum height (48dp on touch).
- Order: headset header, listening mode, noise, equaliser, shortcut button, settings, about.
- No rows of equal cards, no stat tiles, no hero.

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
