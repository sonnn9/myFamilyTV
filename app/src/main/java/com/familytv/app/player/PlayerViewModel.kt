package com.familytv.app.player

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.familytv.app.appContainer
import com.familytv.app.core.catalog.ModeView
import com.familytv.app.core.catalog.PlayContext
import com.familytv.app.core.model.ProgressRecord
import com.familytv.app.core.model.Video
import com.familytv.app.core.model.ViewMode
import com.familytv.app.data.library.SourceState
import com.familytv.app.data.settings.PlaybackPrefs
import com.familytv.app.data.settings.RepeatMode
import com.familytv.app.data.source.SourceAvailability
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase { LOADING, ASK_RESUME, PLAYING, UP_NEXT, ENDED, ERROR, USB_LOST, TIME_UP, NOT_ALLOWED }

/** A card in the player's "up next" shelf (already filtered by the current mode). */
data class Suggestion(val video: Video, val progress: ProgressRecord?)

data class PlayerUi(
    val phase: Phase = Phase.LOADING,
    val video: Video? = null,
    val folderName: String? = null,
    val index: Int = 0,
    val total: Int = 0,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val resumeAtMs: Long = 0,
    val message: String? = null,
    val isFavorite: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val suggestions: List<Suggestion> = emptyList(),
    val upNext: Video? = null,
    val countdown: Int = 0,
)

