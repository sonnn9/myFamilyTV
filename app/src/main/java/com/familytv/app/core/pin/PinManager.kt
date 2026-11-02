package com.familytv.app.core.pin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface PinStore {
    suspend fun loadRecord(): PinRecord?
    suspend fun saveRecord(record: PinRecord)
    suspend fun loadAttempts(): PinAttemptState
    suspend fun saveAttempts(state: PinAttemptState)
}

/** Only [PinManager.verify] can create this, so "switch to adults" cannot be called without a PIN. */
class PinProof internal constructor(val verifiedAtElapsed: Long)

sealed interface PinCheck {
    data class Ok(val proof: PinProof) : PinCheck
    data class Wrong(val attemptsLeft: Int, val lockMs: Long) : PinCheck
    data class Locked(val remainingMs: Long) : PinCheck
    data object NotSet : PinCheck
}

class PinManager(
    private val store: PinStore,
    private val elapsedRealtime: () -> Long,
    private val iterations: Int = PinHasher.DEFAULT_ITERATIONS,
) {
    private val mutex = Mutex()
    private val _isSet = MutableStateFlow<Boolean?>(null)
    /** null until loaded. There is no default PIN: until one is set, nothing PIN-gated is reachable. */
    val isSet: StateFlow<Boolean?> = _isSet.asStateFlow()

    suspend fun load() {
        _isSet.value = store.loadRecord() != null
    }

    suspend fun lockRemainingMs(): Long =
        PinLockoutPolicy.remainingLockMs(store.loadAttempts(), elapsedRealtime())

    suspend fun verify(pin: String): PinCheck = mutex.withLock {
        val record = store.loadRecord() ?: return@withLock PinCheck.NotSet
        val attempts = store.loadAttempts()
        val remaining = PinLockoutPolicy.remainingLockMs(attempts, elapsedRealtime())
        if (remaining > 0) return@withLock PinCheck.Locked(remaining)

        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, record) }
        if (ok) {
            store.saveAttempts(PinLockoutPolicy.onSuccess())
            PinCheck.Ok(PinProof(elapsedRealtime()))
        } else {
            val now = elapsedRealtime()
            val next = PinLockoutPolicy.onFailure(attempts, now)
            store.saveAttempts(next)
            PinCheck.Wrong(PinLockoutPolicy.attemptsLeftBeforeLock(next), PinLockoutPolicy.remainingLockMs(next, now))
        }
    }

    /** First-time setup only. Returns an error message, or null on success. */
    suspend fun setInitialPin(pin: String): String? = mutex.withLock {
        if (store.loadRecord() != null) return@withLock "PIN đã được thiết lập; hãy dùng chức năng đổi PIN"
        savePin(pin)
    }

    /** Change PIN; [proof] comes from verifying the current PIN. */
    suspend fun changePin(@Suppress("UNUSED_PARAMETER") proof: PinProof, newPin: String): String? =
        mutex.withLock { savePin(newPin) }

    private suspend fun savePin(pin: String): String? {
        PinRules.validateNewPin(pin)?.let { return it }
        val record = withContext(Dispatchers.Default) { PinHasher.create(pin, iterations) }
        try {
            store.saveRecord(record)
            store.saveAttempts(PinLockoutPolicy.onSuccess())
        } catch (e: Exception) {
            return "Không lưu được PIN (bộ nhớ TV đầy?): ${e.message}"
        }
        _isSet.value = true
        return null
    }
}
