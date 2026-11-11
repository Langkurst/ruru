# Ruru: notes for coding sessions

Ruru is a free, open-source app for Bose headphones (Windows, macOS, Linux, Android, iPhone),
built with Kotlin Multiplatform and Compose Multiplatform. Earshot (MIT) is the source of the
Bose protocol code; see CREDITS.md.

## Layout

- `core/protocol` Bose BMAP frames and tables · `core/dsp` EQ maths · `core/session` session
  logic, simulated headset, `HeadsetPlatform` interface
- `platform/windows|linux|macos` one Bluetooth connection each (`macos/helper` is a Swift tool)
- `app/shared` screens + `HeadsetController` (also the iOS framework) · `app/desktop` ·
  `app/android` · `iosApp` (XcodeGen project) · `tools/probe` headset checks from a Windows PC

## Commands (Windows: `gradlew.bat`, JDK 21; Android needs ANDROID_HOME)

- Tests: `gradlew :core:protocol:jvmTest :core:dsp:jvmTest :core:session:jvmTest :app:shared:desktopTest`
- Desktop app: `gradlew :app:desktop:run` (`--args=--demo` for demo mode)
- Screenshot of the real app: `gradlew :app:desktop:snapshot -Pout=<png> [-Pdemo] [-Plight] [-Pheight=1600]`
- Real headphones: `gradlew :app:desktop:hardwareCheck` changes the mode and bass through the
  app's controller, confirms each on a new connection, then puts both back. The headphones must
  be worn while it runs (they switch off when taken off); if a run is cut short, the next run
  starts by undoing what it changed. `gradlew :tools:probe:run` reads every setting and changes
  nothing
- Android: `gradlew :app:android:assembleRelease` (signed only when `keystore.properties` exists)
- iOS Kotlin compiles on Windows: `gradlew :app:shared:compileKotlinIosArm64`

## Rules

- Public identity is Langkurst. No personal names, email addresses, Windows user names,
  Bluetooth addresses or serial numbers in code, docs, commits, screenshots or releases.
  Commit as `Langkurst <145878628+Langkurst@users.noreply.github.com>`.
- Never commit `_private/`, `PLAN.md`, `keystore.properties` or any `.jks`.
- Headphone safety: send only BMAP commands the code already knows. A write test changes one
  setting and puts it back. Never send anything on the Apple accessory (iAP2) channel. No firmware
  updates, ever.
- Design follows `docs/DESIGN.md`. Wording is plain NZ English.
- CI: a push runs tests, Windows, Linux and Android. A hand-started run with `apple=true`, or a
  `v*` tag, adds macOS, iPhone and the Android emulator check. macOS minutes are expensive on
  a private repo, so start Apple runs only when something there needs checking.
- Line endings come from `.gitattributes` (`gradlew` and shell scripts stay LF).
