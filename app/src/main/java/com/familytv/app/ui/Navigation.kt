package com.familytv.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.ComponentActivity
import com.familytv.app.AppContainer
import com.familytv.app.core.catalog.PlayContext
import com.familytv.app.core.model.Video
import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.pin.PinProof
import com.familytv.app.player.PlayerActivity
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.pin.PinFlowKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MenuDest(val label: String, val icon: ImageVector) {
    HOME("Trang chủ", AppIcons.Home),
    SEARCH("Tìm kiếm", AppIcons.Search),
    CATEGORIES("Danh mục", AppIcons.Folder),
    FAVORITES("Yêu thích", AppIcons.Favorite),
    CONTINUE("Xem tiếp", AppIcons.History),
    // Shown as a plain "Settings" entry; the parent area behind it is PIN protected.
    PARENT("Cài đặt", AppIcons.Settings),
}

sealed interface Screen {
    val key: String
    val menu: MenuDest

    data object Home : Screen {
        override val key = "home"
        override val menu = MenuDest.HOME
    }

    data object Search : Screen {
        override val key = "search"
        override val menu = MenuDest.SEARCH
    }

    data object Categories : Screen {
        override val key = "categories"
        override val menu = MenuDest.CATEGORIES
    }

    data class Category(val folderId: String) : Screen {
        override val key get() = "category:$folderId"
        override val menu get() = MenuDest.CATEGORIES
    }

    data object Favorites : Screen {
        override val key = "favorites"
        override val menu = MenuDest.FAVORITES
    }

    data object Continue : Screen {
        override val key = "continue"
        override val menu = MenuDest.CONTINUE
    }

    data object Parent : Screen {
        override val key = "parent"
        override val menu = MenuDest.PARENT
    }
}

/** Simple back stack; Home is always at the bottom so Back from any page returns to it. */
@Stable
class NavState {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun openRoot(dest: MenuDest) {
        val target = when (dest) {
            MenuDest.HOME -> Screen.Home
            MenuDest.SEARCH -> Screen.Search
            MenuDest.CATEGORIES -> Screen.Categories
            MenuDest.FAVORITES -> Screen.Favorites
            MenuDest.CONTINUE -> Screen.Continue
            MenuDest.PARENT -> Screen.Parent
        }
        stack.clear()
        stack.add(Screen.Home)
        if (target != Screen.Home) stack.add(target)
    }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

/**
 * Remembers which item was focused on each screen so that coming back (Back from a category,
 * from the player) puts focus on the same card.
 */
class FocusMemory {
    private val lastFocused = HashMap<String, String>()
    private val requesters = HashMap<String, FocusRequester>()

    fun register(screen: String, item: String, requester: FocusRequester) {
        requesters["$screen|$item"] = requester
    }

    fun unregister(screen: String, item: String, requester: FocusRequester) {
        val k = "$screen|$item"
        if (requesters[k] === requester) requesters.remove(k)
    }

    fun onFocused(screen: String, item: String) {
        lastFocused[screen] = item
    }

    fun restore(screen: String): Boolean {
        val item = lastFocused[screen] ?: return false
        val requester = requesters["$screen|$item"] ?: return false
        return try {
            requester.requestFocus()
        } catch (_: IllegalStateException) {
            false
        }
    }
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }
val LocalScreenKey = staticCompositionLocalOf { "" }

@Composable
fun Modifier.rememberFocus(itemKey: String): Modifier {
    val memory = LocalFocusMemory.current
    val screen = LocalScreenKey.current
    val requester = remember { FocusRequester() }
    DisposableEffect(screen, itemKey) {
        memory.register(screen, itemKey, requester)
        onDispose { memory.unregister(screen, itemKey, requester) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.onFocused(screen, itemKey) }
}

sealed interface DialogRequest {
    data class Pin(
        val kind: PinFlowKind,
        val title: String,
        val onSuccess: (PinProof) -> Unit,
    ) : DialogRequest

    data object Mode : DialogRequest
    data object GrantTime : DialogRequest
    data class Message(val title: String, val body: String) : DialogRequest
}

/** UI-level actions shared by all screens (dialogs, toasts, opening the player). */
@Stable
class UiController(
    val container: AppContainer,
    private val activity: ComponentActivity,
    val nav: NavState,
    private val scope: CoroutineScope,
) {
    var dialog by mutableStateOf<DialogRequest?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null
    private var lastProof: PinProof? = null

    fun play(video: Video, context: PlayContext) {
        if (container.kidsTimeUp.value) {
            showToast("Đã hết giờ xem hôm nay")
            return
        }
        activity.startActivity(PlayerActivity.intent(activity, video.id, context))
    }

    fun toggleFavorite(video: Video) {
        scope.launch {
            when (container.library.toggleFavorite(video.id)) {
                true -> showToast("Đã thêm vào Yêu thích: ${video.title}")
                false -> showToast("Đã bỏ khỏi Yêu thích: ${video.title}")
                null -> Unit
            }
        }
    }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2_500)
            toast = null
        }
    }

    fun dismissDialog() {
        dialog = null
    }

    fun message(title: String, body: String) {
        dialog = DialogRequest.Message(title, body)
    }

    /** Asks for the parent PIN (or explains that none exists yet). */
    fun requestPin(title: String, onVerified: (PinProof) -> Unit) {
        if (container.pins.isSet.value != true) {
            message("Chưa có PIN bố mẹ", "Hãy vào \"Cài đặt\" để tạo PIN trước. Khi chưa có PIN, app chỉ hiển thị nội dung trẻ em.")
            return
        }
        dialog = DialogRequest.Pin(PinFlowKind.VERIFY, title) { proof ->
            lastProof = proof
            dialog = null
            onVerified(proof)
        }
    }

    /** Reuses a PIN verified moments ago (e.g. inside the parent area), otherwise asks again. */
    fun withRecentPin(title: String, onVerified: (PinProof) -> Unit) {
        val proof = lastProof
        if (proof != null && container.session.isFresh(proof)) onVerified(proof) else requestPin(title, onVerified)
    }

    fun createPin(onCreated: (PinProof) -> Unit) {
        dialog = DialogRequest.Pin(PinFlowKind.CREATE, "Tạo PIN bố mẹ") { proof ->
            lastProof = proof
            dialog = null
            onCreated(proof)
        }
    }

    fun changePin() {
        dialog = DialogRequest.Pin(PinFlowKind.CHANGE, "Đổi PIN bố mẹ") { proof ->
            lastProof = proof
            dialog = null
            showToast("Đã đổi PIN")
        }
    }

    fun chooseMode() {
        dialog = DialogRequest.Mode
    }

    fun applyMode(mode: ViewMode) {
        if (mode == ViewMode.KIDS) {
            container.session.switchMode(ViewMode.KIDS, null)
            dialog = null
            showToast("Đã chuyển sang chế độ Trẻ em")
            return
        }
        withRecentPin("Nhập PIN để chuyển sang chế độ ${mode.label}") { proof ->
            dialog = null
            if (container.session.switchMode(mode, proof)) showToast("Đã chuyển sang chế độ ${mode.label}")
        }
    }

    fun grantTime() {
        requestPin("Nhập PIN để thêm thời gian xem") { dialog = DialogRequest.GrantTime }
    }

    /** Marks that we are about to open a system screen, so the session is not reset to Kids. */
    fun keepSessionDuringExternalActivity() {
        container.session.keepSessionWhileAway = true
    }
}
