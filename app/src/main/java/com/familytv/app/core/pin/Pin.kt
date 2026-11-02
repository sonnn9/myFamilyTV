package com.familytv.app.core.pin

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stored PIN verifier: PBKDF2-HMAC-SHA256(pin, salt, iterations). The PIN itself is never stored. */
class PinRecord(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

object PinRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    /** Error message for an unacceptable new PIN, or null if it is fine. */
    fun validateNewPin(pin: String): String? {
        if (pin.length !in MIN_LENGTH..MAX_LENGTH || pin.any { it !in '0'..'9' }) {
            return "PIN phải gồm $MIN_LENGTH–$MAX_LENGTH chữ số"
        }
        if (pin.all { it == pin[0] }) return "PIN quá dễ đoán (các số giống nhau)"
        val steps = pin.zipWithNext { a, b -> b - a }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return "PIN quá dễ đoán (dãy số liên tiếp)"
        return null
    }
}

object PinHasher {
    const val DEFAULT_ITERATIONS = 40_000
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32

    fun create(pin: String, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): PinRecord {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinRecord(salt, pbkdf2(pin.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES), iterations)
    }

    fun verify(pin: String, record: PinRecord): Boolean {
        val candidate = pbkdf2(pin.toByteArray(Charsets.UTF_8), record.salt, record.iterations, record.hash.size)
        return MessageDigest.isEqual(candidate, record.hash)
    }

    /** RFC 8018 PBKDF2 with HMAC-SHA256 (portable to every API level, unlike SecretKeyFactory names). */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations > 0 && keyLength > 0)
        // HMAC keys must be non-empty for SecretKeySpec; a PIN is never empty here.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val hLen = mac.macLength
        val blocks = (keyLength + hLen - 1) / hLen
        val out = ByteArray(keyLength)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val offset = (block - 1) * hLen
            System.arraycopy(t, 0, out, offset, minOf(hLen, keyLength - offset))
        }
        return out
    }
}

/**
 * Failed-attempt bookkeeping. Lock time is measured with the monotonic clock
 * (SystemClock.elapsedRealtime) so changing the TV clock cannot shorten it.
 */
data class PinAttemptState(
    val failedCount: Int = 0,
    val lockStartElapsed: Long = 0,
    val lockDurationMs: Long = 0,
)

object PinLockoutPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    fun remainingLockMs(state: PinAttemptState, nowElapsed: Long): Long {
        if (state.lockDurationMs <= 0) return 0
        // After a reboot elapsedRealtime restarts from 0: time since boot is then a lower bound
        // of the real time passed, so the lock is never shortened, at most extended.
        val passed = if (nowElapsed >= state.lockStartElapsed) nowElapsed - state.lockStartElapsed else nowElapsed
        return (state.lockDurationMs - passed).coerceIn(0, state.lockDurationMs)
    }

    fun onFailure(state: PinAttemptState, nowElapsed: Long): PinAttemptState {
        val count = state.failedCount + 1
        if (count < FREE_ATTEMPTS) return PinAttemptState(failedCount = count)
        val exponent = (count - FREE_ATTEMPTS).coerceAtMost(10)
        val duration = (BASE_LOCK_MS shl exponent).coerceAtMost(MAX_LOCK_MS)
        return PinAttemptState(count, nowElapsed, duration)
    }

    fun onSuccess(): PinAttemptState = PinAttemptState()

    fun attemptsLeftBeforeLock(state: PinAttemptState): Int = (FREE_ATTEMPTS - state.failedCount).coerceAtLeast(0)
}

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0)
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
