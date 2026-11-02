package com.familytv.app.data.library

import com.familytv.app.core.catalog.LibraryCatalog
import com.familytv.app.core.config.ConfigParseResult
import com.familytv.app.core.config.ConfigParser
import com.familytv.app.core.config.FolderCheck
import com.familytv.app.core.config.LibraryConfig
import com.familytv.app.core.model.Audience
import com.familytv.app.core.model.Video
import com.familytv.app.core.model.ViewMode
import com.familytv.app.data.source.FsEntry
import com.familytv.app.data.source.FsPaths
import com.familytv.app.data.source.LibraryFileSystem
import com.familytv.app.data.source.LibrarySourceRef
import com.familytv.app.data.source.SourceAvailability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** In-memory read-only file tree standing in for a USB stick. */
private class FakeFs(paths: List<String>, sourceValue: String = "usb-1", private val broken: Set<String> = emptySet()) : LibraryFileSystem {
    override val source = LibrarySourceRef(LibrarySourceRef.Type.SAF, sourceValue)
    private val files = paths.toSet()
    private val dirs: Set<String> = buildSet {
        add("")
        paths.forEach { p ->
            var d = p.substringBeforeLast('/', "")
            while (d.isNotEmpty()) {
                add(d)
                d = d.substringBeforeLast('/', "")
            }
        }
    }

    override fun availability() = SourceAvailability.AVAILABLE
    override fun root() = FsEntry("", true, 0, 0, "fake://", "")

    override fun list(dir: FsEntry): List<FsEntry> {
        if (dir.ref in broken) throw IOException("unplugged")
        val prefix = if (dir.ref.isEmpty()) "" else dir.ref + "/"
        val children = (files + dirs).filter { it.startsWith(prefix) && it != dir.ref && !it.removePrefix(prefix).contains('/') }
        return children.map { FsEntry(it.substringAfterLast('/'), it in dirs, 10, 5, "fake://$it", it) }
    }

    override fun open(entry: FsEntry): InputStream = ByteArrayInputStream(ByteArray(0))
}

class LibraryScannerTest {
    private fun config(json: String): LibraryConfig = (ConfigParser.parse(json) as ConfigParseResult.Valid).config

    private val usb = listOf(
        "config.json",
        "videos/kids/songs/Bài hát ABC.mp4",
        "videos/kids/songs/Bài hát ABC.jpg",
        "videos/kids/songs/._Bài hát ABC.mp4",
        "videos/kids/songs/notes.txt",
        "videos/kids/songs/nested/Con cò.mkv",
        "videos/adults/Phim.mp4",
        "videos/shared/Du lịch.mov",
        "private/Bí mật.mp4",
    )

    @Test
    fun `only declared folders are indexed, hidden and non-video files skipped`() = runTest {
        val cfg = config(
            """{"schemaVersion":1,"folders":[
                {"id":"songs","name":"Songs","path":"videos/kids/songs","audience":"kids"},
                {"id":"shared","name":"Shared","path":"videos/shared","audience":"all"}]}""",
        )
        val result = LibraryScanner(FakeFs(usb)).scan(cfg)
        val paths = result.videos.map { it.relativePath }.toSet()
        assertEquals(
            setOf("videos/kids/songs/Bài hát ABC.mp4", "videos/kids/songs/nested/Con cò.mkv", "videos/shared/Du lịch.mov"),
            paths,
        )
        assertTrue(result.complete)
        val abc = result.videos.first { it.title == "Bài hát ABC" }
        assertEquals("fake://videos/kids/songs/Bài hát ABC.jpg", abc.sidecarUri)
    }

    @Test
    fun `non-recursive folder ignores subfolders`() = runTest {
        val cfg = config("""{"schemaVersion":1,"folders":[{"id":"s","name":"S","path":"videos/kids/songs","audience":"kids","recursive":false}]}""")
        val result = LibraryScanner(FakeFs(usb)).scan(cfg)
        assertEquals(listOf("videos/kids/songs/Bài hát ABC.mp4"), result.videos.map { it.relativePath })
    }

    @Test
    fun `overlapping entries merge audiences so adults files never show in kids mode`() = runTest {
        // Kids entry covers all of "Videos" (different case!) and therefore also videos/adults.
        val cfg = config(
            """{"schemaVersion":1,"folders":[
                {"id":"everything","name":"Tất cả","path":"Videos","audience":"kids","recursive":true},
                {"id":"grown","name":"Bố mẹ","path":"videos/adults","audience":"adults"}]}""",
        )
        val result = LibraryScanner(FakeFs(usb)).scan(cfg)
        val movie = result.videos.single { it.relativePath == "videos/adults/Phim.mp4" }
        assertEquals(setOf(Audience.KIDS, Audience.ADULTS), movie.audiences)
        assertEquals(setOf("everything", "grown"), movie.folderIds)

        val videos = result.videos.map {
            Video(it.id, it.relativePath, it.title, it.uri, it.size, it.lastModified, null, it.sidecarUri, it.folderIds, it.audiences)
        }
        val kids = LibraryCatalog(cfg, videos).forMode(ViewMode.KIDS, emptyList(), emptyList())
        assertNull(kids.video(movie.id))
        assertTrue(kids.videosIn("everything").none { it.id == movie.id })
        assertTrue(kids.search("phim").isEmpty)
    }

    @Test
    fun `ids are stable per path and differ between two usb sticks`() = runTest {
        val cfg = config("""{"schemaVersion":1,"folders":[{"id":"s","name":"S","path":"videos/shared","audience":"all"}]}""")
        val a1 = LibraryScanner(FakeFs(usb, "usb-1")).scan(cfg).videos.single()
        val a2 = LibraryScanner(FakeFs(usb, "usb-1")).scan(cfg).videos.single()
        val b = LibraryScanner(FakeFs(usb, "usb-2")).scan(cfg).videos.single()
        assertEquals(a1.id, a2.id)
        assertNotEquals(a1.id, b.id)
    }

    @Test
    fun `missing folders are reported and unreadable folders mark the scan incomplete`() = runTest {
        val cfg = config(
            """{"schemaVersion":1,"folders":[
                {"id":"gone","name":"Gone","path":"videos/deleted","audience":"kids"},
                {"id":"songs","name":"Songs","path":"videos/kids/songs","audience":"kids"}]}""",
        )
        val ok = LibraryScanner(FakeFs(usb)).scan(cfg)
        assertTrue(ok.complete)
        assertTrue(ok.messages.any { it.contains("videos/deleted") })

        val unplugged = LibraryScanner(FakeFs(usb, broken = setOf("videos/kids/songs/nested"))).scan(cfg)
        assertFalse(unplugged.complete)
    }

    @Test
    fun `paths resolve only inside the granted root`() {
        val fs = FakeFs(usb)
        assertEquals("videos/kids/songs", FsPaths.resolveDir(fs, "VIDEOS/Kids/songs")!!.relativePath)
        assertNull(FsPaths.resolveDir(fs, "videos/none"))
        assertNull(FsPaths.resolveDir(fs, "config.json")) // a file is not a folder
        assertEquals(FolderCheck.MISSING, FsPaths.checkFolder(fs, "videos/none"))
        assertEquals(FolderCheck.NOT_A_DIRECTORY, FsPaths.checkFolder(fs, "videos/adults/Phim.mp4"))
        assertEquals(FolderCheck.OK, FsPaths.checkFolder(fs, "videos/adults"))
    }
}
