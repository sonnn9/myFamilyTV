package com.familytv.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.familytv.app.core.catalog.ModeView
import com.familytv.app.core.catalog.PlayContext
import com.familytv.app.core.catalog.SearchResults
import com.familytv.app.core.config.FolderConfig
import com.familytv.app.core.model.Video
import com.familytv.app.core.model.ViewMode
import com.familytv.app.data.library.LibraryStatus
import com.familytv.app.ui.Screen
import com.familytv.app.ui.UiController
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.components.Chip
import com.familytv.app.ui.components.FtvButton
import com.familytv.app.ui.components.MessagePanel
import com.familytv.app.ui.components.MoreCard
import com.familytv.app.ui.components.SectionTitle
import com.familytv.app.ui.components.Thumbnail
import com.familytv.app.ui.components.VideoCard
import com.familytv.app.ui.rememberFocus
import com.familytv.app.ui.theme.Ftv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val HOME_ROW_LIMIT = 30
private val ScreenPadding = 24.dp

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(view: ModeView, status: LibraryStatus, ui: UiController) {
    val listState = rememberLazyListState()
    val recommended = remember(view) { view.recommended(HOME_ROW_LIMIT) }
    // A mixed "recommended" shelf only makes sense when there is more than one folder to mix.
    val showRecommended = remember(view) {
        view.folders.count { view.videosIn(it.id).isNotEmpty() } >= 2 && recommended.size >= 4
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 64.dp),
    ) {
        item(key = "header") { HomeHeader(view, status, ui) }
        if (showRecommended) {
            item(key = "row:recommended") {
                VideoRow("Đề xuất cho bạn", recommended, PlayContext.Recommended, view, ui)
            }
        }
        if (view.continueWatching.isNotEmpty()) {
            item(key = "row:continue") {
                VideoRow("Xem tiếp", view.continueWatching.take(HOME_ROW_LIMIT).map { it.video }, PlayContext.ContinueWatching, view, ui)
            }
        }
        items(view.folders, key = { "row:${it.id}" }) { folder ->
            val videos = view.videosIn(folder.id)
            if (videos.isEmpty()) {
                EmptyRow(folder.name)
            } else {
                VideoRow(
                    title = folder.name,
                    videos = videos.take(HOME_ROW_LIMIT),
                    context = PlayContext.Folder(folder.id),
                    view = view,
                    ui = ui,
                    moreCount = videos.size.takeIf { it > HOME_ROW_LIMIT },
                    onMore = { ui.nav.push(Screen.Category(folder.id)) },
                )
            }
        }
        if (view.folders.isEmpty()) {
            item(key = "empty") {
                MessagePanel(
                    "Chưa có danh mục cho chế độ ${view.mode.label}",
                    "config.json chưa khai báo thư mục nào dành cho chế độ này.",
                )
            }
        } else if (view.videoCount == 0 && !status.scanning) {
            item(key = "empty") {
                MessagePanel(
                    "Chưa có video",
                    "Các thư mục khai báo trong config.json đang trống hoặc không tồn tại. Bố mẹ hãy kiểm tra USB và bấm \"Quét lại thư viện\".",
                )
            }
        }
    }
}

/**
 * Logo + name like a regular video app. In Kids mode nothing hints at filtering or limits;
 * the mode switch and config warnings only appear in the parents' modes (and in Settings).
 */
@Composable
private fun HomeHeader(view: ModeView, status: LibraryStatus, ui: UiController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(AppIcons.Logo, contentDescription = null, modifier = Modifier.size(width = 40.dp, height = 28.dp))
            Spacer(Modifier.width(10.dp))
            Text(view.appName, color = Ftv.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        if (view.mode != ViewMode.KIDS) {
            FtvButton(
                text = "Chế độ: ${view.mode.label}",
                onClick = ui::chooseMode,
                icon = AppIcons.Lock,
                modifier = Modifier.rememberFocus("mode"),
            )
            if (status.config.lastFailure != null) {
                Chip("config.json mới có lỗi – đang dùng bản trước", color = Color(0x33FFC857), textColor = Ftv.Warning)
            }
        }
        if (status.scanning) Chip("Đang cập nhật…")
    }
}

