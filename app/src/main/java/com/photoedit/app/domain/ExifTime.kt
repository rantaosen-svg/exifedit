package com.photoedit.app.domain
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object ExifTime {
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    fun format(dt: LocalDateTime): String = dt.format(FMT)
    fun parse(s: String?): LocalDateTime? {
        if (s.isNullOrBlank()) return null
        return try { LocalDateTime.parse(s.trim(), FMT) } catch (e: Exception) { null }
    }
    fun offsetOf(zone: ZoneId = ZoneId.systemDefault()): String {
        val total = zone.rules.getOffset(Instant.now()).totalSeconds
        val sign = if (total < 0) "-" else "+"
        return "%s%02d:%02d".format(sign, abs(total) / 3600, abs(total) % 3600 / 60)
    }
}
