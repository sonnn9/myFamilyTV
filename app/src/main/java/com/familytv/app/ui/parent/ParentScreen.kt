package com.familytv.app.ui.parent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.familytv.app.core.config.ConfigIssue
import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.quota.DailyQuota
import com.familytv.app.core.text.NaturalOrder
import com.familytv.app.data.library.MediaFiles
import com.familytv.app.data.library.SourceState
import com.familytv.app.data.settings.ParentOverrides
import com.familytv.app.data.source.LibrarySourceRef
import com.familytv.app.data.source.StorageVolumes
import com.familytv.app.data.source.VolumeInfo
import com.familytv.app.ui.UiController
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.components.FtvButton
import com.familytv.app.ui.components.MessagePanel
import com.familytv.app.ui.components.SectionTitle
import com.familytv.app.ui.components.formatMinutes
import com.familytv.app.ui.rememberFocus
import com.familytv.app.ui.theme.Ftv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** System folder picker (Storage Access Framework); asks DocumentsUI to also show USB/internal roots. */
class OpenFamilyTvFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).apply {
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra("android.provider.extra.SHOW_ADVANCED", true)
        }

    companion object {
        fun isAvailable(context: Context): Boolean =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null
    }
}

private const val PICKER_MISSING =
    "Hệ thống của TV này không có trình chọn thư mục (Storage Access Framework). " +
        "Hãy dùng \"Đọc USB trực tiếp\": Android 6–10 chỉ cần cấp quyền đọc bộ nhớ; " +
        "Android 11 trở lên cần cấp quyền \"Truy cập mọi tệp\" (thường phải dùng lệnh adb – xem tài liệu)."

@Composable
fun ParentScreen(ui: UiController) {
    val c = ui.container
    val unlocked by c.session.parentUnlocked.collectAsStateWithLifecycle()
    if (!unlocked) {
        ParentLocked(ui)
        return
    }
    // Leaving the parent area (another menu item, Home button) locks it again.
    DisposableEffect(Unit) { onDispose { c.session.lockParentArea() } }
    ParentContent(ui)
}

