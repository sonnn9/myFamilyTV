package com.familytv.app.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.familytv.app.appContainer
import com.familytv.app.data.settings.RepeatMode
import com.familytv.app.ui.FtvDialog
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.components.FtvButton
import com.familytv.app.ui.components.Thumbnail
import com.familytv.app.ui.components.VideoCard
import com.familytv.app.ui.components.formatDuration
import com.familytv.app.ui.pin.PinDialog
import com.familytv.app.ui.pin.PinFlowKind
import com.familytv.app.ui.theme.Ftv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How far the bottom controls are pushed down while the "up next" shelf only peeks. */
private val ShelfPeekOffset = 185.dp

@OptIn(UnstableApi::class) // PlayerView.resizeMode / setShowBuffering
@Composable
fun PlayerScreen(vm: PlayerViewModel, onExit: () -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var seekFlash by remember { mutableIntStateOf(0) }
    var centerDown by remember { mutableStateOf(false) }
    var grantStep by remember { mutableIntStateOf(0) } // 0 none, 1 PIN, 2 choose minutes
    var wantShelf by remember { mutableStateOf(false) }
    var focusTick by remember { mutableIntStateOf(0) }
    var shelfFocused by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val shelfFocus = remember { FocusRequester() }
    val playing = state.phase == Phase.PLAYING
    val hasShelf = state.suggestions.isNotEmpty()

    /** Shows the controls; [shelf] = put focus straight into the "up next" shelf (Down key). */
    fun showControls(shelf: Boolean = false) {
        wantShelf = shelf && hasShelf
        controlsVisible = true
        interactions++
        focusTick++
    }

    fun hideControls() {
        controlsVisible = false
        shelfFocused = false
        wantShelf = false
    }

    // A new video / end screen: the next time the controls open, start on the play button.
    LaunchedEffect(state.phase, state.video?.id) {
        wantShelf = false
        shelfFocused = false
    }

    // A new player instance means we came back from Home/TV sleep, paused: show where we are.
    LaunchedEffect(player) {
        if (player != null) showControls()
    }
    // Auto-hide while playing; browsing the shelf gets more time.
    LaunchedEffect(controlsVisible, interactions, state.isPlaying, state.phase, shelfFocused) {
        if (controlsVisible && state.isPlaying && playing) {
            delay(if (shelfFocused) 12_000 else 5_000)
            hideControls()
        }
    }
    LaunchedEffect(controlsVisible, state.phase, focusTick) {
        withFrameNanos { }
        if (!playing) return@LaunchedEffect
        runCatching {
            when {
                !controlsVisible -> rootFocus.requestFocus()
                wantShelf -> shelfFocus.requestFocus()
                else -> playFocus.requestFocus()
            }
        }
    }

    BackHandler {
        when {
            state.phase == Phase.UP_NEXT -> vm.cancelUpNext()
            playing && controlsVisible && (state.isPlaying || shelfFocused) -> hideControls()
            else -> onExit()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val handled = handleKey(e, playing, controlsVisible, vm, ::showControls, { seekFlash++ }, { centerDown }, { centerDown = it })
                if (!handled && controlsVisible) interactions++ // any key keeps the controls on screen
                handled
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.keepScreenOn = state.isPlaying
            },
            modifier = Modifier.fillMaxSize(),
        )

        when (state.phase) {
            Phase.PLAYING -> {
                if (state.buffering) {
                    Text("Đang tải…", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                }
                if (controlsVisible) {
                    Controls(
                        state = state,
                        vm = vm,
                        playFocus = playFocus,
                        shelfFocus = shelfFocus,
                        shelfFocused = shelfFocused,
                        onShelfFocus = { shelfFocused = it },
                        onPick = { id ->
                            hideControls()
                            vm.playSuggestion(id)
                        },
                    )
                } else {
                    SeekFlash(seekFlash, state.positionMs, state.durationMs, Modifier.align(Alignment.BottomCenter))
                }
            }
            Phase.LOADING -> Text("Đang mở video…", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            Phase.ASK_RESUME -> Panel(state.video?.title.orEmpty(), "Lần trước đã xem đến ${formatDuration(state.resumeAtMs)}.") { first ->
                FtvButton("Xem tiếp từ ${formatDuration(state.resumeAtMs)}", { vm.resumeAt(state.resumeAtMs) }, Modifier.focusRequester(first), icon = AppIcons.Play, primary = true)
                FtvButton("Xem từ đầu", vm::restart, icon = AppIcons.Replay)
                FtvButton("Quay lại", onExit)
            }
            Phase.UP_NEXT -> UpNextCard(state, vm)
            Phase.ENDED -> Panel(
                title = "Đã xem xong",
                body = state.video?.title,
                extra = {
                    if (hasShelf) SuggestionShelf(state.suggestions, onPick = vm::playSuggestion, modifier = Modifier.padding(horizontal = 48.dp))
                },
            ) { first ->
                FtvButton("Xem lại", vm::restart, Modifier.focusRequester(first), icon = AppIcons.Replay, primary = true)
                if (state.hasNext) FtvButton("Video tiếp theo", vm::next, icon = AppIcons.SkipNext)
                FtvButton("Quay lại", onExit)
            }
            Phase.ERROR -> Panel("Không phát được video", state.message, icon = AppIcons.Warning) { first ->
                if (state.hasNext) FtvButton("Video tiếp theo", vm::next, Modifier.focusRequester(first), icon = AppIcons.SkipNext)
                FtvButton("Quay lại", onExit, if (state.hasNext) Modifier else Modifier.focusRequester(first), primary = !state.hasNext)
            }
            Phase.USB_LOST -> Panel("USB đã ngắt kết nối", "Video đã dừng. Hãy cắm lại USB rồi mở lại video.", icon = AppIcons.Warning) { first ->
                FtvButton("Quay lại", onExit, Modifier.focusRequester(first), primary = true)
            }
            Phase.NOT_ALLOWED -> Panel("Không thể phát", state.message, icon = AppIcons.Lock) { first ->
                FtvButton("Quay lại", onExit, Modifier.focusRequester(first), primary = true)
            }
            Phase.TIME_UP -> Panel(
                "Hết giờ xem rồi!",
                "Hôm nay con đã xem đủ thời gian cho phép. Hãy nghỉ ngơi và quay lại vào ngày mai nhé!",
                icon = AppIcons.Moon,
            ) { first ->
                FtvButton("Bố mẹ: thêm thời gian", { grantStep = 1 }, Modifier.focusRequester(first), icon = AppIcons.Lock)
                FtvButton("Quay lại", onExit)
            }
        }
    }

    GrantTimeFlow(grantStep, onStep = { grantStep = it })
}

private fun handleKey(
    e: KeyEvent,
    playing: Boolean,
    controlsVisible: Boolean,
    vm: PlayerViewModel,
    showControls: (shelf: Boolean) -> Unit,
    flashSeek: () -> Unit,
    centerDown: () -> Boolean,
    setCenterDown: (Boolean) -> Unit,
): Boolean {
    if (!playing || controlsVisible) return false
    val isCenter = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    if (e.type == KeyEventType.KeyDown) {
        val fast = e.nativeKeyEvent.repeatCount > 3
        when {
            e.key == Key.DirectionLeft -> { vm.seekBy(if (fast) -30_000 else -10_000); flashSeek(); return true }
            e.key == Key.DirectionRight -> { vm.seekBy(if (fast) 30_000 else 10_000); flashSeek(); return true }
            e.key == Key.DirectionDown -> { showControls(true); return true } // "up next" suggestions
            e.key == Key.DirectionUp -> { showControls(false); return true }
            isCenter -> { setCenterDown(true); return true }
        }
    }
    // Act on key-up only if the key-down happened here (the OK that opened the player is ignored).
    if (e.type == KeyEventType.KeyUp && isCenter && centerDown()) {
        setCenterDown(false)
        vm.togglePlayPause()
        showControls(false)
        return true
    }
    return false
}

@Composable
private fun Controls(
    state: PlayerUi,
    vm: PlayerViewModel,
    playFocus: FocusRequester,
    shelfFocus: FocusRequester,
    shelfFocused: Boolean,
    onShelfFocus: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    val hasShelf = state.suggestions.isNotEmpty()
    // The shelf peeks below the buttons; focusing it slides everything up (like YouTube TV).
    val offset by animateDpAsState(if (!hasShelf || shelfFocused) 0.dp else ShelfPeekOffset, label = "shelf")
    val scrim = if (shelfFocused) {
        Brush.verticalGradient(0f to Color(0x99000000), 0.35f to Color(0xCC000000), 1f to Color(0xF2000000))
    } else {
        Brush.verticalGradient(0f to Color(0xB3000000), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0xE6000000))
    }
    Box(Modifier.fillMaxSize().background(scrim)) {
        if (!shelfFocused) {
            Column(Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 28.dp)) {
                Text(
                    state.video?.title.orEmpty(),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(state.folderName, if (state.total > 1) "${state.index}/${state.total}" else null).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Ftv.TextSecondary, fontSize = 15.sp)
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .offset(y = offset)
                .padding(start = 48.dp, end = 48.dp, bottom = 20.dp),
        ) {
            SeekBar(state.positionMs, state.durationMs, onSeek = vm::seekBy)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)) {
                Text(formatDuration(state.positionMs), color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(if (state.durationMs > 0) formatDuration(state.durationMs) else "--:--", color = Color.White, fontSize = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                ControlButton(AppIcons.SkipPrevious, "Trước", vm::previous)
                ControlButton(AppIcons.FastRewind, "-10 giây", { vm.seekBy(-10_000) })
                ControlButton(
                    if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                    if (state.isPlaying) "Tạm dừng" else "Phát",
                    vm::togglePlayPause,
                    Modifier.focusRequester(playFocus),
                )
                ControlButton(AppIcons.FastForward, "+10 giây", { vm.seekBy(10_000) })
                ControlButton(AppIcons.SkipNext, "Tiếp", vm::next, enabled = state.hasNext)
                ControlButton(AppIcons.Replay, "Xem từ đầu", vm::restart)
                ControlButton(AppIcons.Favorite, if (state.isFavorite) "Bỏ thích" else "Yêu thích", vm::toggleFavorite, active = state.isFavorite)
                ControlButton(AppIcons.Shuffle, if (state.shuffle) "Ngẫu nhiên" else "Tuần tự", vm::toggleShuffle, active = state.shuffle)
                ControlButton(
                    if (state.repeat == RepeatMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                    when (state.repeat) {
                        RepeatMode.OFF -> "Không lặp"
                        RepeatMode.ALL -> "Lặp danh sách"
                        RepeatMode.ONE -> "Lặp 1 video"
                    },
                    vm::cycleRepeat,
                    active = state.repeat != RepeatMode.OFF,
                )
            }
            if (hasShelf) {
                Spacer(Modifier.height(18.dp))
                SuggestionShelf(state.suggestions, onPick, focus = shelfFocus, onFocusChanged = onShelfFocus)
            }
        }
    }
}

/** "Up next" row: rest of the current list, same folder, then other recommended videos. */
@Composable
private fun SuggestionShelf(
    items: List<Suggestion>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    Column(modifier) {
        Text("Video tiếp theo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .focusRestorer()
                .focusGroup(),
            // Room for the focused card's 8% zoom so its border is not clipped.
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items, key = { it.video.id }) { s ->
                VideoCard(video = s.video, onClick = { onPick(s.video.id) }, progress = s.progress, width = 200.dp)
            }
        }
    }
}

