# Credits

Ruru stands on other people's careful work. Thank you.

| Project | What Ruru uses | Licence |
|---|---|---|
| [Earshot](https://github.com/HellzYeahh/earshot) | The Bose protocol code (`core/protocol`), the connection and session logic and the simulated headset (`core/session`), the EQ maths (`core/dsp`), the rules that tell headphone makers apart and how each is reached (`core/vendors`) and their tests, ported from Android Kotlin to Kotlin Multiplatform. The Gradle wrapper. | MIT |
| [bosectl](https://github.com/aaronsb/bosectl) | Protocol research Earshot built on (message layout, mode slots, captures used in the tests). | MIT |
| [Quiet Rebellion](https://github.com/MadEyez/quietrebellion) | Protocol research Earshot built on. | MIT |
| [SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient) (mos9527, Amr Satrio and other contributors; continues Plutoberth's original) | Sony protocol research Earshot's Sony code is built on: the Tandem frame layout, the MDR v2 message layouts and the captures used in the Sony tests (`core/vendors`, `sony` package). | MIT |
| [momentum4-control](https://github.com/Ariazonaa/momentum4-control) | Sennheiser research Earshot's Sennheiser code is built on: the GAIA v3 command ids, the Momentum 4 and 5 captures used as golden frames in the tests, and the behaviour the simulated Sennheiser reproduces (`core/vendors`, `gaia` and `sennheiser` packages). | MIT |
| [momentumctl](https://codeberg.org/galen/momentumctl) | A cross-check of Sennheiser's vendor 0x0495 command ids, and of opening the headset by its GAIA v3 service UUID instead of a channel number. | MIT |
| [soundcorebridge](https://github.com/mervin008/soundcorebridge) | Soundcore protocol research Earshot's Soundcore code is built on: the model identification by serial, the state layouts and the write rules (`core/vendors`, `soundcore` package). OpenSCQ30 (GPL-3.0) and Gadgetbridge (AGPL-3.0) were read for protocol facts only; no code was copied from them. | MIT |
| Apple Continuity research (OpenPods, LibrePods, CAPod) | The layout of the Bluetooth LE adverts AirPods and Beats send (model, battery nibbles, charging bits), which Earshot's AirPods and Beats battery reader and Ruru's (`core/vendors`, `apple` package) are written from. Those projects are GPL: only the facts were used, no code was copied. | GPL (facts only) |
| [AutoEq](https://github.com/jaakkopasanen/AutoEq) | The "Tuned for QC Ultra" correction curve. | MIT |
| [Mona Sans](https://github.com/github/mona-sans) | The typeface (`third_party/mona-sans/OFL.txt`). | SIL OFL 1.1 |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform), [Kotlin](https://kotlinlang.org), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx-io](https://github.com/Kotlin/kotlinx-io) | The app framework and language. | Apache 2.0 |
| [JNA](https://github.com/java-native-access/jna) | Bluetooth sockets on Windows and Linux. | Apache 2.0 / LGPL 2.1 (Apache chosen) |

The full licence text is in [LICENSE](LICENSE). Earshot's own copyright notice is kept there, as
the MIT licence asks.

## Not affiliated

Ruru is not affiliated with, endorsed by or supported by Bose Corporation. Bose and
QuietComfort are trademarks of Bose Corporation.
