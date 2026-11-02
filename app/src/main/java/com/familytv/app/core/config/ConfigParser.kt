package com.familytv.app.core.config

import com.familytv.app.core.config.ConfigIssue.Severity
import com.familytv.app.core.model.Audience
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ConfigParseResult {
    val warnings: List<ConfigIssue>

    data class Valid(val config: LibraryConfig, override val warnings: List<ConfigIssue>) : ConfigParseResult
    data class Invalid(val errors: List<ConfigIssue>, override val warnings: List<ConfigIssue>) : ConfigParseResult
}

/**
 * Strict parser for config.json. Structural problems (bad JSON, unsupported schemaVersion,
 * duplicate ids, unknown audience, unsafe paths) are errors and reject the whole file; harmless
 * oddities (unknown keys, unknown sortBy) are warnings. Folder existence is checked separately
 * because it needs storage access (see [ConfigManager]).
 */
object ConfigParser {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_CONFIG_CHARS = 512 * 1024
    const val MAX_FOLDERS = 200
    const val MAX_LIMIT_MINUTES = 24 * 60
    const val DEFAULT_APP_NAME = "Family TV"

    /** Windows Notepad prefixes UTF-8 files with U+FEFF. */
    private val UTF8_BOM = Char(0xFEFF).toString()
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val OFFSET = Regex("offset (\\d+)")
    private val ROOT_KEYS = setOf("schemaVersion", "appName", "defaultMode", "settings", "folders")
    private val SETTINGS_KEYS = setOf("autoplay", "resumePlayback", "sessionLimitMinutes")
    private val FOLDER_KEYS = setOf("id", "name", "path", "audience", "recursive", "sortBy")

    fun parse(text: String): ConfigParseResult {
        val errors = mutableListOf<ConfigIssue>()
        val warnings = mutableListOf<ConfigIssue>()
        fun err(at: String, msg: String) = errors.add(ConfigIssue(Severity.ERROR, at, msg))
        fun warn(at: String, msg: String) = warnings.add(ConfigIssue(Severity.WARNING, at, msg))

        val clean = text.removePrefix(UTF8_BOM)
        if (clean.length > MAX_CONFIG_CHARS) {
            err("", "File config.json quá lớn (tối đa ${MAX_CONFIG_CHARS / 1024} KB)")
            return ConfigParseResult.Invalid(errors, warnings)
        }
        if (clean.isBlank()) {
            err("", "File config.json trống")
            return ConfigParseResult.Invalid(errors, warnings)
        }
        val root = try {
            Json.parseToJsonElement(clean)
        } catch (e: Exception) {
            err("", "JSON sai định dạng" + describeJsonError(clean, e.message))
            return ConfigParseResult.Invalid(errors, warnings)
        }
        if (root !is JsonObject) {
            err("", "Nội dung gốc phải là một đối tượng JSON { ... }")
            return ConfigParseResult.Invalid(errors, warnings)
        }
        (root.keys - ROOT_KEYS).forEach { warn(it, "Khóa không được hỗ trợ, sẽ bị bỏ qua") }

        // schemaVersion first: if unsupported, the rest of the file may have a different meaning.
        val schemaEl = root["schemaVersion"]
        val schemaVersion = schemaEl?.asInt()
        when {
            schemaEl == null -> err("schemaVersion", "Thiếu schemaVersion (cần giá trị $SUPPORTED_SCHEMA_VERSION)")
            schemaVersion == null -> err("schemaVersion", "schemaVersion phải là số nguyên")
            schemaVersion != SUPPORTED_SCHEMA_VERSION ->
                err("schemaVersion", "schemaVersion $schemaVersion không được hỗ trợ (app này chỉ hỗ trợ $SUPPORTED_SCHEMA_VERSION)")
        }
        if (errors.isNotEmpty()) return ConfigParseResult.Invalid(errors, warnings)

        var appName = DEFAULT_APP_NAME
        root["appName"]?.let { el ->
            val s = el.asString()
            if (s == null) err("appName", "appName phải là chuỗi") else if (s.isNotBlank()) appName = s.trim().take(40)
        }

        root["defaultMode"]?.let { el ->
            val s = el.asString()
            val audience = s?.let { Audience.fromKey(it.trim().lowercase()) }
            when {
                audience == null -> err("defaultMode", "defaultMode phải là \"kids\", \"adults\" hoặc \"all\"")
                audience != Audience.KIDS -> warn(
                    "defaultMode",
                    "Vì an toàn, app luôn khởi động ở chế độ Trẻ em; giá trị \"$s\" bị bỏ qua",
                )
            }
        }

        val settings = parseSettings(root["settings"], ::err, ::warn)
        val folders = parseFolders(root["folders"], ::err, ::warn)

        if (errors.isNotEmpty()) return ConfigParseResult.Invalid(errors, warnings)
        warnings += overlapWarnings(folders)
        return ConfigParseResult.Valid(
            LibraryConfig(SUPPORTED_SCHEMA_VERSION, appName, settings, folders),
            warnings,
        )
    }

