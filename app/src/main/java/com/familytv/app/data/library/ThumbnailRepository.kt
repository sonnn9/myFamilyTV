package com.familytv.app.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.familytv.app.core.model.Video
import com.familytv.app.data.source.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Thumbnails: the image next to the video ("Bai hat ABC.jpg") wins; otherwise a frame is
 * extracted. Results are cached as small JPEGs in the app's own cache dir (never on the USB),
 * generated lazily for cards that are on screen, at most [MAX_PARALLEL] at a time, off the UI thread.
 */
class ThumbnailRepository(
    private val context: Context,
    private val onDuration: (videoId: String, durationMs: Long) -> Unit,
) {
    private val dir = File(context.cacheDir, "thumbs")
    private val generation = Semaphore(MAX_PARALLEL)
    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val writes = AtomicInteger()

    private val memory = object : LruCache<String, ImageBitmap>(memoryBudgetBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(video: Video): ImageBitmap? = memory.get(keyOf(video))

    suspend fun load(video: Video): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = keyOf(video)
        memory.get(key)?.let { return@withContext it }
        val file = File(dir, "$key.jpg")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { bmp ->
                file.setLastModified(System.currentTimeMillis())
                return@withContext bmp.asImageBitmap().also { memory.put(key, it) }
            }
        }
        if (key in failed) return@withContext null
        generation.withPermit {
            memory.get(key)?.let { return@withPermit it }
            val bitmap = generate(video)
            if (bitmap == null) {
                failed += key
                null
            } else {
                store(bitmap, file)
                bitmap.asImageBitmap().also { memory.put(key, it) }
            }
        }
    }

    @Volatile
    private var background: Pair<String, ImageBitmap>? = null

    /**
     * App background picture from the USB, scaled to at most 1920 px wide and cached as a JPEG in
     * the app's cache dir (only the current one is kept).
     */
    suspend fun loadBackground(uri: String, version: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = sha256("$uri|$version").take(24)
        background?.takeIf { it.first == key }?.let { return@withContext it.second }
        val bgDir = File(context.cacheDir, "background")
        val file = File(bgDir, "$key.jpg")
        val bitmap = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null) ?: decodeBackground(uri)?.also { bmp ->
            runCatching {
                bgDir.mkdirs()
                bgDir.listFiles()?.forEach { it.delete() }
                if (bgDir.usableSpace > MIN_FREE_BYTES) FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }
        } ?: return@withContext null
        bitmap.asImageBitmap().also { background = key to it }
    }

    private fun decodeBackground(uri: String): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= BG_MAX_WIDTH) sample *= 2
        val decoded = resolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        if (decoded.width <= BG_MAX_WIDTH) {
            decoded
        } else {
            val h = (decoded.height * BG_MAX_WIDTH.toFloat() / decoded.width).toInt()
            Bitmap.createScaledBitmap(decoded, BG_MAX_WIDTH, h, true).also { if (it !== decoded) decoded.recycle() }
        }
    }.getOrNull()

    fun clear() {
        memory.evictAll()
        failed.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun cacheSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun generate(video: Video): Bitmap? {
        val sidecar = video.sidecarImageUri?.let { decodeSidecar(it) }
        val needsFrame = sidecar == null
        val needsDuration = video.durationMs == null
        if (!needsFrame && !needsDuration) return sidecar
        val frame = extract(video, needsFrame)
        return sidecar ?: frame
    }

    private fun decodeSidecar(uri: String): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH && bounds.outHeight / (sample * 2) >= HEIGHT) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) }
        decoded?.let { centerCrop(it) }
    }.getOrNull()

    /** One retriever pass: duration (reported to the index) and, if asked, a frame. */
    private fun extract(video: Video, wantFrame: Boolean): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(video.uri))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (video.durationMs == null) onDuration(video.id, duration ?: -1L)
            if (!wantFrame) return null
            val atUs = when {
                duration == null || duration <= 0 -> 5_000_000L
                else -> (duration / 10).coerceIn(1_000L, 60_000L) * 1_000L
            }
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH * 2, HEIGHT * 2)
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.frameAtTime
            return frame?.let { centerCrop(it) }
        } catch (_: Exception) {
            if (video.durationMs == null) onDuration(video.id, -1L)
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun centerCrop(src: Bitmap): Bitmap {
        val scale = max(WIDTH / src.width.toFloat(), HEIGHT / src.height.toFloat())
        val cropW = (WIDTH / scale).toInt().coerceIn(1, src.width)
        val cropH = (HEIGHT / scale).toInt().coerceIn(1, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(cropped, WIDTH, HEIGHT, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        if (src !== scaled) src.recycle()
        return scaled
    }

    /** Writes the cache file unless the device is short on space (then memory-only). */
    private fun store(bitmap: Bitmap, file: File) {
        try {
            dir.mkdirs()
            if (dir.usableSpace < MIN_FREE_BYTES) return
            val tmp = File(dir, file.name + ".tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (!tmp.renameTo(file)) tmp.delete()
            if (writes.incrementAndGet() % 25 == 0) trim()
        } catch (_: Exception) {
            // Cache is best effort (e.g. storage full); the thumbnail still shows from memory.
        }
    }

    fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files) {
            if (total <= TRIM_TO_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun keyOf(v: Video) = "${v.id}_${v.lastModified}_${v.sizeBytes}_${if (v.sidecarImageUri != null) "s" else "f"}"

    private fun memoryBudgetBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val MAX_PARALLEL = 2
        const val MAX_CACHE_BYTES = 120L * 1024 * 1024
        const val TRIM_TO_BYTES = 90L * 1024 * 1024
        const val MIN_FREE_BYTES = 50L * 1024 * 1024
        const val BG_MAX_WIDTH = 1920
    }
}
