package com.familytv.app.core.config

import com.familytv.app.core.model.Audience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserTest {

    private fun valid(json: String): ConfigParseResult.Valid {
        val r = ConfigParser.parse(json)
        assertTrue("expected valid, got $r", r is ConfigParseResult.Valid)
        return r as ConfigParseResult.Valid
    }

    private fun errors(json: String): List<ConfigIssue> {
        val r = ConfigParser.parse(json)
        assertTrue("expected invalid, got $r", r is ConfigParseResult.Invalid)
        return (r as ConfigParseResult.Invalid).errors
    }

    private fun folderJson(path: String, audience: String = "kids", id: String = "f1") =
        """{"schemaVersion":1,"folders":[{"id":"$id","name":"N","path":"$path","audience":"$audience"}]}"""

    @Test
    fun `sample config from the spec is valid`() {
        val r = valid(SAMPLE)
        val c = r.config
        assertEquals("Family TV", c.appName)
        assertEquals(4, c.folders.size)
        assertEquals(listOf("kids-songs", "kids-english", "adults", "family"), c.folders.map { it.id })
        assertEquals(listOf(Audience.KIDS, Audience.KIDS, Audience.ADULTS, Audience.ALL), c.folders.map { it.audience })
        assertEquals("videos/kids/songs", c.folders[0].path)
        assertEquals(false, c.settings.autoplay)
        assertEquals(true, c.settings.resumePlayback)
        assertEquals(30, c.settings.sessionLimitMinutes)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `malformed json is rejected with line information`() {
        val e = errors("{\n  \"schemaVersion\": 1,\n  \"folders\": [ \n}")
        assertEquals(1, e.size)
        assertTrue(e[0].message, e[0].message.startsWith("JSON sai định dạng"))
        assertTrue(e[0].message, e[0].message.contains("dòng"))
    }

    @Test
    fun `empty file and non-object root are rejected`() {
        assertTrue(errors("   ").isNotEmpty())
        assertTrue(errors("[1,2,3]").single().message.contains("đối tượng"))
    }

    @Test
    fun `utf8 bom from windows notepad is accepted`() {
        valid("\uFEFF" + folderJson("videos"))
    }

    @Test
    fun `unsupported or missing schemaVersion is rejected`() {
        assertTrue(errors("""{"schemaVersion":2,"folders":[]}""").single().message.contains("không được hỗ trợ"))
        assertTrue(errors("""{"folders":[]}""").single().location == "schemaVersion")
        assertTrue(errors("""{"schemaVersion":"1","folders":[]}""").single().location == "schemaVersion")
    }

    @Test
    fun `duplicate ids are rejected case-insensitively`() {
        val json = """{"schemaVersion":1,"folders":[
            {"id":"kids","name":"A","path":"a","audience":"kids"},
            {"id":"KIDS","name":"B","path":"b","audience":"kids"}]}"""
        val e = errors(json)
        assertTrue(e.any { it.message.contains("bị trùng") })
    }

    @Test
    fun `invalid or missing audience is rejected`() {
        assertTrue(errors(folderJson("videos", audience = "teens")).any { it.location.endsWith("audience") })
        val missing = """{"schemaVersion":1,"folders":[{"id":"a","name":"A","path":"videos"}]}"""
        assertTrue(errors(missing).any { it.location.endsWith("audience") })
    }

    @Test
    fun `unsafe paths are rejected`() {
        listOf(
            "/storage/1A2B-3C4D/videos",
            "C:\\\\videos",
            "../secret",
            "videos/../../outside",
            "videos/..",
            "content://com.android.externalstorage.documents/tree/x",
            "file:///sdcard",
            "~/videos",
            "",
        ).forEach { path ->
            val e = errors(folderJson(path))
            assertTrue("path '$path' should be rejected", e.any { it.location.endsWith("path") })
        }
    }

    @Test
    fun `backslashes and dot segments are normalized`() {
        assertEquals("videos/kids", valid(folderJson("videos\\\\kids")).config.folders[0].path)
        assertEquals("videos/kids", valid(folderJson("./videos//kids/")).config.folders[0].path)
        assertEquals("", valid(folderJson(".")).config.folders[0].path)
    }

    @Test
    fun `wrong setting types and ranges are errors`() {
        val json = """{"schemaVersion":1,"settings":{"autoplay":"yes","sessionLimitMinutes":-5},
            "folders":[{"id":"a","name":"A","path":"v","audience":"kids"}]}"""
        val e = errors(json)
        assertTrue(e.any { it.location == "settings.autoplay" })
        assertTrue(e.any { it.location == "settings.sessionLimitMinutes" })
    }

    @Test
    fun `unknown sortBy and keys are warnings only`() {
        val json = """{"schemaVersion":1,"extra":true,
            "folders":[{"id":"a","name":"A","path":"v","audience":"kids","sortBy":"random","colour":"red"}]}"""
        val r = valid(json)
        assertEquals(SortBy.FILENAME, r.config.folders[0].sortBy)
        assertTrue(r.warnings.any { it.location.endsWith("sortBy") })
        assertTrue(r.warnings.any { it.location == "extra" })
    }

    @Test
    fun `defaultMode other than kids is ignored with a warning`() {
        val json = """{"schemaVersion":1,"defaultMode":"adults","folders":[{"id":"a","name":"A","path":"v","audience":"kids"}]}"""
        assertTrue(valid(json).warnings.any { it.location == "defaultMode" })
    }

    @Test
    fun `overlapping folders with adults produce an explanatory warning`() {
        val json = """{"schemaVersion":1,"folders":[
            {"id":"all-kids","name":"A","path":"videos","audience":"kids","recursive":true},
            {"id":"grown","name":"B","path":"videos/adults","audience":"adults"}]}"""
        val r = valid(json)
        assertTrue(r.warnings.any { it.message.contains("không hiện ở chế độ Trẻ em") })
    }

    @Test
    fun `recursive defaults to true`() {
        assertTrue(valid(folderJson("v")).config.folders[0].recursive)
        assertFalse(
            valid("""{"schemaVersion":1,"folders":[{"id":"a","name":"A","path":"v","audience":"kids","recursive":false}]}""")
                .config.folders[0].recursive,
        )
    }

    companion object {
        val SAMPLE = """
            {
              "schemaVersion": 1,
              "appName": "Family TV",
              "defaultMode": "kids",
              "settings": { "autoplay": false, "resumePlayback": true, "sessionLimitMinutes": 30 },
              "folders": [
                { "id": "kids-songs", "name": "Các bài hát thiếu nhi", "path": "videos/kids/songs", "audience": "kids", "recursive": true, "sortBy": "filename" },
                { "id": "kids-english", "name": "Tiếng Anh cho bé", "path": "videos/kids/english", "audience": "kids", "recursive": true, "sortBy": "filename" },
                { "id": "adults", "name": "Video dành cho bố mẹ", "path": "videos/adults", "audience": "adults", "recursive": true, "sortBy": "filename" },
                { "id": "family", "name": "Cả nhà cùng xem", "path": "videos/shared", "audience": "all", "recursive": true, "sortBy": "filename" }
              ]
            }
        """.trimIndent()
    }
}
