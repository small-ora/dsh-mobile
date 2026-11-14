package com.smallora.dshmobile.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smallora.dshmobile.core.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_EDGE = 1600

/** 附件缩略图内存 LRU（同一图片在列表中重复出现时避免重复请求；最多 20 张）。 */
private val attachmentCache = LruCache<String, ImageBitmap>(20)

/**
 * 聊天消息图片（用户气泡 / 助手正文下方）。
 * - [ImageRef.DataUrl]：直接 base64 解码；
 * - [ImageRef.Attachment]：LaunchedEffect 拉取 bytes 后采样解码（max edge 1600 防 OOM）；
 * - 失败显示「图片无法加载」占位；缩略图圆角 12dp、单图最大 240dp、多图 160dp、FlowRow 换行（≤4 张）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatImages(
    images: List<ImageRef>,
    fetchAttachment: suspend (String, String) -> Pair<String, ByteArray>?,
    onPreview: (ImageBitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val shown = images.take(4)
    val side: Dp = if (shown.size == 1) 240.dp else 160.dp
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { ref ->
            ChatImage(
                ref = ref,
                side = side,
                fetchAttachment = fetchAttachment,
                onPreview = onPreview,
            )
        }
    }
}

@Composable
private fun ChatImage(
    ref: ImageRef,
    side: Dp,
    fetchAttachment: suspend (String, String) -> Pair<String, ByteArray>?,
    onPreview: (ImageBitmap) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    val dataUrlBitmap = remember(ref) {
        (ref as? ImageRef.DataUrl)?.let { decodeDataUrl(it.url) }
    }
    var attachmentBitmap by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(ref) {
        mutableStateOf(ref is ImageRef.DataUrl && dataUrlBitmap == null)
    }

    LaunchedEffect(ref) {
        val attachment = ref as? ImageRef.Attachment ?: return@LaunchedEffect
        failed = false
        attachmentCache.get(attachment.attachmentId)?.let {
            attachmentBitmap = it
            return@LaunchedEffect
        }
        // core fetchAttachment 非空返回、失败抛异常 → runCatching 兜底显示占位
        val bytes = runCatching { fetchAttachment(attachment.sessionId, attachment.attachmentId) }
            .getOrNull()
            ?.second
        val decoded = bytes?.let { data ->
            withContext(Dispatchers.Default) { decodeSampled(data) }
        }?.asImageBitmap()
        if (decoded == null) {
            failed = true
        } else {
            attachmentCache.put(attachment.attachmentId, decoded)
            attachmentBitmap = decoded
        }
    }

    val bitmap = attachmentBitmap ?: dataUrlBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onPreview(bitmap) },
        )
    } else {
        Box(
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (failed) "图片无法加载" else "图片",
                style = typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 全屏图片预览：黑底、点空白/返回关闭（Dialog 的 onDismissRequest 处理返回键）。 */
@Composable
internal fun FullScreenImagePreview(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 解码

/** data URL → Bitmap（输入区附件与 DataUrl 图片块共用）。 */
internal fun decodeDataUrl(dataUrl: String): ImageBitmap? {
    val marker = "base64,"
    val index = dataUrl.indexOf(marker)
    if (index < 0) return null
    return runCatching {
        val bytes = Base64.decode(dataUrl.substring(index + marker.length), Base64.DEFAULT)
        decodeSampled(bytes)?.asImageBitmap()
    }.getOrNull()
}

/** 采样解码，最长边不超过 [MAX_IMAGE_EDGE]，防止大图 OOM。 */
private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
