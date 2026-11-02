package com.familytv.app.core.catalog

import com.familytv.app.core.config.FolderConfig
import com.familytv.app.core.config.LibraryConfig
import com.familytv.app.core.config.SortBy
import com.familytv.app.core.model.ContentPolicy
import com.familytv.app.core.model.FavoriteRecord
import com.familytv.app.core.model.ProgressRecord
import com.familytv.app.core.model.Video
import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.text.NaturalOrder
import com.familytv.app.core.text.VietnameseText

/** The list a video was opened from; the player rebuilds its queue from it (always mode-filtered). */
sealed interface PlayContext {
    fun encode(): String

    data class Folder(val folderId: String) : PlayContext {
        override fun encode() = "folder:$folderId"
    }

    data class Search(val query: String) : PlayContext {
        override fun encode() = "search:$query"
    }

    data object Favorites : PlayContext {
        override fun encode() = "favorites"
    }

    data object ContinueWatching : PlayContext {
        override fun encode() = "continue"
    }

    data object History : PlayContext {
        override fun encode() = "history"
    }

    data object Recommended : PlayContext {
        override fun encode() = "recommended"
    }

    data object Single : PlayContext {
        override fun encode() = "single"
    }

    companion object {
        fun decode(value: String?): PlayContext = when {
            value == null -> Single
            value.startsWith("folder:") -> Folder(value.removePrefix("folder:"))
            value.startsWith("search:") -> Search(value.removePrefix("search:"))
            value == "favorites" -> Favorites
            value == "continue" -> ContinueWatching
            value == "history" -> History
            value == "recommended" -> Recommended
            else -> Single
        }
    }
}

data class WatchEntry(val video: Video, val progress: ProgressRecord)

data class SearchResults(val query: String, val folders: List<FolderConfig>, val videos: List<Video>) {
    val isEmpty get() = folders.isEmpty() && videos.isEmpty()
}

/** Everything indexed for the current source + active config. Never handed to UI directly. */
class LibraryCatalog(val config: LibraryConfig, val videos: List<Video>) {
    fun forMode(
        mode: ViewMode,
        favorites: List<FavoriteRecord>,
        progress: List<ProgressRecord>,
    ): ModeView = ModeView(mode, config, videos, favorites, progress)
}

/**
 * The library as one mode is allowed to see it. All screens, search, the player queue and
 * open-by-id go through this class, so the filtering rule lives in exactly one place.
 */