/** End of a video with autoplay / repeat-all on: "Up next in 5" with play-now and cancel. */
@Composable
private fun UpNextCard(state: PlayerUi, vm: PlayerViewModel) {
    val next = state.upNext ?: return
    val first = remember { FocusRequester() }
    LaunchedEffect(next.id) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(48.dp)
                .background(Ftv.Surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(240.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                Thumbnail(next, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tiếp theo sau ${state.countdown} giây", color = Ftv.TextSecondary, fontSize = 16.sp)
                Text(next.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FtvButton("Phát ngay", vm::playUpNext, Modifier.focusRequester(first), icon = AppIcons.Play, primary = true)
                    FtvButton("Hủy", vm::cancelUpNext)
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(56.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0x33FFFFFF),
                contentColor = if (active) Ftv.Primary else Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = if (active) Ftv.Primary else Color.Black,
                disabledContainerColor = Color(0x14FFFFFF),
                disabledContentColor = Color(0x66FFFFFF),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ftv.Primary), shape = CircleShape)),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp).align(Alignment.Center))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (focused) Color.White else Color(0x99FFFFFF),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Focusable progress bar: Left/Right seek by 10 s while it has focus. */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onSeek(-10_000); true }
                    Key.DirectionRight -> { onSeek(10_000); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 8.dp else 5.dp
        Box(Modifier.fillMaxWidth().height(barHeight).background(Color(0x55FFFFFF), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(Ftv.Primary, RoundedCornerShape(4.dp)))
        if (focused) {
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - 9.dp)
                    .size(18.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/** Short progress hint while seeking with the controls hidden. */
@Composable
private fun SeekFlash(trigger: Int, positionMs: Long, durationMs: Long, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        visible = true
        delay(1_500)
        visible = false
    }
    if (!visible) return
    Column(modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 48.dp, vertical = 18.dp)) {
        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Ftv.Primary))
        }
        Text(
            "${formatDuration(positionMs)} / ${if (durationMs > 0) formatDuration(durationMs) else "--:--"}",
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Centered message with buttons; the first button receives focus. [extra] goes below the buttons. */
@Composable
private fun Panel(
    title: String,
    body: String?,
    icon: ImageVector? = null,
    extra: @Composable () -> Unit = {},
    buttons: @Composable (first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(title, body) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xD9000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Ftv.Warning, modifier = Modifier.size(64.dp))
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (!body.isNullOrEmpty()) {
                Text(body, color = Ftv.TextSecondary, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { buttons(first) }
            extra()
        }
    }
}

@Composable
private fun GrantTimeFlow(step: Int, onStep: (Int) -> Unit) {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    when (step) {
        1 -> PinDialog(PinFlowKind.VERIFY, "Nhập PIN để thêm thời gian xem", container.pins, onDismiss = { onStep(0) }, onSuccess = { onStep(2) })
        2 -> FtvDialog("Thêm thời gian xem hôm nay", { onStep(0) }) { first ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(15, 30, 60).forEachIndexed { i, minutes ->
                    FtvButton("+$minutes phút", {
                        scope.launch { container.watchTime.grant(minutes) }
                        onStep(0)
                    }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            FtvButton("Đóng", { onStep(0) })
        }
    }
}
