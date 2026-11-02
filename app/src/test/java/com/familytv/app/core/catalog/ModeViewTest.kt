package com.familytv.app.core.catalog

import com.familytv.app.core.config.ConfigParseResult
import com.familytv.app.core.config.ConfigParser
import com.familytv.app.core.config.ConfigParserTest
import com.familytv.app.core.model.Audience
import com.familytv.app.core.model.ContentPolicy
import com.familytv.app.core.model.FavoriteRecord
import com.familytv.app.core.model.ProgressRecord
import com.familytv.app.core.model.Video
import com.familytv.app.core.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kids/adults/all rules and "no adults content leaks" through any path. */
class ModeViewTest {
    private val config = (ConfigParser.parse(ConfigParserTest.SAMPLE) as ConfigParseResult.Valid).config

    private fun video(id: String, title: String, folders: Map<String, Audience>) = Video(
        id = id, relativePath = "videos/$id.mp4", title = title, uri = "content://x/$id",
        sizeBytes = 1, lastModified = 1, durationMs = 60_000, sidecarImageUri = null,
        folderIds = folders.keys, audiences = folders.values.toSet(),
    )

    private val kidsSong = video("song", "Bài hát Con cò", mapOf("kids-songs" to Audience.KIDS))
    private val english = video("abc", "ABC song", mapOf("kids-english" to Audience.KIDS))
    private val adultMovie = video("movie", "Phim hành động", mapOf("adults" to Audience.ADULTS))
    private val familyClip = video("trip", "Du lịch Đà Lạt", mapOf("family" to Audience.ALL))
    /** Same file reached by a kids entry and an adults entry: must be treated as adults. */
    private val conflict = video("conflict", "Bài hát người lớn", mapOf("kids-songs" to Audience.KIDS, "adults" to Audience.ADULTS))

    private val all = listOf(kidsSong, english, adultMovie, familyClip, conflict)
    private val adultIds = setOf(adultMovie.id, conflict.id)
    private val catalog = LibraryCatalog(config, all)

    private val favorites = all.mapIndexed { i, v -> FavoriteRecord(v.id, i.toLong()) }
    private val progress = all.mapIndexed { i, v -> ProgressRecord(v.id, 10_000, 60_000, completed = i % 2 == 0, updatedAt = i.toLong()) }

    private fun kids() = catalog.forMode(ViewMode.KIDS, favorites, progress)

    @Test
    fun `content policy follows kids, adults, all rules`() {
        assertTrue(ContentPolicy.isVideoVisible(setOf(Audience.KIDS), ViewMode.KIDS))
        assertTrue(ContentPolicy.isVideoVisible(setOf(Audience.ALL), ViewMode.KIDS))
        assertFalse(ContentPolicy.isVideoVisible(setOf(Audience.ADULTS), ViewMode.KIDS))
        assertFalse(ContentPolicy.isVideoVisible(setOf(Audience.KIDS, Audience.ADULTS), ViewMode.KIDS))
        assertFalse(ContentPolicy.isVideoVisible(setOf(Audience.KIDS, Audience.ALL, Audience.ADULTS), ViewMode.KIDS))
        assertFalse(ContentPolicy.isVideoVisible(emptySet(), ViewMode.KIDS))

        assertTrue(ContentPolicy.isVideoVisible(setOf(Audience.ADULTS), ViewMode.ADULTS))
        assertTrue(ContentPolicy.isVideoVisible(setOf(Audience.ALL), ViewMode.ADULTS))
        assertFalse(ContentPolicy.isVideoVisible(setOf(Audience.KIDS), ViewMode.ADULTS))

        assertTrue(ContentPolicy.isVideoVisible(setOf(Audience.KIDS), ViewMode.ALL))
        assertTrue(ContentPolicy.isVideoVisible(setOf(Audience.ADULTS), ViewMode.ALL))
    }

    @Test
    fun `kids mode shows only kids and all folders and videos`() {
        val v = kids()
        assertEquals(listOf("kids-songs", "kids-english", "family"), v.folders.map { it.id })
        assertEquals(listOf(kidsSong), v.videosIn("kids-songs")) // conflict excluded from its kids row
        assertTrue(v.videosIn("adults").isEmpty())
        assertEquals(3, v.videoCount)
    }

    @Test
    fun `adults mode shows adults and all`() {
        val v = catalog.forMode(ViewMode.ADULTS, favorites, progress)
        assertEquals(listOf("adults", "family"), v.folders.map { it.id })
        assertEquals(setOf(adultMovie.id, conflict.id), v.videosIn("adults").map { it.id }.toSet())
        assertNull(v.video(kidsSong.id))
        assertEquals(familyClip, v.video(familyClip.id))
    }

