package io.github.langkurst.ruru.app.night

/** True when this system's language and region write the time on a 24-hour clock ("22:30") rather than with am and pm. */
expect fun uses24HourClock(): Boolean

/** A time of day the way this system's language and region write it: "10:30 pm", "22:30", "午後10:30". */
expect fun formatClockTime(hour: Int, minute: Int): String
