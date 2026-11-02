package com.familytv.app.data.library

import com.familytv.app.core.config.LibraryConfig
import com.familytv.app.core.model.Audience
import com.familytv.app.core.text.VietnameseText
import com.familytv.app.data.source.FsEntry
import com.familytv.app.data.source.FsPaths
import com.familytv.app.data.source.LibraryFileSystem
import com.familytv.app.data.source.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

object MediaFiles {
    /** Containers Media3 can usually open; whether a file actually plays depends on the TV's codecs. */
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m2ts", "mts", "avi", "mpg", "mpeg", "flv")
    /** In order of preference when several images share the video's name. */
    val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun baseName(name: String) = name.substringBeforeLast('.')

    /** Hidden/system entries, including macOS "._name.mp4" AppleDouble files. */
    fun isHidden(name: String) = name.startsWith(".") || name.startsWith("$") ||
        name.equals("System Volume Information", ignoreCase = true) || name.equals("LOST.DIR", ignoreCase = true)

    fun isVideo(name: String) = !isHidden(name) && extension(name) in VIDEO_EXTENSIONS
    fun isImage(name: String) = !isHidden(name) && extension(name) in IMAGE_EXTENSIONS

    /** Title = file name without extension (NFC-normalized for Vietnamese names). */
    fun titleOf(fileName: String) = VietnameseText.display(baseName(fileName)).ifEmpty { fileName }
}

data class ScannedVideo(
    val id: String,
    val relativePath: String,
    val title: String,
    val uri: String,
    val size: Long,
    val lastModified: Long,
    val sidecarUri: String?,
    val folderIds: Set<String>,
    val audiences: Set<Audience>,
)

/**
 * [complete] is false when some directory could not be read (e.g. USB pulled mid-scan); callers
 * must then not delete anything that is missing from [videos].
 */
data class ScanResult(val videos: List<ScannedVideo>, val messages: List<String>, val complete: Boolean)

/**
 * Lists only the folders declared in config.json (never the whole USB). A file reached through
 * several folder entries is reported once, with the union of their ids and audiences.
 */
class LibraryScanner(
    private val fs: LibraryFileSystem,
    private val maxDepth: Int = 12,
    private val maxVideos: Int = 20_000,
) {
    private class Builder(val entry: FsEntry, val relativePath: String, val sidecar: FsEntry?) {
        val folderIds = LinkedHashSet<String>()
        val audiences = LinkedHashSet<Audience>()
    }

    suspend fun scan(config: LibraryConfig, onProgress: (Int) -> Unit = {}): ScanResult = withContext(Dispatchers.IO) {
        val listings = HashMap<String, List<FsEntry>>()
        val found = LinkedHashMap<String, Builder>()
        val messages = mutableListOf<String>()
        var complete = true
        var limitHit = false

        fun listing(dir: FsEntry): List<FsEntry> = listings.getOrPut(dir.ref) { fs.list(dir) }

        for (folder in config.folders) {
            ensureActive()
            val resolved = try {
                FsPaths.resolveDir(fs, folder.path, ::listing)
            } catch (e: IOException) {
                complete = false
                messages += "Không đọc được \"${folder.path}\": ${e.message}"
                continue
            }
            if (resolved == null) {
                messages += "Không tìm thấy thư mục \"${folder.path.ifEmpty { "." }}\" (${folder.name})"
                continue
            }
            val stack = ArrayDeque<Triple<FsEntry, String, Int>>()
            stack.addLast(Triple(resolved.entry, resolved.relativePath, 0))
            while (stack.isNotEmpty()) {
                ensureActive()
                val (dir, rel, depth) = stack.removeLast()
                val children = try {
                    listing(dir)
                } catch (e: IOException) {
                    complete = false
                    messages += "Không đọc được thư mục \"${rel.ifEmpty { "." }}\""
                    continue
                }
                val sidecars = HashMap<String, FsEntry>()
                MediaFiles.IMAGE_EXTENSIONS.forEach { ext ->
                    children.filter { !it.isDirectory && MediaFiles.isImage(it.name) && MediaFiles.extension(it.name) == ext }
                        .forEach { sidecars.getOrPut(MediaFiles.baseName(it.name).lowercase()) { it } }
                }
                for (child in children) {
                    val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                    if (child.isDirectory) {
                        if (!folder.recursive || MediaFiles.isHidden(child.name)) continue
                        if (depth < maxDepth) {
                            stack.addLast(Triple(child, childRel, depth + 1))
                        } else {
                            messages += "Bỏ qua thư mục quá sâu: $childRel"
                        }
                    } else if (MediaFiles.isVideo(child.name)) {
                        var builder = found[childRel]
                        if (builder == null) {
                            if (found.size >= maxVideos) {
                                limitHit = true
                                continue
                            }
                            builder = Builder(child, childRel, sidecars[MediaFiles.baseName(child.name).lowercase()])
                            found[childRel] = builder
                        }
                        builder.folderIds += folder.id
                        builder.audiences += folder.audience
                    }
                }
                onProgress(found.size)
            }
        }
        if (limitHit) messages += "Thư viện quá lớn: chỉ lập chỉ mục $maxVideos video đầu tiên"

        val videos = found.values.map { b ->
            ScannedVideo(
                id = videoId(fs.source.key, b.relativePath),
                relativePath = b.relativePath,
                title = MediaFiles.titleOf(b.entry.name),
                uri = b.entry.uri,
                size = b.entry.size,
                lastModified = b.entry.lastModified,
                sidecarUri = b.sidecar?.uri,
                folderIds = b.folderIds,
                audiences = b.audiences,
            )
        }
        ScanResult(videos, messages, complete && !limitHit)
    }

    companion object {
        /** Stable per source + path; never a list position. Same path on another USB = other id. */
        fun videoId(sourceKey: String, relativePath: String): String = sha256("$sourceKey|$relativePath").take(32)
    }
}