@Composable
private fun ParentLocked(ui: UiController) {
    val c = ui.container
    val pinSet by c.pins.isSet.collectAsStateWithLifecycle()
    val unlock: () -> Unit = {
        if (c.pins.isSet.value == true) {
            ui.requestPin("Cài đặt") { proof -> c.session.unlockParentArea(proof) }
        } else {
            ui.createPin { proof -> c.session.unlockParentArea(proof) }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { pinSet }.first { it != null }
        unlock()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MessagePanel(
            title = "Cài đặt",
            body = if (pinSet == false) {
                "Lần đầu sử dụng: hãy tạo PIN bố mẹ (4–8 chữ số). PIN chỉ lưu dạng mã hóa trong TV, không có PIN mặc định."
            } else {
                "Nhập mã PIN để mở Cài đặt."
            },
            icon = AppIcons.Settings,
        ) {
            FtvButton(if (pinSet == false) "Tạo PIN bố mẹ" else "Nhập mã PIN", unlock, primary = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentContent(ui: UiController) {
    val c = ui.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by c.library.status.collectAsStateWithLifecycle()
    val mode by c.session.mode.collectAsStateWithLifecycle()
    val rules by c.rules.collectAsStateWithLifecycle()
    val quota by c.watchTime.state.collectAsStateWithLifecycle()
    val overrides by c.settings.overrides.collectAsStateWithLifecycle(ParentOverrides())
    var showDirect by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { c.thumbnails.cacheSizeBytes() } }

    val picker = rememberLauncherForActivityResult(OpenFamilyTvFolder()) { uri ->
        if (uri != null) {
            scope.launch {
                val error = c.library.selectSafFolder(uri)
                if (error != null) ui.message("Chưa chọn được thư mục", error) else ui.showToast("Đã chọn thư mục, đang quét thư viện…")
            }
        }
    }
    val launchPicker = {
        if (!OpenFamilyTvFolder.isAvailable(context)) {
            ui.message("Không có trình chọn thư mục", PICKER_MISSING)
        } else {
            ui.keepSessionDuringExternalActivity()
            try {
                picker.launch(null)
            } catch (_: ActivityNotFoundException) {
                c.session.keepSessionWhileAway = false
                ui.message("Không mở được trình chọn thư mục", PICKER_MISSING)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item("title") {
            Column {
                Text("Khu vực bố mẹ", color = Ftv.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Rời khỏi trang này hoặc bấm Home sẽ tự khóa lại.", color = Ftv.TextSecondary, fontSize = 14.sp)
            }
        }

        item("source") {
            Section("Nguồn thư viện (USB)") {
                val stateText = when (status.state) {
                    SourceState.LOADING, SourceState.CHECKING -> "Đang kiểm tra…"
                    SourceState.NO_SOURCE -> "Chưa chọn thư mục FamilyTV"
                    SourceState.AVAILABLE -> "Đã kết nối, đọc được"
                    SourceState.NOT_CONNECTED -> "Không thấy USB (chưa cắm hoặc đã rút)"
                    SourceState.PERMISSION_LOST -> "Mất quyền đọc – hãy chọn lại thư mục"
                }
                val stateColor = when (status.state) {
                    SourceState.AVAILABLE -> Ftv.Success
                    SourceState.NOT_CONNECTED, SourceState.PERMISSION_LOST -> Ftv.Warning
                    else -> Ftv.TextSecondary
                }
                InfoLine("Trạng thái", stateText, stateColor)
                status.source?.let { src ->
                    val how = if (src.type == LibrarySourceRef.Type.SAF) "trình chọn hệ thống" else "đọc trực tiếp"
                    InfoLine("Thư mục", "${src.displayName()} ($how)")
                }
                status.notice?.let { Note(it, Ftv.Warning) }
                status.lastScan?.let { InfoLine("Quét gần nhất", "${formatTime(it.at)} – ${it.summary}") }
                if (status.scanning) InfoLine("Đang quét", "${status.scanCount} video…", Ftv.Accent)
                status.scanMessages.take(8).forEach { Note("• $it", Ftv.Warning) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FtvButton("Chọn thư mục FamilyTV", launchPicker, Modifier.rememberFocus("pick"), icon = AppIcons.Folder, primary = status.source == null)
                    FtvButton("Đọc USB trực tiếp (dự phòng)", { showDirect = true })
                    FtvButton("Quét lại thư viện", {
                        c.library.refreshAsync(rescan = true)
                        ui.showToast("Đang quét lại thư viện…")
                    }, enabled = status.source != null && !status.scanning, icon = AppIcons.Replay)
                }
                Note(
                    "Trong trình chọn của TV, mở USB rồi chọn thư mục FamilyTV (thư mục chứa config.json) và bấm \"Cho phép\". " +
                        "Nếu trình chọn không hiện USB, dùng \"Đọc USB trực tiếp\".",
                )
            }
        }

        item("config") {
            Section("Cấu hình config.json") {
                val cfg = status.config
                if (cfg.active != null) {
                    InfoLine(
                        "Đang dùng",
                        "\"${cfg.active.appName}\", ${cfg.active.folders.size} thư mục" +
                            (cfg.activeAppliedAt?.let { " – áp dụng lúc ${formatTime(it)}" } ?: ""),
                        Ftv.Success,
                    )
                } else {
                    InfoLine("Đang dùng", "Chưa có cấu hình hợp lệ – app không hiển thị nội dung nào", Ftv.Warning)
                }
                cfg.lastFailure?.let { failure ->
                    Note(
                        "Lần đọc config.json lúc ${formatTime(failure.at)} bị lỗi" +
                            (if (cfg.active != null) " – vẫn giữ cấu hình hợp lệ trước đó:" else ":"),
                        Ftv.Error,
                    )
                    failure.errors.take(12).forEach { IssueLine(it) }
                }
                cfg.warnings.take(12).forEach { IssueLine(it) }
            }
        }

        item("mode") {
            Section("Chế độ xem") {
                Note("Mỗi lần mở app (hoặc khi app bị đưa xuống nền) sẽ tự về chế độ Trẻ em.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ViewMode.entries.forEach { m ->
                        FtvButton(m.label, { ui.applyMode(m) }, selected = m == mode)
                    }
                }
            }
        }

        item("limit") {
            Section("Giới hạn thời gian (chế độ Trẻ em)") {
                val configLimit = status.config.active?.settings?.sessionLimitMinutes
                val origin = when {
                    overrides.limitMinutes != null -> "đặt trong app"
                    configLimit != null -> "theo config.json"
                    else -> "mặc định"
                }
                val applied = if (rules.limitMinutes == 0) "Không giới hạn" else "${rules.limitMinutes} phút mỗi ngày"
                InfoLine("Đang áp dụng", "$applied ($origin)")
                quota?.let { q ->
                    val remaining = DailyQuota.remainingMs(q, rules.limitOrNull)
                    InfoLine(
                        "Hôm nay",
                        "đã xem ${formatMinutes(q.usedMs)}" +
                            (if (q.bonusMs > 0) ", thêm ${formatMinutes(q.bonusMs)}" else "") +
                            (remaining?.let { ", còn ${formatMinutes(it)}" } ?: ""),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FtvButton("Theo config.json", { scope.launch { c.settings.setLimitOverride(null) } }, selected = overrides.limitMinutes == null)
                    FtvButton("Tắt giới hạn", { scope.launch { c.settings.setLimitOverride(0) } }, selected = overrides.limitMinutes == 0)
                    listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                        FtvButton("$m phút", { scope.launch { c.settings.setLimitOverride(m) } }, selected = overrides.limitMinutes == m)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FtvButton("+15 phút hôm nay", { scope.launch { c.watchTime.grant(15); ui.showToast("Đã thêm 15 phút") } })
                    FtvButton("+30 phút hôm nay", { scope.launch { c.watchTime.grant(30); ui.showToast("Đã thêm 30 phút") } })
                    FtvButton("Đặt lại hôm nay", { scope.launch { c.watchTime.resetToday(); ui.showToast("Đã đặt lại thời gian hôm nay") } })
                }
                Note(
                    "Chỉ tính thời gian video thực sự phát ở chế độ Trẻ em (không tính lúc tạm dừng hay chọn video). " +
                        "Hạn mức tự đặt lại lúc 0:00 theo giờ TV. Nếu đồng hồ TV bị chỉnh lùi, thời gian đã dùng không bị xóa.",
                )
            }
        }

        item("playback") {
            Section("Phát video") {
                TriStateRow("Tự phát video tiếp theo", overrides.autoplay, status.config.active?.settings?.autoplay, false) { v ->
                    scope.launch { c.settings.setAutoplayOverride(v) }
                }
                TriStateRow("Tiếp tục từ chỗ đang xem", overrides.resumePlayback, status.config.active?.settings?.resumePlayback, true) { v ->
                    scope.launch { c.settings.setResumeOverride(v) }
                }
            }
        }

        item("security") {
            Section("PIN bố mẹ") {
                FtvButton("Đổi PIN", ui::changePin, icon = AppIcons.Lock)
                Note(
                    "Quên PIN: vào Cài đặt của TV > Ứng dụng > Family TV > Xóa dữ liệu (hoặc gỡ cài đặt rồi cài lại). " +
                        "Thao tác này xóa PIN, lịch sử, yêu thích và nguồn USB đã chọn; video trên USB không bị ảnh hưởng.",
                )
            }
        }

        item("data") {
            Section("Dữ liệu trong TV") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FtvButton("Xóa lịch sử và tiến độ xem", {
                        scope.launch {
                            c.library.clearHistory()
                            ui.showToast("Đã xóa lịch sử xem")
                        }
                    })
                    FtvButton("Xóa ảnh thu nhỏ đã lưu (${cacheBytes / (1024 * 1024)} MB)", {
                        scope.launch {
                            withContext(Dispatchers.IO) { c.thumbnails.clear() }
                            cacheBytes = 0
                            ui.showToast("Đã xóa ảnh thu nhỏ")
                        }
                    })
                }
            }
        }

        item("limits") {
            Section("Giới hạn của ứng dụng") {
                Note(
                    "Family TV chỉ kiểm soát nội dung bên trong ứng dụng này. App không khóa được toàn bộ TV: " +
                        "trẻ vẫn có thể bấm Home để mở ứng dụng khác. Hãy dùng thêm hồ sơ hạn chế/kiểm soát của phụ huynh trên TV nếu cần.",
                )
            }
        }
    }

    if (showDirect) DirectAccessDialog(ui) { showDirect = false }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ftv.Surface, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String, color: Color = Ftv.TextPrimary) {
    Text(
        "$label: $value",
        color = color,
        fontSize = 15.sp,
    )
}

@Composable
private fun Note(text: String, color: Color = Ftv.TextSecondary) {
    Text(text, color = color, fontSize = 14.sp, modifier = Modifier.widthIn(max = 820.dp))
}

@Composable
private fun IssueLine(issue: ConfigIssue) {
    val isError = issue.severity == ConfigIssue.Severity.ERROR
    Note((if (isError) "✖ " else "⚠ ") + issue.toString(), if (isError) Ftv.Error else Ftv.Warning)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriStateRow(label: String, override: Boolean?, fromConfig: Boolean?, default: Boolean, onSet: (Boolean?) -> Unit) {
    val effective = override ?: fromConfig ?: default
    Text("$label: ${if (effective) "Bật" else "Tắt"}", color = Ftv.TextPrimary, fontSize = 15.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FtvButton("Theo config.json", { onSet(null) }, selected = override == null)
        FtvButton("Bật", { onSet(true) }, selected = override == true)
        FtvButton("Tắt", { onSet(false) }, selected = override == false)
    }
}

private fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.US).format(Date(millis))

// ---------------------------------------------------------------------------------------------
// Fallback: direct file access + in-app folder browser
// ---------------------------------------------------------------------------------------------

private fun hasDirectAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun DirectAccessDialog(ui: UiController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasDirectAccess(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = hasDirectAccess(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasDirectAccess(context) }
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(granted) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ftv.Background)
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Đọc USB trực tiếp", color = Ftv.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Note("Dùng khi TV không có trình chọn thư mục hoặc trình chọn không hiện USB. App chỉ đọc, không ghi gì lên USB.")
            if (!granted) {
                if (Build.VERSION.SDK_INT < 30) {
                    Note("Android ${Build.VERSION.RELEASE} trên TV này: cần cấp quyền đọc bộ nhớ.")
                    FtvButton("Cấp quyền đọc bộ nhớ", {
                        ui.keepSessionDuringExternalActivity()
                        permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }, Modifier.focusRequester(first), primary = true)
                } else {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    val canOpenSettings = settingsIntent.resolveActivity(context.packageManager) != null
                    Note(
                        "Android ${Build.VERSION.RELEASE} (11 trở lên) chỉ cho đọc config.json qua trình chọn thư mục hoặc quyền " +
                            "\"Truy cập mọi tệp\". Nhiều TV không có màn hình cấp quyền này; khi đó dùng máy tính chạy lệnh:",
                    )
                    Text(
                        "adb shell appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
                        color = Ftv.Accent,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (canOpenSettings) {
                        FtvButton("Mở cài đặt quyền", {
                            ui.keepSessionDuringExternalActivity()
                            runCatching { context.startActivity(settingsIntent) }
                        }, Modifier.focusRequester(first), primary = true)
                    }
                }
                FtvButton("Đóng", onDismiss, if (Build.VERSION.SDK_INT >= 30) Modifier.focusRequester(first) else Modifier)
            } else {
                FolderBrowser(ui, first, onDone = onDismiss)
            }
        }
    }
}

@Composable
private fun FolderBrowser(ui: UiController, first: FocusRequester, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<File?>(null) }
    var volumeRoot by remember { mutableStateOf<File?>(null) }
    val volumes by produceState(emptyList<VolumeInfo>()) {
        value = withContext(Dispatchers.IO) { StorageVolumes.discover(context) }
    }
    val dirs by produceState(emptyList<File>(), current) {
        value = withContext(Dispatchers.IO) {
            current?.listFiles()
                ?.filter { it.isDirectory && !MediaFiles.isHidden(it.name) }
                ?.sortedWith(compareBy(NaturalOrder) { it.name })
                .orEmpty()
        }
    }
    val hasConfig by produceState(false, current) {
        value = withContext(Dispatchers.IO) {
            current?.listFiles()?.any { it.isFile && it.name.equals("config.json", ignoreCase = true) } == true
        }
    }

    LaunchedEffect(current, volumes, hasConfig) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    fun choose(dir: File) {
        scope.launch {
            val error = ui.container.library.selectLocalFolder(dir)
            if (error != null) {
                ui.message("Chưa chọn được thư mục", error)
            } else {
                ui.showToast("Đã chọn ${dir.path}, đang quét thư viện…")
                onDone()
            }
        }
    }

    Text(current?.path ?: "Chọn ổ lưu trữ", color = Ftv.Accent, fontSize = 16.sp)
    if (current != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasConfig) {
                FtvButton("Dùng thư mục này (có config.json)", { choose(current!!) }, Modifier.focusRequester(first), primary = true)
            } else {
                Note("Thư mục này không có config.json. Hãy mở thư mục FamilyTV.")
            }
            FtvButton("◀ Lên thư mục trên", {
                val parent = current?.parentFile
                current = if (current == volumeRoot || parent == null) null else parent
            }, if (hasConfig) Modifier else Modifier.focusRequester(first))
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (current == null) {
            if (volumes.isEmpty()) {
                item("none") { Note("Không tìm thấy ổ lưu trữ nào. Hãy cắm USB rồi mở lại.", Ftv.Warning) }
            }
            items(volumes, key = { it.directory.path }) { v ->
                val index = volumes.indexOf(v)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val familyTv = remember(v) { v.familyTvDir }
                    if (familyTv != null) {
                        FtvButton(
                            "Dùng ${v.label} / ${familyTv.name}",
                            { choose(familyTv) },
                            if (index == 0) Modifier.focusRequester(first) else Modifier,
                            icon = AppIcons.Check,
                            primary = true,
                        )
                    }
                    FtvButton(
                        "Mở ${v.label} (${v.directory.path})",
                        { volumeRoot = v.directory; current = v.directory },
                        if (index == 0 && familyTv == null) Modifier.focusRequester(first) else Modifier,
                        icon = AppIcons.Folder,
                    )
                }
            }
        } else {
            items(dirs, key = { it.path }) { d ->
                FtvButton(d.name, { current = d }, icon = AppIcons.Folder)
            }
            if (dirs.isEmpty()) item("empty") { Note("(Không có thư mục con)") }
        }
    }
}
