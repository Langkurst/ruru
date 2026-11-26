package io.github.langkurst.ruru.app.night

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar

actual fun uses24HourClock(): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT) as? SimpleDateFormat)?.toPattern() ?: return false
    return (pattern.contains('H') || pattern.contains('k')) && !pattern.contains('a')
}

actual fun formatClockTime(hour: Int, minute: Int): String {
    val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(at.time)
}
