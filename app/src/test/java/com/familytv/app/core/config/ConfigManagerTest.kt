package com.familytv.app.core.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigManagerTest {
    private class MemoryStore : ConfigStore {
        var stored: StoredConfig? = null
        override suspend fun loadValid() = stored
        override suspend fun saveValid(config: StoredConfig) { stored = config }
        override suspend fun clearValid() { stored = null }
    }

    private val allFoldersExist: suspend (FolderConfig) -> FolderCheck = { FolderCheck.OK }
    private var now = 1_000L

    @Test
    fun `a broken update keeps the previous valid config and reports errors`() = runTest {
        val store = MemoryStore()
        val manager = ConfigManager(store) { now }
        val first = manager.applyCandidate("SAF:usb-1", ConfigParserTest.SAMPLE, allFoldersExist)
        assertEquals(4, first.active!!.folders.size)
        assertNull(first.lastFailure)

        now = 2_000L
        val broken = manager.applyCandidate("SAF:usb-1", """{"schemaVersion":1,"folders":[ """, allFoldersExist)
        assertNotNull(broken.active)
        assertEquals(4, broken.active!!.folders.size)
        assertEquals(1_000L, broken.activeAppliedAt)
        assertNotNull(broken.lastFailure)
        assertEquals(2_000L, broken.lastFailure!!.at)
        assertEquals(ConfigParserTest.SAMPLE, store.stored!!.json)
    }

    @Test
    fun `last valid config survives an app restart`() = runTest {
        val store = MemoryStore()
        ConfigManager(store) { now }.applyCandidate("SAF:usb-1", ConfigParserTest.SAMPLE, allFoldersExist)
        val restored = ConfigManager(store) { now }.restore("SAF:usb-1")
        assertEquals(4, restored.active!!.folders.size)
    }

    @Test
    fun `config of another source is never applied`() = runTest {
        val store = MemoryStore()
        val manager = ConfigManager(store) { now }
        manager.applyCandidate("SAF:usb-1", ConfigParserTest.SAMPLE, allFoldersExist)
        assertNull(manager.restore("SAF:usb-2").active)
        assertNull(manager.reportReadFailure("SAF:usb-2", "missing").active)
    }

    @Test
    fun `without any valid config nothing is active`() = runTest {
        val manager = ConfigManager(MemoryStore()) { now }
        val status = manager.applyCandidate("SAF:usb-1", "not json", allFoldersExist)
        assertNull(status.active)
        assertNotNull(status.lastFailure)
    }

    @Test
    fun `read failure keeps previous config`() = runTest {
        val manager = ConfigManager(MemoryStore()) { now }
        manager.applyCandidate("SAF:usb-1", ConfigParserTest.SAMPLE, allFoldersExist)
        val status = manager.reportReadFailure("SAF:usb-1", "Không tìm thấy config.json")
        assertNotNull(status.active)
        assertEquals("Không tìm thấy config.json", status.lastFailure!!.errors.single().message)
    }

    @Test
    fun `missing folders are warnings, not errors`() = runTest {
        val manager = ConfigManager(MemoryStore()) { now }
        val status = manager.applyCandidate("SAF:usb-1", ConfigParserTest.SAMPLE) { folder ->
            if (folder.id == "kids-english") FolderCheck.MISSING else FolderCheck.OK
        }
        assertNotNull(status.active)
        assertTrue(status.warnings.any { it.message.contains("không tồn tại") && it.location.contains("kids-english") })
    }
}
