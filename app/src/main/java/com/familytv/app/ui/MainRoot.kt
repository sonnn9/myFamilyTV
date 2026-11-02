package com.familytv.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.familytv.app.AppContainer
import com.familytv.app.appContainer
import com.familytv.app.core.catalog.ModeView
import com.familytv.app.data.library.BackgroundImage
import com.familytv.app.core.model.ViewMode
import com.familytv.app.data.library.LibraryStatus
import com.familytv.app.data.library.SourceState
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.components.FtvButton
import com.familytv.app.ui.components.MessagePanel
import com.familytv.app.ui.parent.ParentScreen
import com.familytv.app.ui.screens.CategoriesScreen
import com.familytv.app.ui.screens.CategoryScreen
import com.familytv.app.ui.screens.ContinueScreen
import com.familytv.app.ui.screens.FavoritesScreen
import com.familytv.app.ui.screens.HomeScreen
import com.familytv.app.ui.screens.SearchScreen
import com.familytv.app.ui.theme.Ftv

private val MenuCollapsed = 72.dp
private val MenuExpanded = 224.dp

@Composable
fun MainRoot(container: AppContainer, activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    val nav = remember { NavState() }
    val ui = remember { UiController(container, activity, nav, scope) }
    val focusMemory = remember { FocusMemory() }
    val view by container.library.view.collectAsStateWithLifecycle()
    val status by container.library.status.collectAsStateWithLifecycle()
    val timeUp by container.kidsTimeUp.collectAsStateWithLifecycle()
    val mode by container.session.mode.collectAsStateWithLifecycle()
    val parentUnlocked by container.session.parentUnlocked.collectAsStateWithLifecycle()
    val holder = rememberSaveableStateHolder()
    val contentFocus = remember { FocusRequester() }
    val screen = nav.current

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    // Entering a screen (menu, Back, start) or the content being swapped under the focused item
    // (USB removed/back, mode changed, parent area unlocked): focus the remembered card, else the
    // first item, instead of letting focus fall back to the side menu.
    LaunchedEffect(screen.key, view == null, view?.mode, parentUnlocked) {
        withFrameNanos { }
        withFrameNanos { }
        if (!focusMemory.restore(screen.key)) runCatching { contentFocus.requestFocus() }
    }

    CompositionLocalProvider(LocalFocusMemory provides focusMemory) {
        Box(Modifier.fillMaxSize().background(Ftv.Background)) {
            AppBackground(status.background)
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(MenuCollapsed))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(contentFocus)
                        .focusRestorer()
                        .focusGroup(),
                ) {
                    holder.SaveableStateProvider(screen.key) {
                        CompositionLocalProvider(LocalScreenKey provides screen.key) {
                            ScreenContent(screen, view, status, ui)
                        }
                    }
                }
            }
            SideMenu(
                selected = screen.menu,
                mode = mode,
                appName = view?.appName ?: "Family TV",
                onSelect = { dest -> if (dest != screen.menu || nav.stack.size > 2) nav.openRoot(dest) else runCatching { contentFocus.requestFocus() } },
                modifier = Modifier.align(Alignment.CenterStart).zIndex(1f),
            )
            ToastHost(ui.toast, Modifier.align(Alignment.BottomCenter))
        }
        if (timeUp) {
            BreakScreen(container, onGrant = ui::grantTime, onChangeMode = ui::chooseMode, onExit = { activity.finish() })
        }
        DialogHost(ui)
    }
}

/** FamilyTV/background.jpg from the USB, darkened toward the bottom so rows stay readable. */
@Composable
private fun AppBackground(background: BackgroundImage?) {
    val thumbnails = LocalContext.current.appContainer.thumbnails
    val image by produceState<ImageBitmap?>(null, background) {
        value = background?.let { thumbnails.loadBackground(it.uri, it.version) }
    }
    val bitmap = image ?: return
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Ftv.Background.copy(alpha = 0.45f),
                    0.35f to Ftv.Background.copy(alpha = 0.72f),
                    1f to Ftv.Background.copy(alpha = 0.92f),
                ),
            ),
    )
}

@Composable
private fun ScreenContent(screen: Screen, view: ModeView?, status: LibraryStatus, ui: UiController) {
    when (screen) {
        Screen.Parent -> ParentScreen(ui)
        else -> {
            if (view == null) {
                LibraryStatusPage(status, ui)
                return
            }
            when (screen) {
                Screen.Home -> HomeScreen(view, status, ui)
                Screen.Search -> SearchScreen(view, ui)
                Screen.Categories -> CategoriesScreen(view, ui)
                is Screen.Category -> CategoryScreen(view, screen.folderId, ui)
                Screen.Favorites -> FavoritesScreen(view, ui)
                Screen.Continue -> ContinueScreen(view, ui)
                Screen.Parent -> Unit
            }
        }
    }
}