class ModeView internal constructor(
    val mode: ViewMode,
    config: LibraryConfig,
    allVideos: List<Video>,
    favoriteRecords: List<FavoriteRecord>,
    progressRecords: List<ProgressRecord>,
) {
    val appName: String = config.appName

    val folders: List<FolderConfig> = config.folders.filter { mode.allowsFolder(it.audience) }
    private val folderById = folders.associateBy { it.id }

    private val visible: Map<String, Video> = allVideos
        .filter { ContentPolicy.isVideoVisible(it.audiences, mode) }
        .associateBy { it.id }

    val videoCount: Int get() = visible.size

    private val folderVideos: Map<String, List<Video>> by lazy {
        folders.associate { folder ->
            val inFolder = visible.values.filter { folder.id in it.folderIds }
            folder.id to inFolder.sortedWith(comparatorFor(folder.sortBy))
        }
    }

    private val progressById: Map<String, ProgressRecord> =
        progressRecords.filter { it.videoId in visible }.associateBy { it.videoId }

    private val favoriteIds: Set<String> = favoriteRecords.mapTo(HashSet()) { it.videoId }

    val favorites: List<Video> = favoriteRecords
        .sortedByDescending { it.addedAt }
        .mapNotNull { visible[it.videoId] }

    val history: List<WatchEntry> = progressById.values
        .sortedByDescending { it.updatedAt }
        .mapNotNull { p -> visible[p.videoId]?.let { WatchEntry(it, p) } }

    val continueWatching: List<WatchEntry> = history.filter { !it.progress.completed && it.progress.positionMs > 0 }

    private val searchKeys: Map<String, Pair<String, String>> by lazy {
        visible.values.associate { v ->
            val folderNames = v.folderIds.mapNotNull { folderById[it]?.name }.joinToString(" ")
            v.id to (VietnameseText.searchKey(v.title) to VietnameseText.searchKey(folderNames))
        }
    }

    fun folder(id: String): FolderConfig? = folderById[id]

    fun videosIn(folderId: String): List<Video> = folderVideos[folderId].orEmpty()

    /** Open-by-id (player, intents): null when the video is not allowed in this mode. */
    fun video(id: String): Video? = visible[id]

    fun progressOf(id: String): ProgressRecord? = progressById[id]

    fun isFavorite(id: String): Boolean = id in favoriteIds && id in visible

    fun search(query: String): SearchResults {
        val tokens = VietnameseText.tokens(query)
        if (tokens.isEmpty()) return SearchResults(query, emptyList(), emptyList())
        val matchedFolders = folders.filter { VietnameseText.matchesAll(VietnameseText.searchKey(it.name), tokens) }
        val byTitle = mutableListOf<Video>()
        val byFolder = mutableListOf<Video>()
        for (v in visible.values) {
            val (titleKey, folderKey) = searchKeys.getValue(v.id)
            when {
                VietnameseText.matchesAll(titleKey, tokens) -> byTitle += v
                VietnameseText.matchesAll("$titleKey $folderKey", tokens) -> byFolder += v
            }
        }
        val byName = compareBy<Video, String>(NaturalOrder) { it.title }
        return SearchResults(query, matchedFolders, byTitle.sortedWith(byName) + byFolder.sortedWith(byName))
    }

    /** Queue for the player. Contains only videos visible in this mode; empty if [currentId] is not. */
    fun queue(context: PlayContext, currentId: String): List<Video> {
        val current = video(currentId) ?: return emptyList()
        val list = when (context) {
            is PlayContext.Folder -> videosIn(context.folderId)
            is PlayContext.Search -> search(context.query).videos
            PlayContext.Favorites -> favorites
            PlayContext.ContinueWatching -> continueWatching.map { it.video }
            PlayContext.History -> history.map { it.video }
            PlayContext.Recommended -> recommended()
            PlayContext.Single -> emptyList()
        }
        return if (list.any { it.id == currentId }) list else listOf(current)
    }

    /**
     * Home "recommended" shelf: videos taken round-robin from every folder of this mode, so one
     * big folder does not fill the row; not yet finished videos come first.
     */
    fun recommended(limit: Int = 30): List<Video> {
        val lists = folders.map { videosIn(it.id) }
        val picked = LinkedHashMap<String, Video>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (list in lists) list.getOrNull(i)?.let { v -> picked.getOrPut(v.id) { v } }
            i++
        }
        val (fresh, finished) = picked.values.partition { progressById[it.id]?.completed != true }
        return (fresh + finished).take(limit)
    }

    /** Player "up next" suggestions for [currentId]: same folder(s) first, then recommendations. */
    fun related(currentId: String, limit: Int = 20): List<Video> {
        val current = video(currentId) ?: return emptyList()
        val picked = LinkedHashMap<String, Video>()
        for (folderId in current.folderIds) {
            val list = videosIn(folderId)
            val at = list.indexOfFirst { it.id == currentId }
            // Continue after the current file first, like the next episodes of a playlist.
            (list.drop(at + 1) + list.take(at.coerceAtLeast(0))).forEach { v -> picked.getOrPut(v.id) { v } }
        }
        recommended(limit * 2).forEach { v -> picked.getOrPut(v.id) { v } }
        picked.remove(currentId)
        return picked.values.take(limit)
    }

    companion object {
        fun comparatorFor(sortBy: SortBy): Comparator<Video> {
            val byPath = compareBy<Video, String>(NaturalOrder) { it.relativePath }
            return when (sortBy) {
                SortBy.FILENAME -> byPath
                // Not Comparator.reversed(): that default method needs API 24.
                SortBy.FILENAME_DESC -> Comparator { a, b -> byPath.compare(b, a) }
                SortBy.MODIFIED_DESC -> compareByDescending<Video> { it.lastModified }.then(byPath)
                SortBy.MODIFIED_ASC -> compareBy<Video> { it.lastModified }.then(byPath)
            }
        }
    }
}
