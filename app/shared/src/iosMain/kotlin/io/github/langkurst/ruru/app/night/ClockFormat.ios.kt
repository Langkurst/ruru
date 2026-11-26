package io.github.langkurst.ruru.app.night

import platform.Foundation.NSCalendar
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

actual fun uses24HourClock(): Boolean {
    // The "j" template is the hour the person's region prefers; it carries an "a" when that is 12-hour.
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale) ?: return false
    return !pattern.contains('a')
}

actual fun formatClockTime(hour: Int, minute: Int): String {
    val parts = NSDateComponents().apply {
        setHour(hour.toLong())
        setMinute(minute.toLong())
    }
    val date = NSCalendar.currentCalendar.dateFromComponents(parts)
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }
    return date?.let { formatter.stringFromDate(it) } ?: "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}
