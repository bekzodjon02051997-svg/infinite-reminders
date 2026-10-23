package com.infinitereminders.app

import com.infinitereminders.app.data.Draft
import com.infinitereminders.app.data.Recurrence
import com.infinitereminders.app.data.Reminder
import com.infinitereminders.app.data.Repeat
import com.infinitereminders.app.data.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DomainTest {
    private val zone = ZoneId.of("UTC")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 9) =
        ZonedDateTime.of(y, mo, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun dailyNextIsStrictlyAfterNow() {
        val n = Recurrence.nextDue(ms(2026, 10, 20), Repeat.DAILY, ms(2026, 10, 22, 10), zone)
        assertEquals(ms(2026, 10, 23), n)
    }

    @Test fun dailyDueLaterTodayGoesToTomorrow() {
        val n = Recurrence.nextDue(ms(2026, 10, 22, 9), Repeat.DAILY, ms(2026, 10, 22, 8), zone)
        assertEquals(ms(2026, 10, 23), n)
    }

    @Test fun weeklySkipsMissedOccurrencesWithoutCatchUp() {
        val n = Recurrence.nextDue(ms(2026, 9, 1), Repeat.WEEKLY, ms(2026, 10, 22), zone)
        assertTrue(n > ms(2026, 10, 22))
        assertEquals(ms(2026, 10, 27), n)
    }

    @Test fun monthlyKeepsDayOfMonthAfterShortMonth() {
        val n = Recurrence.nextDue(ms(2026, 1, 31), Repeat.MONTHLY, ms(2026, 3, 1), zone)
        assertEquals(ms(2026, 3, 31), n)
    }

    @Test fun nextOccurrenceIsFreshActiveReminder() {
        val r = Reminder(id = 5, title = "x", createdAt = 1, dueAt = ms(2026, 10, 20), state = State.COMPLETED,
            firstDoneAt = 2, completedAt = 3, repeat = Repeat.DAILY, alertedForDueAt = ms(2026, 10, 20))
        val n = Recurrence.next(r, ms(2026, 10, 21), zone)
        assertEquals(0L, n.id)
        assertEquals(State.ACTIVE, n.state)
        assertNull(n.firstDoneAt); assertNull(n.completedAt); assertNull(n.alertedForDueAt)
    }

    @Test fun draftWithoutDueDropsRepeatAndAlert() {
        val d = Draft("t", repeat = Repeat.DAILY, alertEnabled = true).normalized()
        assertEquals(Repeat.NONE, d.repeat)
        assertFalse(d.alertEnabled)
    }

    @Test fun draftTrimsAndClampsPriority() {
        val d = Draft("  hi  ", priority = 99, category = " a ").normalized()
        assertEquals("hi", d.title); assertEquals(3, d.priority); assertEquals("a", d.category)
    }
}
