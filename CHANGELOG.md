# Changelog

## 0.1.0 (unreleased)

First version.

- Windows, macOS and Linux desktop app with a tray icon (battery level, quick mode switch).
  Tested on Windows with QC Ultra Headphones (1st Gen).
- Android app (2.4 MB, no internet permission) and iPhone app (demo mode; it can't control
  headphones yet).
- Listening modes, including adding and removing your own; noise cancelling level, Wind Block and
  Immersive Audio; the headphones' equaliser; shortcut button; self voice; multipoint; auto-off;
  auto play/pause; auto-answer; voice prompts.
- Demo mode with a simulated QC Ultra Headphones (1st Gen).
- Opening Ruru while it is already running shows its window instead of starting a second copy.
- Messages in plain words when a change fails or no headphones are found, including Android's
  Nearby devices permission.
- The connection report leaves out every Bluetooth address, the headphones' own included.
- Keyboard focus can be seen on every control; sliders read out their value.
- A Support Ruru button (Ko-fi) at the bottom of the window.
- The tray shows when the headphones are charging, and a notification says when a charge has
  finished or the battery is low.
- Bose protocol and session logic from Earshot (MIT), with the QC Ultra Headphones (1st Gen)
  profile corrected from a real unit: no live audio settings, 47-byte mode slots, auto-off.
