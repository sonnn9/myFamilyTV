package com.familytv.app.ui.pin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.familytv.app.core.pin.PinCheck
import com.familytv.app.core.pin.PinManager
import com.familytv.app.core.pin.PinProof
import com.familytv.app.core.pin.PinRules
import com.familytv.app.ui.components.AppIcons
import com.familytv.app.ui.components.FtvButton
import com.familytv.app.ui.theme.Ftv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PinFlowKind { VERIFY, CREATE, CHANGE }

private enum class Step { CURRENT, NEW, CONFIRM }

/**
 * PIN entry for the remote: on-screen keypad navigable with the D-pad, plus the remote's number
 * keys. Handles verify, first-time creation (enter + confirm) and change (current, new, confirm).
 */
@Composable
fun PinDialog(
    kind: PinFlowKind,
    title: String,
    pins: PinManager,
    onDismiss: () -> Unit,
    onSuccess: (PinProof) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (kind == PinFlowKind.CREATE) Step.NEW else Step.CURRENT) }
    var entered by remember { mutableStateOf("") }
    var firstNew by remember { mutableStateOf("") }
    var currentProof by remember { mutableStateOf<PinProof?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { lockMs = pins.lockRemainingMs() }
    LaunchedEffect(lockMs > 0) {
        while (lockMs > 0) {
            delay(1_000)
            lockMs = pins.lockRemainingMs()
        }
    }
    val locked = lockMs > 0 && step == Step.CURRENT

    val prompt = when (step) {
        Step.CURRENT -> if (kind == PinFlowKind.CHANGE) "Nhập PIN hiện tại" else "Nhập mã PIN"
        Step.NEW -> "Nhập PIN mới (${PinRules.MIN_LENGTH}–${PinRules.MAX_LENGTH} chữ số)"
        Step.CONFIRM -> "Nhập lại PIN mới để xác nhận"
    }

    fun finishWithNewPin(pin: String) {
        busy = true
        scope.launch {
            val failure = if (kind == PinFlowKind.CREATE) pins.setInitialPin(pin) else pins.changePin(currentProof!!, pin)
            if (failure != null) {
                error = failure
                step = Step.NEW
                entered = ""
                busy = false
                return@launch
            }
            val check = pins.verify(pin)
            busy = false
            if (check is PinCheck.Ok) onSuccess(check.proof) else onDismiss()
        }
    }

    fun submit() {
        if (busy || entered.length < PinRules.MIN_LENGTH) return
        val pin = entered
        when (step) {
            Step.CURRENT -> {
                busy = true
                scope.launch {
                    when (val r = pins.verify(pin)) {
                        is PinCheck.Ok -> if (kind == PinFlowKind.CHANGE) {
                            currentProof = r.proof
                            step = Step.NEW
                            entered = ""
                            error = null
                        } else {
                            onSuccess(r.proof)
                        }
                        is PinCheck.Wrong -> {
                            entered = ""
                            lockMs = r.lockMs
                            error = if (r.lockMs > 0) "Sai PIN quá nhiều lần." else "Sai PIN. Còn ${r.attemptsLeft} lần thử trước khi bị khóa tạm thời."
                        }
                        is PinCheck.Locked -> {
                            entered = ""
                            lockMs = r.remainingMs
                        }
                        PinCheck.NotSet -> error = "Chưa có PIN bố mẹ."
                    }
                    busy = false
                }
            }
            Step.NEW -> {
                val problem = PinRules.validateNewPin(pin)
                entered = ""
                if (problem != null) {
                    error = problem
                } else {
                    firstNew = pin
                    error = null
                    step = Step.CONFIRM
                }
            }
            Step.CONFIRM -> {
                entered = ""
                if (pin != firstNew) {
                    error = "PIN nhập lại không khớp. Hãy nhập lại PIN mới."
                    firstNew = ""
                    step = Step.NEW
                } else {
                    finishWithNewPin(pin)
                }
            }
        }
    }

    fun addDigit(d: Char) {
        if (!busy && !locked && entered.length < PinRules.MAX_LENGTH) entered += d
    }

    val firstKey = remember { FocusRequester() }
    val okKey = remember { FocusRequester() }
    var focusOk by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { firstKey.requestFocus() }
        }
        // Typing with the remote's number keys: once the PIN is long enough, OK confirms it.
        LaunchedEffect(focusOk) {
            if (focusOk == 0) return@LaunchedEffect
            withFrameNanos { }
            runCatching { okKey.requestFocus() }
        }
        Column(
            modifier = Modifier
                .background(Ftv.Surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = digitOf(e.key)
                    when {
                        digit != null -> {
                            addDigit(digit)
                            if (entered.length >= PinRules.MIN_LENGTH) focusOk++
                            true
                        }
                        e.key == Key.Backspace || e.key == Key.Delete -> { entered = entered.dropLast(1); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Ftv.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(prompt, color = Ftv.TextSecondary, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val slots = maxOf(PinRules.MIN_LENGTH, entered.length)
                repeat(slots) { i ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (i < entered.length) Ftv.Primary else Ftv.SurfaceHigh, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val info = when {
                locked -> "Tạm khóa do nhập sai nhiều lần. Thử lại sau ${formatLock(lockMs)}."
                busy -> "Đang kiểm tra…"
                else -> error
            }
            Text(
                info ?: " ",
                color = if (locked || error != null) Ftv.Warning else Ftv.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(360.dp),
            )
            Spacer(Modifier.height(12.dp))
            val rows = listOf("123", "456", "789")
            val enabled = !busy && !locked
            rows.forEachIndexed { r, digits ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    digits.forEachIndexed { c, d ->
                        val mod = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier
                        KeyButton(d.toString(), enabled, mod) { addDigit(d) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                KeyButton("⌫", enabled, icon = true) { entered = entered.dropLast(1) }
                KeyButton("0", enabled) { addDigit('0') }
                KeyButton("OK", enabled && entered.length >= PinRules.MIN_LENGTH, Modifier.focusRequester(okKey), accent = true) { submit() }
            }
            Spacer(Modifier.height(14.dp))
            FtvButton("Hủy", onDismiss)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(width = 84.dp, height = 56.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (accent) Ftv.Primary else Ftv.SurfaceHigh,
            contentColor = if (accent) Ftv.OnPrimary else Ftv.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Ftv.Background,
            disabledContentColor = Ftv.TextMuted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ftv.Primary), shape = shape)),
    ) {
        Box(Modifier.align(Alignment.Center)) {
            if (icon) {
                Icon(AppIcons.Backspace, contentDescription = "Xóa", modifier = Modifier.size(24.dp))
            } else {
                Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

private fun formatLock(ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s >= 60) "${s / 60} phút ${s % 60} giây" else "$s giây"
}
