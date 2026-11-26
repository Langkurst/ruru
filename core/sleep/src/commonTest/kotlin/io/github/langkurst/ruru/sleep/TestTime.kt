package io.github.langkurst.ruru.sleep

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()
