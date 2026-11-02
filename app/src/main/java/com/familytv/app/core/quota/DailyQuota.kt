package com.familytv.app.core.quota

/**
 * Kids-mode watch time for one calendar day ("yyyy-MM-dd", TV local time).
 *
 * Reset rule: usage resets when the local date moves forward to a new day (00:00).
 * If the clock is moved backwards, usage is kept (no reset) and the day key follows the clock,
 * so the next real midnight still resets normally.
 */
data class QuotaState(val dayKey: String, val usedMs: Long = 0, val bonusMs: Long = 0)

object DailyQuota {
    /** Longest gap credited per tick; protects against jumps after sleep/resume or a stalled loop. */
    const val MAX_TICK_MS = 5_000L
    const val DEFAULT_LIMIT_MINUTES = 30

    fun normalize(state: QuotaState?, today: String): QuotaState = when {
        state == null -> QuotaState(today)
        today > state.dayKey -> QuotaState(today)
        today < state.dayKey -> state.copy(dayKey = today)
        else -> state
    }

    fun addPlayed(state: QuotaState, deltaMs: Long): QuotaState =
        state.copy(usedMs = state.usedMs + deltaMs.coerceIn(0, MAX_TICK_MS))

    /** null = unlimited (limit off). */
    fun limitMs(limitMinutes: Int?): Long? = limitMinutes?.takeIf { it > 0 }?.let { it * 60_000L }

    fun remainingMs(state: QuotaState, limitMinutes: Int?): Long? =
        limitMs(limitMinutes)?.let { (it + state.bonusMs - state.usedMs).coerceAtLeast(0) }

    fun isExhausted(state: QuotaState, limitMinutes: Int?): Boolean = remainingMs(state, limitMinutes) == 0L

    fun grant(state: QuotaState, minutes: Int): QuotaState =
        state.copy(bonusMs = state.bonusMs + minutes.coerceAtLeast(0) * 60_000L)

    fun resetToday(state: QuotaState): QuotaState = state.copy(usedMs = 0, bonusMs = 0)
}
