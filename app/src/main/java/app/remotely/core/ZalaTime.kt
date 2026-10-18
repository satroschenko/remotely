package app.remotely.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Time helpers for the portal's "yyyy.MM.dd HH:mm:ss" format and the Minsk wall clock. */
object ZalaTime {
    val MINSK: ZoneId = ZoneId.of("Europe/Minsk")
    private val PORTAL = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun parseUtc(text: String): Long? = parse(text, ZoneOffset.UTC)
    fun parseMinsk(text: String): Long? = parse(text, MINSK)

    private fun parse(text: String, zone: ZoneId): Long? = try {
        LocalDateTime.parse(text.trim(), PORTAL).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun formatUtc(epochMs: Long): String =
        PORTAL.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    fun hhmm(epochMs: Long): String = HM.format(Instant.ofEpochMilli(epochMs).atZone(MINSK))

    fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(MINSK).toLocalDate()

    /** [start, end) of a Minsk calendar day in epoch ms. */
    fun dayRange(day: LocalDate): LongRange {
        val start = day.atStartOfDay(MINSK).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(MINSK).toInstant().toEpochMilli()
        return start until end
    }

    /** "Сегодня", "Вчера", "Позавчера", "Завтра" or "15.10". */
    fun dayLabel(day: LocalDate, today: LocalDate): String = when (day.toEpochDay() - today.toEpochDay()) {
        0L -> "Сегодня"
        -1L -> "Вчера"
        -2L -> "Позавчера"
        1L -> "Завтра"
        else -> "%02d.%02d".format(day.dayOfMonth, day.monthValue)
    }

    /** "Вчера 19:30" style label for an instant relative to now. */
    fun relativeLabel(epochMs: Long, nowMs: Long): String =
        "${dayLabel(localDate(epochMs), localDate(nowMs))} ${hhmm(epochMs)}"
}