/**
 * Playback state that survives the activity being stopped (Home, TV sleep): the ExoPlayer itself
 * is created in onStart and released in onStop, the queue and position live here.
 * Every video is re-checked against the current mode right before it is loaded.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.appContainer
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var started = false
    private var context: PlayContext = PlayContext.Single
    private var mode: ViewMode = ViewMode.KIDS
    private var queue: List<Video> = emptyList()
    private var order: List<Int> = emptyList()
    private var cursor = 0
    private var pendingStartMs = 0L
    private var pendingPlay = true
    private var loadedId: String? = null
    private var countdownJob: Job? = null

    fun start(videoId: String, encodedContext: String?) {
        if (started) return
        started = true
        context = PlayContext.decode(encodedContext)
        viewModelScope.launch {
            val prefs = c.settings.playbackPrefs.first()
            _ui.update { it.copy(shuffle = prefs.shuffle, repeat = prefs.repeat) }
            val view = c.library.awaitView()
            if (view == null) {
                _ui.update {
                    it.copy(phase = Phase.ERROR, message = "Thư viện chưa sẵn sàng (USB chưa kết nối hoặc chưa có cấu hình hợp lệ).")
                }
                return@launch
            }
            mode = view.mode
            val video = view.video(videoId)
            if (video == null) {
                _ui.update { it.copy(phase = Phase.NOT_ALLOWED, message = "Video này không có trong chế độ ${view.mode.label}.") }
                return@launch
            }
            queue = view.queue(context, videoId)
            buildOrder(videoId)
            observeEnvironment()
            startTicker()
            if (c.kidsTimeUp.value) {
                _ui.update { it.copy(phase = Phase.TIME_UP, video = video) }
                return@launch
            }
            val progress = view.progressOf(videoId)
            if (c.rules.value.resumePlayback && progress != null && !progress.completed && progress.positionMs > RESUME_MIN_MS) {
                publishCurrent(view, Phase.ASK_RESUME)
                _ui.update { it.copy(resumeAtMs = progress.positionMs) }
            } else {
                prepareCurrent(0L, play = true)
            }
        }
    }

    // --- lifecycle (called by PlayerActivity onStart/onStop) ------------------------------

    fun attach() {
        if (_player.value != null) return
        val p = ExoPlayer.Builder(getApplication())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        p.addListener(listener)
        _player.value = p
        loadedId = null
        if (_ui.value.phase == Phase.PLAYING) current()?.let { load(p, it, pendingStartMs, pendingPlay) }
    }

    fun detach() {
        // Leaving the app during an "up next" countdown must not start the next video in the background.
        if (_ui.value.phase == Phase.UP_NEXT) cancelUpNext()
        val p = _player.value ?: return
        if (_ui.value.phase == Phase.PLAYING && loadedId != null) {
            pendingStartMs = p.currentPosition
            pendingPlay = false // after Home/sleep, wait for the viewer to press OK
            saveProgress(p)
        }
        p.removeListener(listener)
        p.release()
        _player.value = null
        loadedId = null
        _ui.update { it.copy(isPlaying = false, buffering = false) }
        viewModelScope.launch { c.watchTime.flush() }
    }

    override fun onCleared() {
        _player.value?.release()
        _player.value = null
    }

    // --- user actions -------------------------------------------------------------------------

    fun togglePlayPause() {
        val p = _player.value ?: return
        if (_ui.value.phase != Phase.PLAYING) return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (blockedByTimeLimit()) return
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val p = _player.value ?: return
        if (_ui.value.phase != Phase.PLAYING) return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0L, duration)
        p.seekTo(target)
        _ui.update { it.copy(positionMs = target) }
    }

    fun next() {
        val n = nextCursor() ?: return
        _player.value?.let { saveProgress(it) }
        cursor = n
        prepareCurrent(resumePositionFor(current()), play = true)
    }

    fun previous() {
        val p = _player.value
        if (p != null && _ui.value.phase == Phase.PLAYING && p.currentPosition > 5_000) {
            p.seekTo(0)
            return
        }
        val prev = previousCursor() ?: run { p?.seekTo(0); return }
        p?.let { saveProgress(it) }
        cursor = prev
        prepareCurrent(resumePositionFor(current()), play = true)
    }

    fun restart() {
        val p = _player.value
        if (_ui.value.phase == Phase.PLAYING && p != null && loadedId == current()?.id) {
            p.seekTo(0)
            if (!blockedByTimeLimit()) p.play()
        } else {
            prepareCurrent(0L, play = true)
        }
    }

    fun resumeAt(positionMs: Long) = prepareCurrent(positionMs, play = true)

    /** Play a video picked from the "up next" shelf or the end screen. */
    fun playSuggestion(videoId: String) {
        val view = c.library.view.value ?: return
        val video = view.video(videoId) ?: return // not allowed in this mode: ignore
        countdownJob?.cancel()
        _player.value?.let { saveProgress(it) }
        val inQueue = queue.indexOfFirst { it.id == videoId }
        if (inQueue >= 0) {
            cursor = order.indexOf(inQueue).coerceAtLeast(0)
        } else {
            // Continue in the picked video's own folder, like opening it from that row.
            context = video.folderIds.firstOrNull { view.folder(it) != null }?.let { PlayContext.Folder(it) } ?: PlayContext.Single
            queue = view.queue(context, videoId)
            buildOrder(videoId)
        }
        prepareCurrent(resumePositionFor(video), play = true)
    }

    /** "Up next" countdown: play now. */
    fun playUpNext() {
        countdownJob?.cancel()
        val n = nextCursor() ?: return cancelUpNext()
        cursor = n
        prepareCurrent(resumePositionFor(current()), play = true)
    }

    /** "Up next" countdown: stay on the end screen. */
    fun cancelUpNext() {
        countdownJob?.cancel()
        _ui.update { it.copy(phase = Phase.ENDED, upNext = null, countdown = 0) }
    }

    private fun startUpNext() {
        val next = nextCursor()?.let { order.getOrNull(it) }?.let { queue.getOrNull(it) } ?: return cancelUpNext()
        countdownJob?.cancel()
        _ui.update { it.copy(phase = Phase.UP_NEXT, upNext = next, countdown = UP_NEXT_SECONDS, isPlaying = false) }
        countdownJob = viewModelScope.launch {
            for (s in UP_NEXT_SECONDS downTo 1) {
                _ui.update { it.copy(countdown = s) }
                delay(1_000)
            }
            playUpNext()
        }
    }

    fun toggleFavorite() {
        val v = current() ?: return
        viewModelScope.launch {
            c.library.toggleFavorite(v.id)?.let { fav -> _ui.update { it.copy(isFavorite = fav) } }
        }
    }

    fun toggleShuffle() {
        val shuffle = !_ui.value.shuffle
        _ui.update { it.copy(shuffle = shuffle) }
        current()?.let { buildOrder(it.id) }
        refreshNavigation()
        savePrefs()
    }

    fun cycleRepeat() {
        val next = when (_ui.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _ui.update { it.copy(repeat = next) }
        refreshNavigation()
        savePrefs()
    }

    /** Remote media keys (play/pause, FF/RW, next/previous). Returns true if handled. */
    fun onMediaKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (_player.value?.isPlaying == false) togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> _player.value?.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-30_000)
            KeyEvent.KEYCODE_MEDIA_NEXT -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
            else -> return false
        }
        return true
    }

    // --- internals ------------------------------------------------------------------------------

    private fun current(): Video? = order.getOrNull(cursor)?.let { queue.getOrNull(it) }

    private fun buildOrder(currentId: String) {
        val start = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        order = if (_ui.value.shuffle && queue.size > 1) {
            listOf(start) + (queue.indices - start).shuffled()
        } else {
            queue.indices.toList()
        }
        cursor = order.indexOf(start).coerceAtLeast(0)
    }

    private fun nextCursor(): Int? = when {
        cursor + 1 < order.size -> cursor + 1
        _ui.value.repeat == RepeatMode.ALL && order.isNotEmpty() -> 0
        else -> null
    }

    private fun previousCursor(): Int? = when {
        cursor > 0 -> cursor - 1
        _ui.value.repeat == RepeatMode.ALL && order.size > 1 -> order.lastIndex
        else -> null
    }

    private fun refreshNavigation() {
        _ui.update { it.copy(hasNext = nextCursor() != null, hasPrevious = previousCursor() != null, index = cursor + 1, total = order.size) }
    }

    private fun resumePositionFor(video: Video?): Long {
        if (video == null || !c.rules.value.resumePlayback) return 0L
        val p = c.library.view.value?.progressOf(video.id) ?: return 0L
        return if (!p.completed && p.positionMs > RESUME_MIN_MS) p.positionMs else 0L
    }

    private fun blockedByTimeLimit(): Boolean {
        if (!c.kidsTimeUp.value) return false
        _player.value?.pause()
        _ui.update { it.copy(phase = Phase.TIME_UP, isPlaying = false) }
        return true
    }

    private fun publishCurrent(view: ModeView, phase: Phase) {
        val v = current() ?: return
        val folder = (context as? PlayContext.Folder)?.let { view.folder(it.folderId)?.name }
            ?: v.folderIds.firstNotNullOfOrNull { view.folder(it)?.name }
        _ui.update {
            it.copy(
                phase = phase,
                video = v,
                folderName = folder,
                message = null,
                isFavorite = view.isFavorite(v.id),
                durationMs = v.durationMs ?: 0L,
                positionMs = 0L,
                upNext = null,
                countdown = 0,
                suggestions = suggestionsFor(view, v),
            )
        }
        refreshNavigation()
    }

    /** What comes next in this queue (in play order), then related videos from the filtered library. */
    private fun suggestionsFor(view: ModeView, current: Video): List<Suggestion> {
        val picked = LinkedHashMap<String, Video>()
        val upcoming = (cursor + 1 until order.size) + (0 until cursor)
        upcoming.mapNotNull { order.getOrNull(it)?.let { i -> queue.getOrNull(i) } }
            .filter { view.video(it.id) != null }
            .forEach { v -> picked.getOrPut(v.id) { v } }
        view.related(current.id).forEach { v -> picked.getOrPut(v.id) { v } }
        picked.remove(current.id)
        return picked.values.take(MAX_SUGGESTIONS).map { Suggestion(it, view.progressOf(it.id)) }
    }

    /** Loads the current queue entry after re-checking mode and time limit. */
    private fun prepareCurrent(startMs: Long, play: Boolean) {
        val v = current() ?: return
        val view = c.library.view.value
        if (view == null || view.video(v.id) == null) {
            stopPlayer()
            _ui.update { it.copy(phase = Phase.NOT_ALLOWED, message = "Video này không có trong chế độ hiện tại.", isPlaying = false) }
            return
        }
        publishCurrent(view, Phase.PLAYING)
        if (blockedByTimeLimit()) return
        pendingStartMs = startMs
        pendingPlay = play
        _ui.update { it.copy(positionMs = startMs) }
        _player.value?.let { load(it, v, startMs, play) }
    }

    private fun load(p: ExoPlayer, video: Video, startMs: Long, play: Boolean) {
        p.setMediaItem(MediaItem.Builder().setUri(video.uri).setMediaId(video.id).build(), startMs)
        p.prepare()
        p.playWhenReady = play
        loadedId = video.id
    }

    private fun stopPlayer() {
        _player.value?.let {
            saveProgress(it)
            it.stop()
        }
        loadedId = null
    }

    private fun saveProgress(p: Player) {
        val v = current() ?: return
        if (loadedId != v.id) return
        val position = p.currentPosition
        val duration = p.duration.takeIf { it > 0 } ?: v.durationMs ?: 0L
        viewModelScope.launch { c.library.saveProgress(v.id, position, duration) }
    }

    private fun savePrefs() {
        val s = _ui.value
        viewModelScope.launch { runCatching { c.settings.setPlaybackPrefs(PlaybackPrefs(s.shuffle, s.repeat)) } }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) _player.value?.let { saveProgress(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = _player.value ?: return
            _ui.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_READY && p.duration > 0) {
                _ui.update { it.copy(durationMs = p.duration) }
                current()?.let { v -> if (v.durationMs == null) c.library.reportDuration(v.id, p.duration) }
            }
            if (playbackState == Player.STATE_ENDED) onEnded(p)
        }

        override fun onPlayerError(error: PlaybackException) {
            onError(error)
        }

        // ExoPlayer silently drops a video track the TV cannot decode and keeps playing the audio
        // (e.g. AV1 on TVs without an AV1 decoder). Treat that as an unsupported format instead.
        override fun onTracksChanged(tracks: Tracks) {
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (videoGroups.isEmpty()) return // audio-only file
            val playable = videoGroups.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }
            if (!playable) onUnsupportedVideo(videoGroups.first())
        }
    }

    @OptIn(UnstableApi::class) // getTrackSupport / C.FORMAT_EXCEEDS_CAPABILITIES
    private fun onUnsupportedVideo(group: Tracks.Group) {
        val format = group.getTrackFormat(0)
        val tooBig = (0 until group.length).any { group.getTrackSupport(it) == C.FORMAT_EXCEEDS_CAPABILITIES }
        _player.value?.let {
            it.pause()
            it.stop()
        }
        loadedId = null
        val codec = codecLabel(format)
        val reason = if (tooBig) {
            "Video $codec ${format.width}×${format.height} vượt quá khả năng giải mã của TV này."
        } else {
            "TV này không có bộ giải mã hình cho định dạng $codec nên chỉ phát được tiếng."
        }
        _ui.update {
            it.copy(
                phase = Phase.ERROR,
                isPlaying = false,
                message = "$reason Hãy chuyển video sang MP4 (H.264 + AAC), ví dụ bằng tools/convert_for_tv.ps1.",
            )
        }
    }

    @OptIn(UnstableApi::class) // MimeTypes.VIDEO_VP8 / VIDEO_VP9
    private fun codecLabel(format: Format): String = when (format.sampleMimeType) {
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_H265 -> "H.265/HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        else -> format.sampleMimeType ?: format.codecs ?: "không rõ"
    }

    private fun onEnded(p: ExoPlayer) {
        val v = current() ?: return
        val duration = p.duration.takeIf { it > 0 } ?: v.durationMs ?: 0L
        viewModelScope.launch { c.library.saveProgress(v.id, duration, duration) }
        when {
            _ui.value.repeat == RepeatMode.ONE -> {
                p.seekTo(0)
                if (!blockedByTimeLimit()) p.play()
            }
            (_ui.value.repeat == RepeatMode.ALL || c.rules.value.autoplay) && nextCursor() != null -> startUpNext()
            else -> _ui.update { it.copy(phase = Phase.ENDED, isPlaying = false) }
        }
    }

    private fun onError(error: PlaybackException) {
        _player.value?.let { saveProgress(it) }
        loadedId = null
        viewModelScope.launch {
            val availability = c.library.recheckAvailability()
            if (availability != SourceAvailability.AVAILABLE) {
                _player.value?.stop()
                _ui.update { it.copy(phase = Phase.USB_LOST, message = "USB đã ngắt kết nối", isPlaying = false) }
            } else {
                _ui.update { it.copy(phase = Phase.ERROR, message = describe(error), isPlaying = false) }
            }
        }
    }

    private fun observeEnvironment() {
        // USB removed / permission lost while the player is open.
        viewModelScope.launch {
            c.library.status.map { it.state }.distinctUntilChanged().collect { state ->
                val lost = state == SourceState.NOT_CONNECTED || state == SourceState.PERMISSION_LOST
                if (lost && _ui.value.phase in setOf(Phase.LOADING, Phase.ASK_RESUME, Phase.PLAYING, Phase.UP_NEXT, Phase.ENDED)) {
                    countdownJob?.cancel()
                    stopPlayer()
                    _ui.update { it.copy(phase = Phase.USB_LOST, message = "USB đã ngắt kết nối", isPlaying = false) }
                }
            }
        }
        // Mode changed (e.g. app went to background and came back in Kids mode): rebuild or stop.
        viewModelScope.launch {
            c.library.view.filterNotNull().collect { view ->
                val v = current()
                if (view.mode != mode) {
                    mode = view.mode
                    if (v == null || view.video(v.id) == null) {
                        countdownJob?.cancel()
                        stopPlayer()
                        _ui.update {
                            it.copy(phase = Phase.NOT_ALLOWED, isPlaying = false, message = "Đã chuyển sang chế độ ${view.mode.label}; video này không được phép xem.")
                        }
                        return@collect
                    }
                    queue = view.queue(context, v.id)
                    buildOrder(v.id)
                    refreshNavigation()
                }
                if (v != null) {
                    _ui.update { it.copy(isFavorite = view.isFavorite(v.id), suggestions = suggestionsFor(view, v)) }
                }
            }
        }
        // Parent granted more time on the break screen: continue where we stopped.
        viewModelScope.launch {
            c.kidsTimeUp.collect { up ->
                if (up && _ui.value.phase == Phase.PLAYING) {
                    blockedByTimeLimit()
                } else if (!up && _ui.value.phase == Phase.TIME_UP) {
                    val p = _player.value
                    val v = current()
                    if (p != null && v != null && loadedId == v.id) {
                        _ui.update { it.copy(phase = Phase.PLAYING) }
                        p.play()
                    } else {
                        prepareCurrent(maxOf(pendingStartMs, resumePositionFor(v)), play = true)
                    }
                }
            }
        }
    }

    /** Position/progress refresh, progress saving and kids watch-time accounting. */
    private fun startTicker() {
        viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var lastSave = last
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val delta = now - last
                last = now
                val p = _player.value ?: continue
                _ui.update {
                    it.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = if (p.duration > 0) p.duration else it.durationMs)
                }
                if (!p.isPlaying || _ui.value.phase != Phase.PLAYING) continue
                if (c.session.mode.value == ViewMode.KIDS) {
                    // Only real playback time counts; delta is clamped inside DailyQuota.
                    val exhausted = c.watchTime.addPlayed(delta, c.rules.value.limitOrNull)
                    if (exhausted) {
                        p.pause()
                        saveProgress(p)
                        _ui.update { it.copy(phase = Phase.TIME_UP, isPlaying = false) }
                        continue
                    }
                }
                if (now - lastSave >= SAVE_EVERY_MS) {
                    saveProgress(p)
                    lastSave = now
                }
            }
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Không tìm thấy file video. File có thể đã bị xóa hoặc đổi tên trên USB – bố mẹ hãy \"Quét lại thư viện\"."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "App không có quyền đọc file này trên USB."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "TV không hỗ trợ định dạng (codec) của video này. Nên chuyển video sang MP4 (H.264 + AAC)."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "TV không phát được âm thanh của video này."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "Định dạng file này không được hỗ trợ. Nên dùng MP4 (H.264 + AAC)."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "File video bị hỏng hoặc chưa được chép xong."
        in 2000..2999 -> "Lỗi khi đọc file từ USB (${error.errorCodeName})."
        else -> "Không phát được video này (${error.errorCodeName})."
    }

    private companion object {
        const val SEEK_MS = 10_000L
        const val TICK_MS = 500L
        const val SAVE_EVERY_MS = 5_000L
        const val RESUME_MIN_MS = 5_000L
        const val UP_NEXT_SECONDS = 5
        const val MAX_SUGGESTIONS = 20
    }
}
