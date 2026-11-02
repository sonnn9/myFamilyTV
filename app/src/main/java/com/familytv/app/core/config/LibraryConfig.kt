package com.familytv.app.core.config

import com.familytv.app.core.model.Audience

data class LibraryConfig(
    val schemaVersion: Int,
    val appName: String,
    val settings: ConfigSettings,
    val folders: List<FolderConfig>,
)

/** Values from config.json; null = not specified. Parent overrides set in the app take priority. */
data class ConfigSettings(
    val autoplay: Boolean? = null,
    val resumePlayback: Boolean? = null,
    /** 0 = no limit. */
    val sessionLimitMinutes: Int? = null,
)

data class FolderConfig(
    val id: String,
    val name: String,
    /** Normalized relative path ("" = the selected FamilyTV folder itself). */
    val path: String,
    val audience: Audience,
    val recursive: Boolean,
    val sortBy: SortBy,
)

enum class SortBy(val key: String) {
    FILENAME("filename"),
    FILENAME_DESC("filename_desc"),
    MODIFIED_DESC("modified_desc"),
    MODIFIED_ASC("modified_asc");

    companion object {
        fun fromKey(key: String): SortBy? = entries.firstOrNull { it.key == key }
    }
}

data class ConfigIssue(val severity: Severity, val location: String, val message: String) {
    enum class Severity { ERROR, WARNING }

    override fun toString(): String = if (location.isEmpty()) message else "$location: $message"
}
