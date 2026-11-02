package com.familytv.app.data.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import com.familytv.app.core.catalog.LibraryCatalog
import com.familytv.app.core.catalog.ModeView
import com.familytv.app.core.config.ConfigManager
import com.familytv.app.core.config.ConfigParser
import com.familytv.app.core.config.ConfigStatus
import com.familytv.app.core.config.FolderCheck
import com.familytv.app.core.config.LibraryConfig
import com.familytv.app.core.model.Audience
import com.familytv.app.core.model.FavoriteRecord
import com.familytv.app.core.model.ProgressRecord
import com.familytv.app.core.model.Video
import com.familytv.app.core.session.SessionManager
import com.familytv.app.data.db.FavoriteEntity
import com.familytv.app.data.db.LibraryDao
import com.familytv.app.data.db.ProgressEntity
import com.familytv.app.data.db.VideoEntity
import com.familytv.app.data.settings.ScanInfo
import com.familytv.app.data.settings.SettingsStore
import com.familytv.app.data.source.FsPaths
import com.familytv.app.data.source.LibraryFileSystem
import com.familytv.app.data.source.LibrarySourceRef
import com.familytv.app.data.source.LocalFileSystem
import com.familytv.app.data.source.SafFileSystem
import com.familytv.app.data.source.SourceAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

enum class SourceState { LOADING, NO_SOURCE, CHECKING, AVAILABLE, NOT_CONNECTED, PERMISSION_LOST }

/** Background picture found on the USB; [version] changes when the file is replaced. */
data class BackgroundImage(val uri: String, val version: String)

data class LibraryStatus(
    val source: LibrarySourceRef? = null,
    val state: SourceState = SourceState.LOADING,
    val config: ConfigStatus = ConfigStatus(),
    val scanning: Boolean = false,
    val scanCount: Int = 0,
    val lastScan: ScanInfo? = null,
    val scanMessages: List<String> = emptyList(),
    /** One-off information for the parent area (e.g. permission could not be persisted). */
    val notice: String? = null,
    val background: BackgroundImage? = null,
)

