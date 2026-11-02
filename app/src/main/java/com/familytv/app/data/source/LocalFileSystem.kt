package com.familytv.app.data.source

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Fallback for TVs without a usable folder picker: plain file access to a directory found on a
 * mounted volume. Needs READ_EXTERNAL_STORAGE (Android 6–10) or "All files access" (11+).
 * Every listed entry is checked to stay inside the chosen root (no symlink escapes).
 */
class LocalFileSystem(rootPath: String) : LibraryFileSystem {
    private val rootDir = File(rootPath)
    override val source = LibrarySourceRef(LibrarySourceRef.Type.FILE, rootDir.absolutePath)
    private val rootCanonical: String by lazy {
        runCatching { rootDir.canonicalPath }.getOrDefault(rootDir.absolutePath)
    }

    override fun root() = entryOf(rootDir).copy(name = "")

    override fun availability(): SourceAvailability = when {
        !rootDir.exists() -> SourceAvailability.NOT_CONNECTED
        !rootDir.canRead() || rootDir.list() == null -> SourceAvailability.PERMISSION_LOST
        else -> SourceAvailability.AVAILABLE
    }

    override fun list(dir: FsEntry): List<FsEntry> {
        val directory = File(dir.ref)
        if (!isInsideRoot(directory)) throw IOException("Thư mục nằm ngoài thư viện")
        val files = directory.listFiles() ?: throw IOException("Không đọc được thư mục ${directory.path}")
        return files.filter(::isInsideRoot).map(::entryOf)
    }

    override fun open(entry: FsEntry): InputStream {
        val file = File(entry.ref)
        if (!isInsideRoot(file)) throw IOException("File nằm ngoài thư viện")
        return FileInputStream(file)
    }

    private fun isInsideRoot(file: File): Boolean = runCatching {
        val c = file.canonicalPath
        c == rootCanonical || c.startsWith(rootCanonical + File.separator)
    }.getOrDefault(false)

    private fun entryOf(f: File) = FsEntry(
        name = f.name,
        isDirectory = f.isDirectory,
        size = if (f.isFile) f.length() else 0L,
        lastModified = f.lastModified(),
        uri = Uri.fromFile(f).toString(),
        ref = f.absolutePath,
    )
}

data class VolumeInfo(val label: String, val directory: File, val removable: Boolean) {
    val familyTvDir: File? get() = directory.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("FamilyTV", true) }
}

/** Finds mounted storage volumes for the fallback folder browser (no hard-coded volume ids). */
object StorageVolumes {
    fun discover(context: Context): List<VolumeInfo> {
        val found = LinkedHashMap<String, VolumeInfo>()
        if (Build.VERSION.SDK_INT >= 24) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes.orEmpty().forEach { v ->
                val state = v.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val dir = directoryOf(v) ?: return@forEach
                found[dir.absolutePath] = VolumeInfo(v.getDescription(context) ?: dir.name, dir, v.isRemovable)
            }
        }
        // Mount points of public volumes are listed under /storage on Android 6+.
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "emulated" && f.name != "self" && f.canRead()) {
                found.getOrPut(f.absolutePath) { VolumeInfo("Bộ nhớ ${f.name}", f, true) }
            }
        }
        return found.values.sortedByDescending { it.removable }
    }

    @RequiresApi(24)
    private fun directoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return volume.directory
        return try {
            StorageVolume::class.java.getMethod("getPathFile").invoke(volume) as? File
        } catch (_: Throwable) {
            volume.uuid?.let { File("/storage", it) }
        }
    }
}