    private fun parseSettings(
        el: JsonElement?,
        err: (String, String) -> Boolean,
        warn: (String, String) -> Boolean,
    ): ConfigSettings {
        if (el == null) return ConfigSettings()
        if (el !is JsonObject) {
            err("settings", "settings phải là một đối tượng { ... }")
            return ConfigSettings()
        }
        (el.keys - SETTINGS_KEYS).forEach { warn("settings.$it", "Khóa không được hỗ trợ, sẽ bị bỏ qua") }
        val autoplay = el["autoplay"]?.let { v ->
            v.asBool().also { if (it == null) err("settings.autoplay", "Phải là true hoặc false") }
        }
        val resume = el["resumePlayback"]?.let { v ->
            v.asBool().also { if (it == null) err("settings.resumePlayback", "Phải là true hoặc false") }
        }
        val limit = el["sessionLimitMinutes"]?.let { v ->
            val n = v.asInt()
            when {
                n == null -> { err("settings.sessionLimitMinutes", "Phải là số phút (số nguyên, 0 = không giới hạn)"); null }
                n < 0 || n > MAX_LIMIT_MINUTES -> {
                    err("settings.sessionLimitMinutes", "Phải nằm trong khoảng 0..$MAX_LIMIT_MINUTES"); null
                }
                else -> n
            }
        }
        return ConfigSettings(autoplay, resume, limit)
    }