/**
 * Owns the library: source access, config.json, the Room index and the mode-filtered [view].
 * The UI and the player only ever read [view]; there is no unfiltered path to videos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: LibraryDao,
    session: SessionManager,
    private val scope: CoroutineScope,
) {
    private val configManager = ConfigManager(settings)
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null

    private val _status = MutableStateFlow(LibraryStatus())
    val status: StateFlow<LibraryStatus> = _status.asStateFlow()

    private val source = MutableStateFlow<LibrarySourceRef?>(null)

    private val videoRows: Flow<List<VideoEntity>> =
        source.flatMapLatest { s -> if (s == null) flowOf(emptyList()) else dao.observeVideos(s.key) }
    private val favoriteRows: Flow<List<FavoriteEntity>> =
        source.flatMapLatest { s -> if (s == null) flowOf(emptyList()) else dao.observeFavorites(s.key) }
    private val progressRows: Flow<List<ProgressEntity>> =
        source.flatMapLatest { s -> if (s == null) flowOf(emptyList()) else dao.observeProgress(s.key) }

    private val activeConfig: Flow<LibraryConfig?> = _status.map { it.config.active }.distinctUntilChanged()
    private val available: Flow<Boolean> = _status.map { it.state == SourceState.AVAILABLE }.distinctUntilChanged()

    private val catalog: Flow<LibraryCatalog?> = combine(videoRows, activeConfig) { rows, config ->
        config?.let { LibraryCatalog(it, rows.map(::toVideo)) }
    }

    /** Library as the current mode may see it; null when nothing may be shown (no USB / no valid config). */
    val view: StateFlow<ModeView?> = combine(
        catalog, session.mode, favoriteRows, progressRows, available,
    ) { catalog, mode, favorites, progress, isAvailable ->
        if (catalog == null || !isAvailable) null
        else catalog.forMode(
            mode,
            favorites.map { FavoriteRecord(it.videoId, it.addedAt) },
            progress.map { ProgressRecord(it.videoId, it.positionMs, it.durationMs, it.completed, it.updatedAt) },
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    fun start() {
        scope.launch {
            val ref = settings.source.first()
            source.value = ref
            val lastScan = settings.scanInfo.first()
            if (ref == null) {
                _status.value = LibraryStatus(state = SourceState.NO_SOURCE, lastScan = lastScan)
            } else {
                _status.value = LibraryStatus(ref, SourceState.CHECKING, configManager.restore(ref.key), lastScan = lastScan)
                refresh(rescan = true)
            }
        }
        registerMountReceiver()
    }

    /** Waits briefly for the first filtered view (player started right after process restart). */
    suspend fun awaitView(timeoutMs: Long = 8_000): ModeView? =
        view.value ?: withTimeoutOrNull(timeoutMs) { view.filterNotNull().first() }

    fun refreshAsync(rescan: Boolean = true) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh(rescan) }
    }

    /** Re-checks the USB, re-reads config.json (keeping the last valid one on errors) and rescans. */
    suspend fun refresh(rescan: Boolean = true) = refreshMutex.withLock {
        val ref = source.value
        if (ref == null) {
            _status.update { it.copy(state = SourceState.NO_SOURCE, scanning = false) }
            return@withLock
        }
        val fs = fileSystemFor(ref)
        val availability = checkAvailability(fs)
        if (availability != SourceAvailability.AVAILABLE) {
            val restored = configManager.restore(ref.key)
            _status.update { it.copy(state = availability.toState(), config = restored.copy(lastFailure = it.config.lastFailure)) }
            return@withLock
        }
        val background = withContext(Dispatchers.IO) {
            runCatching { FsPaths.findBackground(fs) }.getOrNull()
                ?.let { BackgroundImage(it.uri, "${it.lastModified}_${it.size}") }
        }
        _status.update { it.copy(state = SourceState.AVAILABLE, background = background) }

        val text = withContext(Dispatchers.IO) {
            runCatching { FsPaths.readConfig(fs, ConfigParser.MAX_CONFIG_CHARS) }
        }
        val configStatus = text.fold(
            onSuccess = { json ->
                configManager.applyCandidate(ref.key, json) { folder ->
                    withContext(Dispatchers.IO) {
                        runCatching { FsPaths.checkFolder(fs, folder.path) }.getOrDefault(FolderCheck.MISSING)
                    }
                }
            },
            onFailure = { e -> configManager.reportReadFailure(ref.key, readErrorMessage(e)) },
        )
        _status.update { it.copy(config = configStatus) }
        val config = configStatus.active ?: return@withLock
        if (rescan) scan(fs, config)
    }

    private suspend fun scan(fs: LibraryFileSystem, config: LibraryConfig) {
        val key = fs.source.key
        _status.update { it.copy(scanning = true, scanCount = 0) }
        try {
            val result = LibraryScanner(fs).scan(config) { n -> _status.update { s -> s.copy(scanCount = n) } }
            // If the USB disappeared during the scan, keep the old index untouched.
            val stillThere = checkAvailability(fs) == SourceAvailability.AVAILABLE
            if (!stillThere) {
                _status.update { it.copy(scanning = false, state = SourceState.NOT_CONNECTED) }
                return
            }
            val token = System.currentTimeMillis()
            val existing = dao.videos(key).associateBy { it.id }
            val rows = result.videos.map { v ->
                val old = existing[v.id]
                val unchanged = old != null && old.sizeBytes == v.size && old.lastModified == v.lastModified
                VideoEntity(
                    id = v.id, sourceKey = key, relativePath = v.relativePath, title = v.title, uri = v.uri,
                    sizeBytes = v.size, lastModified = v.lastModified,
                    durationMs = if (unchanged) old!!.durationMs else 0L,
                    sidecarUri = v.sidecarUri,
                    folderIds = v.folderIds.joinToString(","),
                    audiences = v.audiences.joinToString(",") { it.key },
                    scanToken = token,
                )
            }
            val removed = if (result.complete) {
                dao.applyScan(key, token, rows)
            } else {
                rows.chunked(500).forEach { dao.upsertVideos(it) } // partial scan: add/update only
                0
            }
            dao.deleteOtherSourceVideos(key)
            val summary = buildString {
                append("${rows.size} video")
                if (removed > 0) append(", gỡ $removed video không còn trên USB")
                if (!result.complete) append(" (quét chưa trọn vẹn, giữ nguyên dữ liệu cũ)")
            }
            val info = ScanInfo(System.currentTimeMillis(), summary)
            runCatching { settings.setScanInfo(info) }
            _status.update { it.copy(scanning = false, lastScan = info, scanMessages = result.messages) }
        } catch (e: CancellationException) {
            _status.update { it.copy(scanning = false) }
            throw e
        } catch (e: Exception) {
            _status.update { it.copy(scanning = false, scanMessages = listOf("Quét thư viện thất bại: ${e.message}")) }
            recheckAvailability()
        }
    }

    /** Cheap check used on USB broadcasts, app foreground and player errors. */
    suspend fun recheckAvailability(): SourceAvailability {
        val ref = source.value ?: return SourceAvailability.NOT_CONNECTED
        val availability = checkAvailability(fileSystemFor(ref))
        val previous = _status.value.state
        _status.update { it.copy(state = availability.toState()) }
        if (availability == SourceAvailability.AVAILABLE && previous != SourceState.AVAILABLE) {
            refreshAsync(rescan = true)
        }
        return availability
    }

    // --- Choosing a source (parent area) ------------------------------------------------------

    /** Folder picked in the system picker. Returns an error message, or null on success. */
    suspend fun selectSafFolder(uri: Uri): String? {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persisted = try {
            resolver.takePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        }
        val fs = SafFileSystem(context, uri)
        val hasConfig = withContext(Dispatchers.IO) {
            runCatching { FsPaths.findChild(fs.list(fs.root()), FsPaths.CONFIG_FILE) != null }.getOrDefault(false)
        }
        if (!hasConfig) {
            if (persisted) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
            return "Thư mục vừa chọn không có ${FsPaths.CONFIG_FILE}. Hãy chọn đúng thư mục FamilyTV trên USB."
        }
        resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, flags) }
        }
        val notice = if (persisted) null else
            "TV không cho lưu quyền truy cập lâu dài. Sau khi TV khởi động lại có thể cần chọn lại thư mục."
        switchSource(fs.source, notice)
        return null
    }

    /** Folder chosen in the in-app browser (fallback without system picker). */
    suspend fun selectLocalFolder(dir: File): String? {
        val fs = LocalFileSystem(dir.absolutePath)
        val ok = withContext(Dispatchers.IO) {
            fs.availability() == SourceAvailability.AVAILABLE &&
                runCatching { FsPaths.findChild(fs.list(fs.root()), FsPaths.CONFIG_FILE) != null }.getOrDefault(false)
        }
        if (!ok) return "Không đọc được ${FsPaths.CONFIG_FILE} trong thư mục này."
        switchSource(fs.source, null)
        return null
    }

    private suspend fun switchSource(ref: LibrarySourceRef, notice: String?) {
        val previous = source.value
        settings.setSource(ref)
        if (previous?.key != ref.key) runCatching { settings.clearValid() }
        source.value = ref
        _status.value = LibraryStatus(ref, SourceState.CHECKING, notice = notice, lastScan = _status.value.lastScan)
        refresh(rescan = true)
    }

    fun fileSystem(): LibraryFileSystem? = source.value?.let(::fileSystemFor)

    // --- Favorites, progress, durations ------------------------------------------------------

    /** Only videos visible in the current mode can be (un)favorited. */
    suspend fun toggleFavorite(videoId: String): Boolean? {
        val ref = source.value ?: return null
        val v = view.value ?: return null
        v.video(videoId) ?: return null
        return if (v.isFavorite(videoId)) {
            dao.deleteFavorite(videoId)
            false
        } else {
            dao.upsertFavorite(FavoriteEntity(videoId, ref.key, System.currentTimeMillis()))
            true
        }
    }

    suspend fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        val ref = source.value ?: return
        if (durationMs <= 0 && positionMs <= 0) return
        val completed = durationMs > 0 && (positionMs >= durationMs - 10_000 || positionMs >= durationMs * 95 / 100)
        runCatching {
            dao.upsertProgress(
                ProgressEntity(videoId, ref.key, positionMs.coerceAtLeast(0), durationMs, completed, System.currentTimeMillis()),
            )
        }
    }

    suspend fun clearHistory() {
        source.value?.let { dao.clearProgress(it.key) }
    }

    private val pendingDurations = ConcurrentHashMap<String, Long>()
    private var durationFlush: Job? = null

    /** Batched so that reading many durations does not rebuild the catalog for each one. */
    fun reportDuration(videoId: String, durationMs: Long) {
        pendingDurations[videoId] = if (durationMs > 0) durationMs else -1L
        if (durationFlush?.isActive == true) return
        durationFlush = scope.launch {
            delay(2_000)
            val batch = HashMap(pendingDurations)
            batch.keys.forEach { pendingDurations.remove(it) }
            runCatching { dao.applyDurations(batch) }
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun fileSystemFor(ref: LibrarySourceRef): LibraryFileSystem = when (ref.type) {
        LibrarySourceRef.Type.SAF -> SafFileSystem(context, Uri.parse(ref.value))
        LibrarySourceRef.Type.FILE -> LocalFileSystem(ref.value)
    }

    private suspend fun checkAvailability(fs: LibraryFileSystem): SourceAvailability =
        withContext(Dispatchers.IO) { runCatching { fs.availability() }.getOrDefault(SourceAvailability.NOT_CONNECTED) }

    private fun SourceAvailability.toState() = when (this) {
        SourceAvailability.AVAILABLE -> SourceState.AVAILABLE
        SourceAvailability.NOT_CONNECTED -> SourceState.NOT_CONNECTED
        SourceAvailability.PERMISSION_LOST -> SourceState.PERMISSION_LOST
    }

    private fun readErrorMessage(e: Throwable): String = when (e) {
        is FileNotFoundException -> e.message ?: "Không tìm thấy ${FsPaths.CONFIG_FILE}"
        is SecurityException -> "Mất quyền đọc USB"
        else -> "Không đọc được ${FsPaths.CONFIG_FILE}: ${e.message}"
    }

    private fun toVideo(e: VideoEntity) = Video(
        id = e.id,
        relativePath = e.relativePath,
        title = e.title,
        uri = e.uri,
        sizeBytes = e.sizeBytes,
        lastModified = e.lastModified,
        durationMs = e.durationMs.takeIf { it > 0 },
        sidecarImageUri = e.sidecarUri,
        folderIds = e.folderIds.split(',').filter { it.isNotEmpty() }.toSet(),
        audiences = e.audiences.split(',').mapNotNull { Audience.fromKey(it) }.toSet(),
    )

    private fun registerMountReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                scope.launch {
                    if (intent.action == Intent.ACTION_MEDIA_MOUNTED) {
                        delay(1_500) // let the provider see the new volume
                        recheckAvailability()
                    } else {
                        // EJECT arrives while the volume is still mounted; check now and again shortly.
                        delay(300)
                        recheckAvailability()
                        delay(2_000)
                        recheckAvailability()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
