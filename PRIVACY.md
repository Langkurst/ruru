# Privacy

Ruru talks to your Bose headphones, over Bluetooth, on your own device. It uses the internet only
when you click Check for updates in the Windows, macOS or Linux app.

- No account, no sign-in.
- No analytics, crash reporting or advertising. No update checks in the background: Ruru never
  connects to anything until you click the button.
- Check for updates asks GitHub (`api.github.com`, then the release file from `github.com`) for the
  newest Ruru version, and downloads the installer if there is one. It sends a plain request with
  the user agent `Ruru/<version>`, no account and no identifier. GitHub sees your IP address, as
  any website does. Ruru checks the download against the release's published SHA-256 checksum
  before it offers to install it, and installs only when you choose Install and restart.
- The Android and iPhone apps never connect to anything. Their Check for updates button opens the
  Ruru website in your browser. The Android app does not request the internet permission, so it
  cannot send anything.
- Bluetooth permission is used to see your paired headphones and talk to them. Ruru does not
  scan for other devices and does not ask for location.
- Settings live on the headphones themselves. Ruru stores nothing about you.
- The Support Ruru button opens the Ko-fi page in your web browser. Ruru itself never connects
  to it.
- The connection report is text you copy yourself. It holds the headphone model, firmware and
  recent connection log lines, with Bluetooth addresses masked and the names of your other
  devices left out. It goes nowhere unless you paste it somewhere.
