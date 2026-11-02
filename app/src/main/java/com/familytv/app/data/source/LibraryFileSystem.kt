package com.familytv.app.data.source

import com.familytv.app.core.config.FolderCheck
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where the library lives: a SAF tree URI (preferred) or a directory path (fallback). */
data class LibrarySourceRef(val type: Type, val value: String) {
    enum class Type { SAF, FILE }

    /** Stable id of this source. SAF tree URIs and /storage paths both contain the USB volume id,
     *  so two USB sticks with identical folder layouts get different keys. */
    val key: String get() = "${type.name}:$value"

    val hash: String get() = sha256(key).take(16)

    fun displayName(): String = when (type) {
        Type.SAF -> android.net.Uri.decode(value.substringAfterLast("/tree/", value))
        Type.FILE -> value
    }
}

enum class SourceAvailability { AVAILABLE, NOT_CONNECTED, PERMISSION_LOST }

/** A file or directory inside the library. [ref] is a SAF document id or an absolute path. */
data class FsEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val uri: String,
    val ref: String,
)

/** Read-only access to the library. Implementations never write to the USB. */
interface LibraryFileSystem {
    val source: LibrarySourceRef

    fun availability(): SourceAvailability

    fun root(): FsEntry

    @Throws(IOException::class)
    fun list(dir: FsEntry): List<FsEntry>

    @Throws(IOException::class)
    fun open(entry: FsEntry): InputStream
}

object FsPaths {
    const val CONFIG_FILE = "config.json"

    data class Resolved(val entry: FsEntry, val relativePath: String)

    /**
     * Walks a normalized relative path segment by segment using real directory listings, so the
     * result can never leave the granted root and uses the on-disk spelling of each name
     * (exact match first, then case-insensitive as on FAT/exFAT).
     */
    fun resolveDir(
        fs: LibraryFileSystem,
        relativePath: String,
        listing: (FsEntry) -> List<FsEntry> = fs::list,
    ): Resolved? {
        var current = fs.root()
        val parts = mutableListOf<String>()
        if (relativePath.isEmpty()) return Resolved(current, "")
        for (segment in relativePath.split('/')) {
            val children = listing(current)
            val next = findChild(children, segment) ?: return null
            if (!next.isDirectory) return null
            current = next
            parts += next.name
        }
        return Resolved(current, parts.joinToString("/"))
    }

    fun checkFolder(fs: LibraryFileSystem, relativePath: String): FolderCheck {
        var current = fs.root()
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            val next = findChild(fs.list(current), segment) ?: return FolderCheck.MISSING
            if (!next.isDirectory) return FolderCheck.NOT_A_DIRECTORY
            current = next
        }
        return FolderCheck.OK
    }

    /** Optional app background: FamilyTV/background.jpg (or .jpeg/.png/.webp). */
    val BACKGROUND_FILES = listOf("background.jpg", "background.jpeg", "background.png", "background.webp")

    fun findBackground(fs: LibraryFileSystem): FsEntry? {
        val children = fs.list(fs.root()).filter { !it.isDirectory }
        return BACKGROUND_FILES.firstNotNullOfOrNull { name -> children.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    }

    fun findChild(children: List<FsEntry>, name: String): FsEntry? =
        children.firstOrNull { it.name == name } ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun readConfig(fs: LibraryFileSystem, maxChars: Int): String {
        val entry = findChild(fs.list(fs.root()), CONFIG_FILE)?.takeIf { !it.isDirectory }
            ?: throw java.io.FileNotFoundException("Không tìm thấy $CONFIG_FILE trong thư mục đã chọn")
        if (entry.size > maxChars * 4L) throw IOException("$CONFIG_FILE quá lớn")
        return fs.open(entry).use { stream ->
            val bytes = stream.readBytesLimited(maxChars * 4)
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException("${CONFIG_FILE} quá lớn")
        }
        return out.toByteArray()
    }
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
