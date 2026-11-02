package com.familytv.app.core.quota

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface QuotaStore {
    suspend fun load(): QuotaState?
    suspend fun save(state: QuotaState)
}

/**
 * Keeps today's kids watch time in memory and persists it, so quitting and reopening the app
 * neither clears the used time nor lifts the "time is up" state. Callers feed it deltas measured
 * with the monotonic clock while a video is actually playing.
 */
class WatchTimeTracker(
    private val store: QuotaStore,
    private val today: () -> String,
    private val persistEveryMs: Long = 10_000,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<QuotaState?>(null)
    val state: StateFlow<QuotaState?> = _state.asStateFlow()
    private var unsavedMs = 0L

    suspend fun load() = mutex.withLock {
        val s = DailyQuota.normalize(store.load(), today())
        _state.value = s
        store.save(s)
    }

    /** Re-evaluates the day boundary (call when the app comes to the foreground). */
    suspend fun refreshDay() = mutex.withLock {
        val s = current()
        if (s != _state.value) {
            _state.value = s
            store.save(s)
            unsavedMs = 0
        }
    }

    /** Adds played time; returns true when the limit is now exhausted. */
    suspend fun addPlayed(deltaMs: Long, limitMinutes: Int?): Boolean = mutex.withLock {
        val previous = _state.value
        val before = current()
        val after = DailyQuota.addPlayed(before, deltaMs)
        _state.value = after
        unsavedMs += after.usedMs - before.usedMs
        val exhausted = DailyQuota.isExhausted(after, limitMinutes)
        if (unsavedMs >= persistEveryMs || exhausted || previous?.dayKey != after.dayKey) {
            store.save(after)
            unsavedMs = 0
        }
        exhausted
    }

    suspend fun flush() = mutex.withLock {
        _state.value?.let { store.save(it) }
        unsavedMs = 0
    }

    suspend fun grant(minutes: Int) = update { DailyQuota.grant(it, minutes) }

    suspend fun resetToday() = update { DailyQuota.resetToday(it) }

    private suspend fun update(change: (QuotaState) -> QuotaState) = mutex.withLock {
        val s = change(current())
        _state.value = s
        store.save(s)
        unsavedMs = 0
    }

    private suspend fun current(): QuotaState =
        DailyQuota.normalize(_state.value ?: store.load(), today())
}
