package com.familytv.app.core.pin

import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.session.SessionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinTest {
    private class MemoryPinStore : PinStore {
        var record: PinRecord? = null
        var attempts = PinAttemptState()
        override suspend fun loadRecord() = record
        override suspend fun saveRecord(record: PinRecord) { this.record = record }
        override suspend fun loadAttempts() = attempts
        override suspend fun saveAttempts(state: PinAttemptState) { attempts = state }
    }

    @Test
    fun `pbkdf2 matches the JVM reference implementation`() {
        val salt = ByteArray(16) { it.toByte() }
        val ours = PinHasher.pbkdf2("482916".toByteArray(), salt, 1_000, 32)
        val spec = PBEKeySpec("482916".toCharArray(), salt, 1_000, 256)
        val reference = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertArrayEquals(reference, ours)
    }

    @Test
    fun `stored record never contains the pin and verifies correctly`() {
        val record = PinHasher.create("482916", iterations = 1_000)
        assertFalse(Hex.encode(record.hash).contains("482916"))
        assertTrue(PinHasher.verify("482916", record))
        assertFalse(PinHasher.verify("482917", record))
        // Same PIN, new salt -> different verifier.
        assertFalse(record.hash.contentEquals(PinHasher.create("482916", iterations = 1_000).hash))
    }

    @Test
    fun `weak pins are refused`() {
        assertNotNull(PinRules.validateNewPin("123"))
        assertNotNull(PinRules.validateNewPin("123456789"))
        assertNotNull(PinRules.validateNewPin("12a4"))
        assertNotNull(PinRules.validateNewPin("0000"))
        assertNotNull(PinRules.validateNewPin("1234"))
        assertNotNull(PinRules.validateNewPin("9876"))
        assertNull(PinRules.validateNewPin("4829"))
    }

    @Test
    fun `lockout escalates and is measured on the monotonic clock`() {
        var s = PinAttemptState()
        repeat(4) { s = PinLockoutPolicy.onFailure(s, 1_000) }
        assertEquals(0, PinLockoutPolicy.remainingLockMs(s, 1_000))
        assertEquals(1, PinLockoutPolicy.attemptsLeftBeforeLock(s))
        s = PinLockoutPolicy.onFailure(s, 1_000) // 5th failure
        assertEquals(30_000, PinLockoutPolicy.remainingLockMs(s, 1_000))
        assertEquals(20_000, PinLockoutPolicy.remainingLockMs(s, 11_000))
        assertEquals(0, PinLockoutPolicy.remainingLockMs(s, 31_000))
        s = PinLockoutPolicy.onFailure(s, 40_000) // 6th failure -> 60 s
        assertEquals(60_000, PinLockoutPolicy.remainingLockMs(s, 40_000))
        // After a reboot elapsed time restarts below the lock start: never shortened.
        assertEquals(55_000, PinLockoutPolicy.remainingLockMs(s, 5_000))
        repeat(20) { s = PinLockoutPolicy.onFailure(s, 100_000) }
        assertEquals(PinLockoutPolicy.MAX_LOCK_MS, PinLockoutPolicy.remainingLockMs(s, 100_000))
    }

    @Test
    fun `manager enforces lockout across restarts and has no default pin`() = runTest {
        val store = MemoryPinStore()
        var clock = 10_000L
        val manager = PinManager(store, { clock }, iterations = 1_000)
        assertEquals(PinCheck.NotSet, manager.verify("0000"))
        assertNull(manager.setInitialPin("4829"))
        assertNotNull(manager.setInitialPin("5555")) // cannot overwrite without the old PIN

        repeat(4) { assertTrue(manager.verify("1111") is PinCheck.Wrong) }
        val fifth = manager.verify("1111") as PinCheck.Wrong
        assertEquals(30_000, fifth.lockMs)

        // "Restart": a new manager over the same store is still locked, even for the right PIN.
        val restarted = PinManager(store, { clock }, iterations = 1_000)
        assertTrue(restarted.verify("4829") is PinCheck.Locked)
        clock += 31_000
        assertTrue(restarted.verify("4829") is PinCheck.Ok)
        assertEquals(0, store.attempts.failedCount)
    }

    @Test
    fun `changing the pin requires the current one`() = runTest {
        val store = MemoryPinStore()
        val manager = PinManager(store, { 0L }, iterations = 1_000)
        manager.setInitialPin("4829")
        val proof = (manager.verify("4829") as PinCheck.Ok).proof
        assertNull(manager.changePin(proof, "7391"))
        assertTrue(manager.verify("4829") is PinCheck.Wrong)
        assertTrue(manager.verify("7391") is PinCheck.Ok)
    }

    @Test
    fun `leaving kids mode needs a fresh pin proof`() = runTest {
        var clock = 0L
        val store = MemoryPinStore()
        val pins = PinManager(store, { clock }, iterations = 1_000)
        val session = SessionManager { clock }
        assertEquals(ViewMode.KIDS, session.mode.value)
        assertFalse(session.switchMode(ViewMode.ADULTS, null))
        pins.setInitialPin("4829")
        val proof = (pins.verify("4829") as PinCheck.Ok).proof
        assertTrue(session.switchMode(ViewMode.ADULTS, proof))
        assertEquals(ViewMode.ADULTS, session.mode.value)

        session.onAppBackgrounded()
        assertEquals(ViewMode.KIDS, session.mode.value)

        clock += SessionManager.PROOF_VALIDITY_MS + 1
        assertFalse(session.switchMode(ViewMode.ALL, proof))
        assertTrue(session.switchMode(ViewMode.KIDS, null))
    }
}
