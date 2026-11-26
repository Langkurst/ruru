package io.github.langkurst.ruru.app

/**
 * The tip page, on Ko-fi, shared by all of Langkurst's apps. The Support button opens it in the
 * web browser; Ruru itself never connects to it. Keep README.md and .github/FUNDING.yml in step
 * (a test checks all three).
 */
const val SUPPORT_URL = "https://ko-fi.com/keaclip"

/** The download page. Ruru never connects to it; the Check for updates button opens it in the browser. */
const val WEBSITE_URL = "https://ruru-app.pages.dev"

/**
 * Where a report about a headset goes: GitHub's new-issue page for Ruru. The Send a report button
 * copies the connection report and opens this page in the browser, so the person goes online
 * themselves and pastes it; Ruru never connects to it and puts nothing in the address.
 */
const val ISSUES_URL = "https://github.com/Langkurst/ruru/issues/new"