@Composable
fun VideoRow(
    title: String,
    videos: List<Video>,
    context: PlayContext,
    view: ModeView,
    ui: UiController,
    moreCount: Int? = null,
    onMore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        SectionTitle(title, Modifier.padding(start = ScreenPadding))
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(videos, key = { it.id }) { v ->
                VideoCard(
                    video = v,
                    onClick = { ui.play(v, context) },
                    progress = view.progressOf(v.id),
                    isFavorite = view.isFavorite(v.id),
                    onLongClick = { ui.toggleFavorite(v) },
                    modifier = Modifier.rememberFocus("${context.encode()}/${v.id}"),
                )
            }
            if (moreCount != null && onMore != null) {
                item(key = "more") { MoreCard(moreCount, onMore, Modifier.rememberFocus("${context.encode()}/more")) }
            }
        }
    }
}

@Composable
private fun EmptyRow(title: String) {
    Column(Modifier.fillMaxWidth().padding(start = ScreenPadding, top = 10.dp, bottom = 14.dp)) {
        SectionTitle(title)
        Spacer(Modifier.height(8.dp))
        Text("Chưa có video trong mục này.", color = Ftv.TextMuted, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Grids
// ---------------------------------------------------------------------------------------------

@Composable
private fun VideoGrid(
    videos: List<Video>,
    context: PlayContext,
    view: ModeView,
    ui: UiController,
    keyPrefix: String = "",
    header: LazyGridScope.() -> Unit = {},
    footer: LazyGridScope.() -> Unit = {},
) {
    LazyVerticalGrid(
        state = rememberLazyGridState(),
        columns = GridCells.Adaptive(200.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        header()
        videoItems(videos, context, view, ui, keyPrefix)
        footer()
    }
}

private fun LazyGridScope.videoItems(videos: List<Video>, context: PlayContext, view: ModeView, ui: UiController, keyPrefix: String) {
    items(videos, key = { keyPrefix + it.id }) { v ->
        VideoCard(
            video = v,
            onClick = { ui.play(v, context) },
            progress = view.progressOf(v.id),
            isFavorite = view.isFavorite(v.id),
            onLongClick = { ui.toggleFavorite(v) },
            width = null,
            modifier = Modifier.rememberFocus("${context.encode()}/${v.id}"),
        )
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun PageTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(text, color = Ftv.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Ftv.TextSecondary, fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchScreen(view: ModeView, ui: UiController) {
    var query by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        applied = query
    }
    // Searches only the mode-filtered view; runs off the main thread.
    val results by produceState(SearchResults("", emptyList(), emptyList()), view, applied) {
        value = withContext(Dispatchers.Default) { view.search(applied) }
    }
    val context = PlayContext.Search(applied)
    VideoGrid(
        videos = if (applied.isBlank()) emptyList() else results.videos,
        context = context,
        view = view,
        ui = ui,
        header = {
            fullWidth("title") {
                PageTitle("Tìm kiếm", if (view.mode == ViewMode.KIDS) null else "Chỉ tìm trong nội dung của chế độ ${view.mode.label}.")
            }
            fullWidth("field") { SearchField(query, onValueChange = { query = it }, modifier = Modifier.rememberFocus("field")) }
            when {
                applied.isBlank() -> fullWidth("hint") {
                    Text(
                        "Gõ tên video hoặc tên danh mục. Có thể gõ không dấu, ví dụ \"bai hat\", \"dong vat\".",
                        color = Ftv.TextSecondary, fontSize = 15.sp,
                    )
                }
                results.query != applied -> fullWidth("busy") { Text("Đang tìm…", color = Ftv.TextSecondary, fontSize = 15.sp) }
                results.isEmpty -> fullWidth("none") {
                    MessagePanel("Không tìm thấy", "Không có video hay danh mục nào khớp với \"$applied\".", icon = AppIcons.Search)
                }
                else -> {
                    if (results.folders.isNotEmpty()) {
                        fullWidth("folders") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionTitle("Danh mục")
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                                ) {
                                    items(results.folders, key = { it.id }) { f ->
                                        FtvButton(
                                            f.name,
                                            onClick = { ui.nav.push(Screen.Category(f.id)) },
                                            icon = AppIcons.Folder,
                                            modifier = Modifier.rememberFocus("folder/${f.id}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    fullWidth("count") { SectionTitle("${results.videos.size} video") }
                }
            }
        },
    )
}

/** Text field for the TV's system keyboard; OK opens the keyboard, Down leaves the field. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(80)) },
        singleLine = true,
        textStyle = TextStyle(color = Ftv.TextPrimary, fontSize = 20.sp),
        cursorBrush = SolidColor(Ftv.Primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
            autoCorrectEnabled = false,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            focusManager.moveFocus(FocusDirection.Down)
        }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (focused) Ftv.SurfaceHigh else Ftv.Surface, shape)
                    .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Color.White else Ftv.TextMuted), shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, contentDescription = null, tint = Ftv.TextSecondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            if (focused) "Bấm OK để mở bàn phím" else "Tìm video…",
                            color = Ftv.TextMuted, fontSize = 20.sp,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------------------------

@Composable
fun CategoriesScreen(view: ModeView, ui: UiController) {
    if (view.folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MessagePanel("Chưa có danh mục", "config.json chưa khai báo thư mục nào cho chế độ ${view.mode.label}.")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(240.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val modeNote = if (view.mode == ViewMode.KIDS) "" else " · chế độ ${view.mode.label}"
        fullWidth("title") { PageTitle("Danh mục", "${view.folders.size} danh mục$modeNote") }
        items(view.folders, key = { it.id }) { folder ->
            FolderTile(folder, view.videosIn(folder.id), { ui.nav.push(Screen.Category(folder.id)) }, Modifier.rememberFocus(folder.id))
        }
    }
}

@Composable
private fun FolderTile(folder: FolderConfig, videos: List<Video>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ftv.SurfaceHigh, focusedContainerColor = Ftv.SurfaceHigh),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
    ) {
        videos.firstOrNull()?.let { Thumbnail(it, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(folder.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${videos.size} video", color = Ftv.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
fun CategoryScreen(view: ModeView, folderId: String, ui: UiController) {
    val folder = view.folder(folderId)
    if (folder == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MessagePanel("Danh mục không khả dụng", "Danh mục này không thuộc chế độ ${view.mode.label}.") {
                FtvButton("Quay lại", { ui.nav.pop() }, primary = true)
            }
        }
        return
    }
    val videos = view.videosIn(folder.id)
    VideoGrid(videos, PlayContext.Folder(folder.id), view, ui, header = {
        fullWidth("title") { PageTitle(folder.name, "${videos.size} video") }
        if (videos.isEmpty()) fullWidth("empty") { Text("Chưa có video trong mục này.", color = Ftv.TextMuted, fontSize = 15.sp) }
    })
}

// ---------------------------------------------------------------------------------------------
// Favorites & continue watching
// ---------------------------------------------------------------------------------------------

@Composable
fun FavoritesScreen(view: ModeView, ui: UiController) {
    if (view.favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MessagePanel(
                "Chưa có video yêu thích",
                "Khi đang xem, chọn nút ♥ trên thanh điều khiển – hoặc giữ phím OK trên một video – để thêm vào Yêu thích.",
                icon = AppIcons.Favorite,
            )
        }
        return
    }
    VideoGrid(view.favorites, PlayContext.Favorites, view, ui, header = {
        fullWidth("title") { PageTitle("Yêu thích", "Giữ phím OK trên một video để thêm/bỏ yêu thích.") }
    })
}

@Composable
fun ContinueScreen(view: ModeView, ui: UiController) {
    val watching = view.continueWatching.map { it.video }
    val finished = view.history.filter { it.progress.completed }.map { it.video }
    if (watching.isEmpty() && finished.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MessagePanel("Chưa có lịch sử xem", "Các video đang xem dở sẽ hiện ở đây.", icon = AppIcons.History)
        }
        return
    }
    VideoGrid(
        videos = watching,
        context = PlayContext.ContinueWatching,
        view = view,
        ui = ui,
        keyPrefix = "w:",
        header = {
            fullWidth("title") { PageTitle("Xem tiếp") }
            if (watching.isNotEmpty()) fullWidth("watching") { SectionTitle("Đang xem dở") }
        },
        footer = {
            if (finished.isNotEmpty()) {
                fullWidth("finished") { SectionTitle("Đã xem xong gần đây", Modifier.padding(top = 12.dp)) }
                videoItems(finished, PlayContext.History, view, ui, "h:")
            }
        },
    )
}