    @Test
    fun `all mode shows everything`() {
        val v = catalog.forMode(ViewMode.ALL, favorites, progress)
        assertEquals(4, v.folders.size)
        assertEquals(all.size, v.videoCount)
    }

    @Test
    fun `adults videos cannot be opened by id in kids mode`() {
        val v = kids()
        adultIds.forEach { assertNull(v.video(it)) }
        assertEquals(kidsSong, v.video(kidsSong.id))
    }

    @Test
    fun `search never returns adults content in kids mode`() {
        val v = kids()
        assertTrue(v.search("phim").isEmpty)
        assertTrue(v.search("Phim hành động").isEmpty)
        // Searching by the adults folder name must not reveal anything either.
        assertTrue(v.search("bố mẹ").isEmpty)
        assertEquals(listOf(kidsSong), v.search("bai hat").videos)
    }

    @Test
    fun `favorites, continue watching and history are filtered`() {
        val v = kids()
        assertTrue(v.favorites.none { it.id in adultIds })
        assertTrue(v.continueWatching.none { it.video.id in adultIds })
        assertTrue(v.history.none { it.video.id in adultIds })
        adultIds.forEach {
            assertFalse(v.isFavorite(it))
            assertNull(v.progressOf(it))
        }
        assertEquals(3, v.favorites.size)
    }

    @Test
    fun `player queues contain no adults content in kids mode`() {
        val v = kids()
        val contexts = listOf(
            PlayContext.Folder("kids-songs"), PlayContext.Folder("adults"), PlayContext.Folder("family"),
            PlayContext.Search("bai hat"), PlayContext.Search("phim"),
            PlayContext.Favorites, PlayContext.ContinueWatching, PlayContext.History, PlayContext.Recommended,
            PlayContext.Single,
        )
        for (ctx in contexts) {
            for (start in all) {
                val q = v.queue(ctx, start.id)
                assertTrue("$ctx/$start leaked ${q.map { it.id }}", q.none { it.id in adultIds })
                if (start.id in adultIds) assertTrue(q.isEmpty())
            }
        }
        assertEquals(listOf(kidsSong), v.queue(PlayContext.Folder("adults"), kidsSong.id))
    }

    @Test
    fun `play context round-trips through intent encoding`() {
        listOf(
            PlayContext.Folder("kids-songs"), PlayContext.Search("bài hát: con cò"),
            PlayContext.Favorites, PlayContext.ContinueWatching, PlayContext.History, PlayContext.Recommended,
            PlayContext.Single,
        ).forEach { assertEquals(it, PlayContext.decode(it.encode())) }
        assertEquals(PlayContext.Single, PlayContext.decode("garbage"))
    }

    @Test
    fun `recommended and related suggestions never contain adults content in kids mode`() {
        val v = kids()
        val recommended = v.recommended()
        assertTrue(recommended.none { it.id in adultIds })
        assertEquals(setOf(kidsSong.id, english.id, familyClip.id), recommended.map { it.id }.toSet())
        for (start in all) {
            val related = v.related(start.id)
            assertTrue("$start leaked ${related.map { it.id }}", related.none { it.id in adultIds })
            assertTrue(related.none { it.id == start.id })
            if (start.id in adultIds) assertTrue(related.isEmpty())
        }
    }

    @Test
    fun `recommended mixes folders and puts finished videos last`() {
        val songs = (1..3).map { video("s$it", "Song $it", mapOf("kids-songs" to Audience.KIDS)).copy(relativePath = "s/$it.mp4") }
        val english = (1..2).map { video("e$it", "English $it", mapOf("kids-english" to Audience.KIDS)).copy(relativePath = "e/$it.mp4") }
        val done = listOf(ProgressRecord("s1", 60_000, 60_000, completed = true, updatedAt = 1))
        val v = LibraryCatalog(config, songs + english).forMode(ViewMode.KIDS, emptyList(), done)
        assertEquals(listOf("e1", "s2", "e2", "s3", "s1"), v.recommended().map { it.id })
        // Up next after s1: rest of its folder first, then the other recommendations.
        assertEquals(listOf("s2", "s3", "e1", "e2"), v.related("s1").map { it.id })
    }

    @Test
    fun `rows are naturally sorted by file name`() {
        val a = video("a", "Bài 10", mapOf("family" to Audience.ALL)).copy(relativePath = "videos/shared/Bài 10.mp4")
        val b = video("b", "Bài 2", mapOf("family" to Audience.ALL)).copy(relativePath = "videos/shared/Bài 2.mp4")
        val view = LibraryCatalog(config, listOf(a, b)).forMode(ViewMode.KIDS, emptyList(), emptyList())
        assertEquals(listOf("b", "a"), view.videosIn("family").map { it.id })
    }
}
