package com.familytv.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.familytv.app.R
import com.familytv.app.appContainer
import com.familytv.app.core.model.ProgressRecord
import com.familytv.app.core.model.Video
import com.familytv.app.ui.theme.Ftv
import java.util.Locale

val CardWidth = 208.dp
private val CardShape = RoundedCornerShape(10.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

/** Remote-friendly button: clear white focus state, optional "selected" marker. */
@Composable
fun FtvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val container = when {
        primary -> Ftv.Primary
        selected -> Ftv.SurfaceSelected
        else -> Ftv.SurfaceHigh
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = if (primary) Ftv.OnPrimary else Ftv.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
            disabledContainerColor = Ftv.Surface,
            disabledContentColor = Ftv.TextMuted,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, Ftv.Primary), shape = ButtonShape) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, Ftv.Primary), shape = ButtonShape),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(AppIcons.Check, contentDescription = "Đang chọn", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 16:9 video card with focus border/scale, duration, favorite marker and watched progress. */
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: ProgressRecord? = null,
    isFavorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = CardWidth,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier = if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(CardShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Ftv.SurfaceHigh,
                focusedContainerColor = Ftv.SurfaceHigh,
                pressedContainerColor = Ftv.SurfaceHigh,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Ftv.Focus), shape = CardShape),
            ),
        ) {
            Thumbnail(video, Modifier.fillMaxSize())
            val duration = video.durationMs ?: progress?.durationMs?.takeIf { it > 0 }
            if (duration != null) {
                Text(
                    text = formatDuration(duration),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (isFavorite) {
                Icon(
                    AppIcons.Favorite,
                    contentDescription = "Yêu thích",
                    tint = Ftv.Primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
            if (progress != null && progress.durationMs > 0) {
                val fraction = if (progress.completed) 1f else (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
                WatchedBar(fraction, Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = video.title,
            color = if (focused) Ftv.TextPrimary else Ftv.TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
    }
}

/** Watched-progress bar; the dark frame keeps it visible on any thumbnail colour. */
@Composable
fun WatchedBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).background(Color(0xCC000000)).padding(vertical = 1.dp)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Ftv.Primary))
    }
}

/** Cached thumbnail, generated in the background on first display; placeholder otherwise. */
@Composable
fun Thumbnail(video: Video, modifier: Modifier = Modifier) {
    val thumbnails = LocalContext.current.appContainer.thumbnails
    val image by produceState(thumbnails.cached(video), video.id, video.lastModified, video.sidecarImageUri) {
        if (value == null) value = thumbnails.load(video)
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Placeholder(video.title, modifier)
    }
}

private val placeholderColors = listOf(
    Color(0xFF2E3B55), Color(0xFF3B2E55), Color(0xFF2E554A), Color(0xFF55402E), Color(0xFF552E3B), Color(0xFF2E4A55),
)

@Composable
fun Placeholder(title: String, modifier: Modifier = Modifier) {
    val color = placeholderColors[(title.hashCode() and Int.MAX_VALUE) % placeholderColors.size]
    Box(modifier.background(color), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Play, contentDescription = null, tint = Color(0x99FFFFFF), modifier = Modifier.size(40.dp))
    }
}

/** "See all" tile at the end of a home row. */
@Composable
fun MoreCard(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(CardWidth).aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Ftv.SurfaceHigh,
            contentColor = Ftv.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ftv.Primary), shape = CardShape)),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Xem tất cả", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("$count video", fontSize = 13.sp)
        }
    }
}

/** App logo at the given height, keeping the drawable's own proportions (a personal build may swap it). */
@Composable
fun AppLogo(height: Dp, modifier: Modifier = Modifier) {
    val logo = painterResource(R.drawable.ic_logo)
    Image(
        logo,
        contentDescription = null,
        modifier = modifier.height(height).aspectRatio(logo.intrinsicSize.width / logo.intrinsicSize.height),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Ftv.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** Centered message used for empty lists and status pages (not focusable unless actions given). */
@Composable
fun MessagePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Ftv.TextSecondary, modifier = Modifier.size(48.dp))
        Text(title, color = Ftv.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = Ftv.TextSecondary, fontSize = 15.sp, modifier = Modifier.widthIn(max = 640.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}

@Composable
fun Chip(text: String, color: Color = Ftv.SurfaceHigh, textColor: Color = Ftv.TextPrimary, modifier: Modifier = Modifier) {
    Text(
        text,
        color = textColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatMinutes(ms: Long): String {
    val minutes = (ms + 59_999) / 60_000
    return "$minutes phút"
}
