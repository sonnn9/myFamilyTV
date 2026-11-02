package com.familytv.app.core.quota

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTimeTest {
    private class MemoryQuotaStore : QuotaStore {
        var stored: QuotaState? = null
        var saves = 0
        override suspend fun load() = stored
        override suspend fun save(state: QuotaState) {
            stored = state
            saves++
        }
    }

    @Test
    fun `new day resets, same day keeps, clock moved back keeps usage`() {
        val s = QuotaState("2026-10-27", usedMs = 1_000_000, bonusMs = 60_000)
        assertEquals(QuotaState("2026-10-28"), DailyQuota.normalize(s, "2026-10-28"))
        assertEquals(s, DailyQuota.normalize(s, "2026-10-27"))
        val back = DailyQuota.normalize(s, "2026-10-26")
        assertEquals(1_000_000, back.usedMs)
        assertEquals("2026-10-26", back.dayKey)
        assertEquals(QuotaState("2026-10-27"), DailyQuota.normalize(null, "2026-10-27"))
    }

    @Test
    fun `negative and huge ticks are clamped`() {
        val s = QuotaState("d")
        assertEquals(0, DailyQuota.addPlayed(s, -50_000).usedMs)
        assertEquals(DailyQuota.MAX_TICK_MS, DailyQuota.addPlayed(s, 8 * 3_600_000L).usedMs)
        assertEquals(1_000, DailyQuota.addPlayed(s, 1_000).usedMs)
    }

    @Test
    fun `limit, bonus and unlimited`() {
        val s = QuotaState("d", usedMs = 30 * 60_000L)
        assertTrue(DailyQuota.isExhausted(s, 30))
        assertFalse(DailyQuota.isExhausted(s, 45))
        assertFalse(DailyQuota.isExhausted(s, null))
        assertFalse(DailyQuota.isExhausted(s, 0))
        assertEquals(15 * 60_000L, DailyQuota.remainingMs(DailyQuota.grant(s, 15), 30))
        assertEquals(30 * 60_000L, DailyQuota.remainingMs(DailyQuota.resetToday(s), 30))
    }

    @Test
    fun `used time and time-up state survive an app restart`() = runTest {
        val store = MemoryQuotaStore()
        val today = "2026-10-27"
        val first = WatchTimeTracker(store, { today })
        first.load()
        var exhausted = false
        repeat(15 * 60) { exhausted = first.addPlayed(1_000, limitMinutes = 15) } // 15 minutes, 1 s ticks
        assertTrue(exhausted)
        // No explicit flush: reaching the limit is persisted immediately.

        val second = WatchTimeTracker(store, { today })
        second.load()
        assertEquals(15 * 60_000L, second.state.value!!.usedMs)
        assertTrue(DailyQuota.isExhausted(second.state.value!!, 15))

        second.grant(10)
        val third = WatchTimeTracker(store, { today })
        third.load()
        assertEquals(10 * 60_000L, DailyQuota.remainingMs(third.state.value!!, 15))
    }

    @Test
    fun `progress is persisted periodically and on flush`() = runTest {
        val store = MemoryQuotaStore()
        val tracker = WatchTimeTracker(store, { "2026-10-27" }, persistEveryMs = 10_000)
        tracker.load()
        repeat(4) { tracker.addPlayed(1_000, limitMinutes = 30) }
        assertEquals(0, store.stored!!.usedMs) // below the persist threshold
        tracker.flush()
        assertEquals(4_000, store.stored!!.usedMs)
        repeat(10) { tracker.addPlayed(1_000, limitMinutes = 30) }
        assertTrue(store.stored!!.usedMs >= 13_000)
    }

    @Test
    fun `day change while running resets usage`() = runTest {
        val store = MemoryQuotaStore()
        var day = "2026-10-27"
        val tracker = WatchTimeTracker(store, { day })
        tracker.load()
        repeat(10) { tracker.addPlayed(3_000, 30) }
        day = "2026-10-28"
        tracker.addPlayed(1_000, 30)
        assertEquals(QuotaState("2026-10-28", 1_000, 0), tracker.state.value)
        assertEquals(QuotaState("2026-10-28", 1_000, 0), store.stored)
    }
}
