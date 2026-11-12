// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import app.studiopocket.player.settings.DateTimeRows.Field
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DateTimeRowsTest {
    private fun at(y: Int, mon: Int, d: Int, h: Int, min: Int, s: Int = 30): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/London")).apply { clear(); set(y, mon, d, h, min, s) }

    @Test fun hourLabels() {
        assertEquals(listOf("12 am", "1 am"), DateTimeRows.hours(false).take(2))
        assertEquals("12 pm", DateTimeRows.hours(false)[12])
        assertEquals("11 pm", DateTimeRows.hours(false)[23])
        assertEquals("00", DateTimeRows.hours(true)[0])
        assertEquals("23", DateTimeRows.hours(true)[23])
    }

    @Test fun indexesShowTheCurrentTime() {
        val now = at(2026, Calendar.NOVEMBER, 10, 21, 5)
        assertEquals(21, DateTimeRows.index(Field.HOUR, now))
        assertEquals(5, DateTimeRows.index(Field.MINUTE, now))
        assertEquals(9, DateTimeRows.index(Field.DAY, now))
        assertEquals(10, DateTimeRows.index(Field.MONTH, now))
        assertEquals(2, DateTimeRows.index(Field.YEAR, now))
        assertEquals(30, DateTimeRows.days(now).size)
    }

    @Test fun settingTheTimeZeroesTheSeconds() {
        val c = DateTimeRows.withChoice(at(2026, Calendar.NOVEMBER, 10, 21, 5), Field.MINUTE, 40)
        assertEquals(40, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(21, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(7, DateTimeRows.withChoice(c, Field.HOUR, 7).get(Calendar.HOUR_OF_DAY))
    }

    @Test fun aDayPastTheEndOfTheNewMonthBecomesItsLast() {
        val c = DateTimeRows.withChoice(at(2026, Calendar.JANUARY, 31, 9, 0), Field.MONTH, Calendar.FEBRUARY)
        assertEquals(Calendar.FEBRUARY, c.get(Calendar.MONTH))
        assertEquals(28, c.get(Calendar.DAY_OF_MONTH))
        val leap = DateTimeRows.withChoice(at(2025, Calendar.FEBRUARY, 28, 9, 0), Field.YEAR, 2028 - DateTimeRows.FIRST_YEAR)
        assertEquals(2028, leap.get(Calendar.YEAR))
        assertEquals(28, leap.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun theCurrentZoneIsOfferedEvenWhenNotListed() {
        assertEquals(DateTimeRows.ZONES, DateTimeRows.zones("Europe/London"))
        val z = DateTimeRows.zones("America/Argentina/Buenos_Aires")
        assertEquals(DateTimeRows.Zone("America/Argentina/Buenos_Aires", "Buenos Aires"), z.first())
        assertEquals(0, DateTimeRows.zoneIndex(z, TimeZone.getTimeZone("America/Argentina/Buenos_Aires")))
    }

    @Test fun everyListedZoneIsARealZone() {
        for (zone in DateTimeRows.ZONES) assertEquals(zone.id, TimeZone.getTimeZone(zone.id).id)
    }
}
