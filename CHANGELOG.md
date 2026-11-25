# Changelog

## Unreleased

- Windows: a new Equaliser page with a full equaliser, 10 sliders or a list of bands, presets (built in, your
  own, AutoEq files in and out) and an on/off switch. It works through Equalizer APO, a free program Ruru does not
  download for you; the page shows how to set it up. With your agreement Ruru saves one file next to APO's
  config.txt and adds one line to it, and switching off takes the line out again. Mac and iPhone cannot offer this,
  and the Sound page now says so under the headphones' own three bands.
- Settings longer than 8 KB (many saved equaliser presets) are now kept whole on the computer.
- A big window now fills the screen. The pages grow with the window instead of stopping at 1000dp, and
  from a window about 1280dp wide the whole app (title bar, sidebar and pages) is drawn a little larger,
  up to a third larger on a 2560 pixel screen, so it no longer looks tiny. The equaliser sliders and the
  hint text keep a comfortable length.
- Windows: maximised, the window now reaches the edges of the screen. A dark strip of about 16 pixels
  was left along the right and bottom, which also pushed the caption buttons in from the edge.
- Windows: the first start of 0.2.0 could open at the smallest size. The build had left that size in the
  settings. Ruru ignores it now, and the build no longer writes any settings. A window is only
  remembered at a size under the default when you resized it yourself.

## 0.2.0 (25 November 2026)

- On a computer, Ruru now looks like a desktop app. A wide window has a sidebar with your headphones,
  the battery and two pages: Sound (listening modes and noise side by side, the equaliser below with a
  curve that shows its shape) and Settings. Ctrl+1 and Ctrl+2 switch pages. Narrow windows and phones
  are unchanged.
- On Windows, Ruru draws its own title bar in its own colours. Snapping, the shadow and resizing from
  the edges still work as usual.
- A new switch, Keep running in the tray, is on by default. Turn it off and closing the window quits
  Ruru.
- Check for updates now does the work on Windows and Mac. It looks for the newest version, downloads
  it, checks it, then asks if you want to install it and restart. Ruru still goes online only when you
  click the button. Linux and phones open the website.
- Windows: a new installer, Ruru-<version>-setup.exe, with the owl, a smooth progress bar and the
  percentage. It installs for your user only (no admin password), adds a Start menu shortcut and an
  entry in Settings > Apps, and removes an older MSI copy of Ruru first so you never end up with two.
  The .msi stays as the second download, and the old plain .exe is gone.
- Ruru opens faster on Windows: about 1 second from a click to the window, down from 1.5.
- The owl is new: a chubby ruru with black headphones, in the app, the tray, the installers, on phones
  and on the website.
## 0.1.3 (25 November 2026)

- Ruru now speaks 11 languages: English, German, French, Spanish, Italian, Dutch, Portuguese
  (Brazil), Japanese, Chinese (Simplified), Korean and Polish. It uses your computer's or phone's
  language.
- The website is in the same languages.

## 0.1.2 (25 November 2026)

- A new owl icon: a friendlier ruru face, in the app, the tray, the installers and on phones.
- Check for updates: a new button at the bottom of the window opens the Ruru website, which tells
  you if a newer version is out. Ruru itself still never uses the internet.
- Windows, Mac and Linux: the download is about a fifth smaller. Unused library code is left out,
  and Ruru works the same. Tested with real headphones on Windows.
- Mac: About Ruru in the menu now opens Ruru's window, which shows the real version number.
- Phones: a long headphone name now wraps onto a second line instead of being cut off.
## 0.1.1 (25 November 2026)

The first release: everything under 0.1.0, plus these changes.

- iPhone: control over Bluetooth LE is built. The QC Ultra Headphones (1st Gen) answer this way
  from a Windows PC, but the iPhone app hasn't been tried on a real iPhone yet, so it may not
  work. It opens only headphones this iPhone is playing through or has used before, checks their
  own Bluetooth address where iOS gives one, and says in plain words when pairing, permission or
  Bluetooth stops it.
- iPhone: Ruru no longer tries Apple's accessory channel (iAP2), which needs a protocol name Bose
  hasn't published. It uses Bluetooth LE instead.
- macOS: the window opens again. In 0.1.0 a renderer setting meant for Windows stopped it opening
  on a Mac, while the menu bar icon kept running.
- Linux: the .deb has a proper Maintainer line and the .rpm states its licence (MIT). The .deb
  needs Ubuntu 24.04, Debian 13 or newer.

## 0.1.0 (11 November 2026)

First version, tagged but not released: its Mac app couldn't open its window.

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