/** Shown instead of content whenever the library cannot be shown safely. */
@Composable
private fun LibraryStatusPage(status: LibraryStatus, ui: UiController) {
    val openParent = { ui.nav.openRoot(MenuDest.PARENT) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            status.state == SourceState.LOADING || status.state == SourceState.CHECKING -> MessagePanel(
                "Đang kiểm tra USB…", "Vui lòng chờ trong giây lát.",
            )
            status.state == SourceState.NO_SOURCE -> MessagePanel(
                "Chào mừng đến Family TV",
                "Bố mẹ cần thiết lập lần đầu: tạo PIN bố mẹ, sau đó chọn thư mục FamilyTV trên USB.",
                icon = AppIcons.Lock,
            ) { FtvButton("Bắt đầu thiết lập", openParent, primary = true) }
            status.state == SourceState.NOT_CONNECTED -> MessagePanel(
                "Chưa thấy USB",
                "Hãy cắm USB có thư mục FamilyTV vào TV. App sẽ tự nhận khi USB sẵn sàng.",
                icon = AppIcons.Warning,
            ) {
                FtvButton("Thử lại", { ui.container.library.refreshAsync() }, primary = true)
                FtvButton("Cài đặt", openParent, icon = AppIcons.Settings)
            }
            status.state == SourceState.PERMISSION_LOST -> MessagePanel(
                "Mất quyền đọc USB",
                "TV không còn cho app đọc thư mục đã chọn (USB khác, hoặc quyền đã bị thu hồi). Bố mẹ hãy chọn lại thư mục FamilyTV.",
                icon = AppIcons.Warning,
            ) {
                FtvButton("Thử lại", { ui.container.library.refreshAsync() })
                FtvButton("Cài đặt", openParent, icon = AppIcons.Settings, primary = true)
            }
            status.config.active == null -> MessagePanel(
                "Chưa có cấu hình hợp lệ",
                "config.json bị thiếu hoặc có lỗi nên app chưa hiển thị nội dung nào. Bố mẹ xem chi tiết lỗi trong Cài đặt.",
                icon = AppIcons.Warning,
            ) { FtvButton("Cài đặt", openParent, icon = AppIcons.Settings, primary = true) }
            else -> MessagePanel("Đang tải thư viện…", "Vui lòng chờ trong giây lát.")
        }
    }
}

@Composable
private fun SideMenu(selected: MenuDest, mode: ViewMode, appName: String, onSelect: (MenuDest) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) MenuExpanded else MenuCollapsed, label = "menu")
    val selectedRequester = remember { FocusRequester() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(listOf(Color(0xF2121318), Color(0xE6121318)))
                else Brush.horizontalGradient(listOf(Color(0xCC0F0F0F), Color(0x660F0F0F))),
            )
            .onFocusChanged { expanded = it.hasFocus }
            // Entering the menu always lands on the current page's item.
            .focusProperties { onEnter = { selectedRequester.requestFocus() } }
            .focusGroup()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 18.dp)) {
            Image(AppIcons.Logo, contentDescription = null, modifier = Modifier.size(width = 36.dp, height = 25.dp))
            if (expanded) {
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(appName, color = Ftv.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    // Kids mode shows no hint that the app is filtered.
                    if (mode != ViewMode.KIDS) Text("Chế độ: ${mode.label}", color = Ftv.Warning, fontSize = 12.sp)
                }
            }
        }
        MenuDest.entries.forEach { dest ->
            if (dest == MenuDest.PARENT) Spacer(Modifier.height(18.dp))
            MenuItem(
                dest = dest,
                selected = dest == selected,
                expanded = expanded,
                onClick = { onSelect(dest) },
                modifier = if (dest == selected) Modifier.focusRequester(selectedRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun MenuItem(dest: MenuDest, selected: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ftv.SurfaceHigh else Color.Transparent,
            contentColor = if (selected) Ftv.Primary else Ftv.TextSecondary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Ftv.Primary), shape = shape)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(24.dp))
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(dest.label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToastHost(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text,
        color = Color.White,
        fontSize = 16.sp,
        modifier = modifier
            .padding(bottom = 36.dp)
            .background(Color(0xE6262932), RoundedCornerShape(12.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
}
