package com.familytv.app.core.model

/** Audience declared for a folder in config.json. */
enum class Audience(val key: String) {
    KIDS("kids"),
    ADULTS("adults"),
    ALL("all");

    companion object {
        fun fromKey(key: String): Audience? = entries.firstOrNull { it.key == key }
    }
}

/** What the TV is currently showing. Every launch starts in [KIDS]. */
enum class ViewMode(val label: String) {
    KIDS("Trẻ em"),
    ADULTS("Người lớn"),
    ALL("Tất cả");

    /** Folder rows a mode lists: Kids = kids + all, Adults = adults + all, All = everything. */
    fun allowsFolder(audience: Audience): Boolean = when (this) {
        KIDS -> audience == Audience.KIDS || audience == Audience.ALL
        ADULTS -> audience == Audience.ADULTS || audience == Audience.ALL
        ALL -> true
    }
}

/**
 * One indexed video. [audiences] holds the audience of every config folder that contains the file
 * (a file can be covered by several overlapping folder entries).
 */
data class Video(
    val id: String,
    val relativePath: String,
    val title: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long?,
    val sidecarImageUri: String?,
    val folderIds: Set<String>,
    val audiences: Set<Audience>,
)

data class FavoriteRecord(val videoId: String, val addedAt: Long)

data class ProgressRecord(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

/**
 * The single place that decides whether a video may be shown or played in a mode.
 *
 * Safe rule for conflicting config entries: if ANY folder entry covering a file is "adults",
 * the file never appears in Kids mode – not in its kids row, search, favorites, history or queue.
 */
object ContentPolicy {
    fun isVideoVisible(audiences: Set<Audience>, mode: ViewMode): Boolean = when (mode) {
        ViewMode.KIDS -> audiences.isNotEmpty() && Audience.ADULTS !in audiences
        ViewMode.ADULTS -> Audience.ADULTS in audiences || Audience.ALL in audiences
        ViewMode.ALL -> audiences.isNotEmpty()
    }
}
