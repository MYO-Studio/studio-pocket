// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import java.util.Calendar
import java.util.TimeZone

/**
 * The Date & time page's choices: hour, minute, day, month, year, the 24-hour switch and a short
 * list of time zones, and the clock time a change gives. The stock Android date screen could not be
 * used on the Y2 (its automatic-time switch opens a dialog that cannot be answered with the keys), so
 * the player sets the clock itself. Pure apart from [Calendar], so it is unit-tested on the JVM.
 */
object DateTimeRows {
    enum class Field { HOUR, MINUTE, DAY, MONTH, YEAR }

    const val FIRST_YEAR = 2024
    const val LAST_YEAR = 2040
    val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val ON_OFF = listOf("Off", "On")

    /** A zone the list offers: its tz id and the place it is named by. */
    data class Zone(val id: String, val label: String)

    /** West to east; the player's own zone is added first when it is not one of these. */
    val ZONES = listOf(
        Zone("Pacific/Honolulu", "Honolulu"), Zone("America/Anchorage", "Anchorage"),
        Zone("America/Los_Angeles", "Los Angeles"), Zone("America/Denver", "Denver"),
        Zone("America/Chicago", "Chicago"), Zone("America/New_York", "New York"),
        Zone("America/Halifax", "Halifax"), Zone("America/Sao_Paulo", "São Paulo"),
        Zone("Atlantic/Reykjavik", "Reykjavík"), Zone("Europe/London", "London"),
        Zone("Europe/Dublin", "Dublin"), Zone("Europe/Lisbon", "Lisbon"),
        Zone("Europe/Paris", "Paris"), Zone("Europe/Madrid", "Madrid"),
        Zone("Europe/Berlin", "Berlin"), Zone("Europe/Amsterdam", "Amsterdam"),
        Zone("Europe/Rome", "Rome"), Zone("Africa/Johannesburg", "Johannesburg"),
        Zone("Europe/Athens", "Athens"), Zone("Europe/Helsinki", "Helsinki"),
        Zone("Africa/Cairo", "Cairo"), Zone("Europe/Istanbul", "Istanbul"),
        Zone("Europe/Moscow", "Moscow"), Zone("Asia/Dubai", "Dubai"),
        Zone("Asia/Karachi", "Karachi"), Zone("Asia/Kolkata", "India"),
        Zone("Asia/Bangkok", "Bangkok"), Zone("Asia/Singapore", "Singapore"),
        Zone("Asia/Hong_Kong", "Hong Kong"), Zone("Asia/Tokyo", "Tokyo"),
        Zone("Australia/Perth", "Perth"), Zone("Australia/Sydney", "Sydney"),
        Zone("Pacific/Auckland", "Auckland"), Zone("UTC", "UTC"),
    )

    /** "12 am" … "11 pm", or "00" … "23" in 24-hour mode. */
    fun hours(is24Hour: Boolean): List<String> = (0..23).map { h ->
        if (is24Hour) h.toString().padStart(2, '0') else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "am" else "pm"}"
    }

    fun minutes(): List<String> = (0..59).map { it.toString().padStart(2, '0') }

    fun days(now: Calendar): List<String> = (1..now.getActualMaximum(Calendar.DAY_OF_MONTH)).map { it.toString() }

    fun years(): List<String> = (FIRST_YEAR..LAST_YEAR).map { it.toString() }

    /** The row index each field shows for [now]. */
    fun index(field: Field, now: Calendar): Int = when (field) {
        Field.HOUR -> now.get(Calendar.HOUR_OF_DAY)
        Field.MINUTE -> now.get(Calendar.MINUTE)
        Field.DAY -> now.get(Calendar.DAY_OF_MONTH) - 1
        Field.MONTH -> now.get(Calendar.MONTH)
        Field.YEAR -> (now.get(Calendar.YEAR) - FIRST_YEAR).coerceIn(0, LAST_YEAR - FIRST_YEAR)
    }

    /**
     * [now] with [field] set to choice [index]. Setting the hour or minute zeroes the seconds. A day
     * past the end of the new month (31 Jan to February) becomes that month's last day.
     */
    fun withChoice(now: Calendar, field: Field, index: Int): Calendar {
        val c = now.clone() as Calendar
        when (field) {
            Field.HOUR -> { c.set(Calendar.HOUR_OF_DAY, index.coerceIn(0, 23)); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0) }
            Field.MINUTE -> { c.set(Calendar.MINUTE, index.coerceIn(0, 59)); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0) }
            Field.DAY -> c.set(Calendar.DAY_OF_MONTH, (index + 1).coerceIn(1, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            Field.MONTH -> keepDay(c) { c.set(Calendar.MONTH, index.coerceIn(0, 11)) }
            Field.YEAR -> keepDay(c) { c.set(Calendar.YEAR, FIRST_YEAR + index.coerceIn(0, LAST_YEAR - FIRST_YEAR)) }
        }
        return c
    }

    private fun keepDay(c: Calendar, change: () -> Unit) {
        val day = c.get(Calendar.DAY_OF_MONTH)
        c.set(Calendar.DAY_OF_MONTH, 1)
        change()
        c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
    }

    /** The zone list for a player in [currentId]: [ZONES], with the current zone first when it is not one of them. */
    fun zones(currentId: String): List<Zone> =
        if (ZONES.any { it.id == currentId }) ZONES else listOf(Zone(currentId, label(currentId))) + ZONES

    /** "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
    fun label(id: String): String = id.substringAfterLast('/').replace('_', ' ')

    /** The list index of the player's zone. */
    fun zoneIndex(zones: List<Zone>, current: TimeZone): Int = zones.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
}