    private fun parseFolders(
        el: JsonElement?,
        err: (String, String) -> Boolean,
        warn: (String, String) -> Boolean,
    ): List<FolderConfig> {
        if (el == null) {
            err("folders", "Thiếu danh sách folders")
            return emptyList()
        }
        if (el !is JsonArray) {
            err("folders", "folders phải là một mảng [ ... ]")
            return emptyList()
        }
        if (el.isEmpty()) {
            err("folders", "Chưa khai báo thư mục nào")
            return emptyList()
        }
        if (el.size > MAX_FOLDERS) {
            err("folders", "Quá nhiều thư mục (tối đa $MAX_FOLDERS)")
            return emptyList()
        }
        val result = mutableListOf<FolderConfig>()
        val seenIds = HashMap<String, Int>()
        el.forEachIndexed { index, item ->
            val at = "folders[$index]"
            if (item !is JsonObject) {
                err(at, "Mỗi thư mục phải là một đối tượng { ... }")
                return@forEachIndexed
            }
            (item.keys - FOLDER_KEYS).forEach { warn("$at.$it", "Khóa không được hỗ trợ, sẽ bị bỏ qua") }
            var ok = true

            val id = item["id"]?.asString()?.trim()
            when {
                id.isNullOrEmpty() -> { err("$at.id", "Thiếu id"); ok = false }
                !ID_PATTERN.matches(id) -> {
                    err("$at.id", "id \"$id\" chỉ được dùng chữ không dấu, số, \"-\" và \"_\" (tối đa 64 ký tự)"); ok = false
                }
                else -> {
                    val previous = seenIds.put(id.lowercase(), index)
                    if (previous != null) {
                        err("$at.id", "id \"$id\" bị trùng với folders[$previous]"); ok = false
                    }
                }
            }

            val name = item["name"]?.asString()?.trim()
            if (name.isNullOrEmpty()) { err("$at.name", "Thiếu tên hiển thị (name)"); ok = false }

            val rawPath = item["path"]?.asString()
            var path = ""
            if (rawPath == null) {
                err("$at.path", "Thiếu đường dẫn (path)"); ok = false
            } else {
                when (val r = PathRules.normalize(rawPath)) {
                    is PathRules.Result.Ok -> path = r.path
                    is PathRules.Result.Rejected -> { err("$at.path", "\"$rawPath\": ${r.reason}"); ok = false }
                }
            }

            val audienceRaw = item["audience"]?.asString()
            val audience = audienceRaw?.let { Audience.fromKey(it.trim().lowercase()) }
            if (audience == null) {
                val shown = audienceRaw?.let { "\"$it\"" } ?: "(thiếu)"
                err("$at.audience", "audience $shown không hợp lệ; chỉ chấp nhận \"kids\", \"adults\" hoặc \"all\"")
                ok = false
            }

            var recursive = true
            item["recursive"]?.let { v ->
                val b = v.asBool()
                if (b == null) { err("$at.recursive", "Phải là true hoặc false"); ok = false } else recursive = b
            }

            var sortBy = SortBy.FILENAME
            item["sortBy"]?.let { v ->
                val s = v.asString()?.trim()?.lowercase()
                val parsed = s?.let { SortBy.fromKey(it) }
                if (parsed == null) {
                    warn("$at.sortBy", "Không hỗ trợ \"${s ?: v}\"; dùng \"filename\". Hỗ trợ: " + SortBy.entries.joinToString { it.key })
                } else {
                    sortBy = parsed
                }
            }

            if (ok) result += FolderConfig(id!!, name!!.take(100), path, audience!!, recursive, sortBy)
        }
        return result
    }

    /** Explain (as warnings) where overlapping folder entries mix audiences and which rule applies. */
    private fun overlapWarnings(folders: List<FolderConfig>): List<ConfigIssue> {
        val out = mutableListOf<ConfigIssue>()
        for (i in folders.indices) for (j in folders.indices) {
            if (i >= j) continue
            val a = folders[i]
            val b = folders[j]
            if (a.audience == b.audience) continue
            val overlaps = (a.path.equals(b.path, ignoreCase = true)) ||
                (a.recursive && PathRules.isWithin(b.path, a.path)) ||
                (b.recursive && PathRules.isWithin(a.path, b.path))
            if (!overlaps) continue
            val involvesAdults = a.audience == Audience.ADULTS || b.audience == Audience.ADULTS
            val rule = if (involvesAdults) {
                "video nằm trong cả hai sẽ được coi là \"adults\" và không hiện ở chế độ Trẻ em"
            } else {
                "video nằm trong cả hai hiện theo từng mục"
            }
            out += ConfigIssue(
                Severity.WARNING,
                "folders",
                "\"${a.id}\" (${a.audience.key}) và \"${b.id}\" (${b.audience.key}) trùng phạm vi thư mục; $rule",
            )
        }
        return out
    }

    private fun describeJsonError(text: String, message: String?): String {
        val offset = message?.let { OFFSET.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return ""
        val upTo = text.substring(0, offset.coerceIn(0, text.length))
        val line = upTo.count { it == '\n' } + 1
        val column = offset - (upTo.lastIndexOf('\n') + 1) + 1
        return " (dòng $line, cột $column)"
    }

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.asInt(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonElement.asBool(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
