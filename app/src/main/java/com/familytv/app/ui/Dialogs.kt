package com.familytv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.familytv.app.AppContainer
import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.quota.DailyQuota
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.components.FtvButton
import com.familytv.app.ui.components.formatMinutes
import com.familytv.app.ui.pin.PinDialog
import com.familytv.app.ui.theme.Ftv
import kotlinx.coroutines.launch

@Composable
fun DialogHost(ui: UiController) {
    val container = ui.container
    when (val d = ui.dialog) {
        null -> Unit
        is DialogRequest.Pin -> PinDialog(d.kind, d.title, container.pins, onDismiss = ui::dismissDialog, onSuccess = d.onSuccess)
        DialogRequest.Mode -> {
            val mode by container.session.mode.collectAsStateWithLifecycle()
            ModeDialog(mode, onDismiss = ui::dismissDialog, onChoose = ui::applyMode)
        }
        DialogRequest.GrantTime -> GrantTimeDialog(ui)
        is DialogRequest.Message -> MessageDialog(d.title, d.body, ui::dismissDialog)
    }
}

/** Dialog frame that puts focus on its first button once the window is shown. */
@Composable
fun FtvDialog(
    title: String,
    onDismiss: () -> Unit,
    body: String? = null,
    content: @Composable (first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 680.dp)
                .background(Ftv.Surface, RoundedCornerShape(20.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = Ftv.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (body != null) Text(body, color = Ftv.TextSecondary, fontSize = 15.sp)
            content(first)
        }
    }
}

@Composable
fun MessageDialog(title: String, body: String, onDismiss: () -> Unit) {
    FtvDialog(title, onDismiss, body) { first ->
        FtvButton("Đã hiểu", onDismiss, Modifier.focusRequester(first), primary = true)
    }
}

@Composable
fun ModeDialog(current: ViewMode, onDismiss: () -> Unit, onChoose: (ViewMode) -> Unit) {
    FtvDialog("Chọn chế độ xem", onDismiss, "Chế độ Người lớn và Tất cả cần PIN của bố mẹ.") { first ->
        val options = listOf(
            ViewMode.KIDS to "Trẻ em – nội dung \"kids\" và \"all\"",
            ViewMode.ADULTS to "Người lớn – nội dung \"adults\" và \"all\"",
            ViewMode.ALL to "Tất cả – mọi nội dung",
        )
        options.forEachIndexed { i, (mode, label) ->
            FtvButton(
                text = label,
                onClick = { onChoose(mode) },
                selected = mode == current,
                icon = if (mode == ViewMode.KIDS) null else AppIcons.Lock,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        FtvButton("Đóng", onDismiss)
    }
}

@Composable
fun GrantTimeDialog(ui: UiController) {
    val scope = rememberCoroutineScope()
    val tracker = ui.container.watchTime
    fun grant(minutes: Int) {
        scope.launch {
            tracker.grant(minutes)
            ui.dismissDialog()
            ui.showToast("Đã thêm $minutes phút cho hôm nay")
        }
    }
    FtvDialog("Thêm thời gian xem hôm nay", ui::dismissDialog, "Chỉ áp dụng cho hôm nay, ở chế độ Trẻ em.") { first ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FtvButton("+15 phút", { grant(15) }, Modifier.focusRequester(first))
            FtvButton("+30 phút", { grant(30) })
            FtvButton("+60 phút", { grant(60) })
        }
        FtvButton("Đặt lại thời gian đã dùng hôm nay", {
            scope.launch {
                tracker.resetToday()
                ui.dismissDialog()
                ui.showToast("Đã đặt lại thời gian xem hôm nay")
            }
        })
        FtvButton("Đóng", ui::dismissDialog)
    }
}

/**
 * Full-screen "time is up" page for Kids mode. It is its own window, so nothing behind it can be
 * focused; Back leaves the app instead of returning to the videos.
 */
@Composable
fun BreakScreen(container: AppContainer, onGrant: () -> Unit, onChangeMode: () -> Unit, onExit: () -> Unit) {
    val quota by container.watchTime.state.collectAsStateWithLifecycle()
    val rules by container.rules.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF101528)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(AppIcons.Moon, contentDescription = null, tint = Ftv.Warning, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(20.dp))
            Text("Hết giờ xem rồi!", color = Ftv.TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            val limitMs = (DailyQuota.limitMs(rules.limitOrNull) ?: 0L) + (quota?.bonusMs ?: 0L)
            Text(
                "Hôm nay con đã xem đủ ${formatMinutes(limitMs)}. Hãy nghỉ ngơi, vận động một chút và quay lại vào ngày mai nhé!",
                color = Ftv.TextSecondary,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 620.dp),
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FtvButton("Bố mẹ: thêm thời gian", onGrant, Modifier.focusRequester(first), icon = AppIcons.Lock)
                FtvButton("Bố mẹ: đổi chế độ", onChangeMode, icon = AppIcons.Lock)
                FtvButton("Thoát", onExit)
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
