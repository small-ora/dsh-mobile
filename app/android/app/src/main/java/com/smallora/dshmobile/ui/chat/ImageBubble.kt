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
import com.smallora.dshmobile.core.MarkdownImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val MAX_IMAGE_EDGE = 1600

/** 附件/远程/工作区图片的内存 LRU（同一图片重复出现时避免重复请求；最多 20 张）。 */
private val imageCache = LruCache<String, ImageBitmap>(20)

/** 远程图片下载客户端（无鉴权，15s 超时）。 */
private val remoteClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

/** 聊天图片的通用来源：附件（fetchAttachment）/ Markdown 远程 / 工作区路径 / data URL。 */
internal sealed interface ChatImageSource {
    val cacheKey: String

    data class Attachment(val sessionId: String, val attachmentId: String) : ChatImageSource {
        override val cacheKey: String get() = "attachment:$attachmentId"
    }

    data class Remote(val url: String) : ChatImageSource {
        override val cacheKey: String get() = "remote:$url"
    }

    data class Workspace(val path: String) : ChatImageSource {
        override val cacheKey: String get() = "workspace:$path"
    }

    data class DataUrl(val url: String) : ChatImageSource {
        override val cacheKey: String get() = "data:${url.hashCode()}"
    }
}

/**
 * 图片渲染环境：由 ChatScreen 提供。
 * - [loadBytes]：按来源取字节（附件 fetchAttachment / 远程 OkHttp / 工作区 fetchFile；失败返回 null）；
 * - [onPreview]：点击缩略图打开全屏预览；
 * - [resolve]：Markdown 图片 src → 来源（内部用 core 的 resolveMarkdownImageSrc(src, cwd)）。
 */
internal class ChatImageEnv(
    val loadBytes: suspend (ChatImageSource) -> Pair<String, ByteArray>?,
    val onPreview: (ImageBitmap) -> Unit,
    val resolve: (String) -> ChatImageSource?,
)

internal fun ImageRef.toChatImageSource(): ChatImageSource = when (this) {
    is ImageRef.DataUrl -> ChatImageSource.DataUrl(url)
    is ImageRef.Attachment -> ChatImageSource.Attachment(sessionId, attachmentId)
}

internal fun MarkdownImageSource.toChatImageSource(): ChatImageSource = when (this) {
    is MarkdownImageSource.Remote -> ChatImageSource.Remote(url)
    is MarkdownImageSource.DataUrl -> ChatImageSource.DataUrl(url)
    is MarkdownImageSource.WorkspacePath -> ChatImageSource.Workspace(path)
}

// ------------------------------------------------------------------ 消息附件图片（用户/助手）

/** 消息附件的图片列表（`ChatItem.User/Assistant.images`）：≤4 张，单图 240dp、多图 160dp，圆角 12dp。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatImages(
    images: List<ImageRef>,
    env: ChatImageEnv,
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
            ChatImage(source = ref.toChatImageSource(), side = side, env = env)
        }
    }
}

// ------------------------------------------------------------------ Markdown 正文内嵌图片

/** Markdown 段落内嵌图片：按 src 解析为来源，失败显示占位；多图换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkdownImages(
    images: List<MarkdownImage.Image>,
    env: ChatImageEnv,
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
        shown.forEach { image ->
            val source = env.resolve(image.src)
            if (source == null) {
                ImagePlaceholder(side = side, text = "图片无法加载")
            } else {
                ChatImage(source = source, side = side, env = env)
            }
        }
    }
}

// ------------------------------------------------------------------ 通用图片

@Composable
private fun ChatImage(
    source: ChatImageSource,
    side: Dp,
    env: ChatImageEnv,
) {
    val dataUrlBitmap = remember(source) {
        (source as? ChatImageSource.DataUrl)?.let { decodeDataUrl(it.url) }
    }
    var loadedBitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source) { mutableStateOf(source is ChatImageSource.DataUrl && dataUrlBitmap == null) }
    var errorText by remember(source) { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        if (source is ChatImageSource.DataUrl) return@LaunchedEffect
        failed = false
        errorText = null
        imageCache.get(source.cacheKey)?.let {
            loadedBitmap = it
            return@LaunchedEffect
        }
        val loaded = try {
            env.loadBytes(source)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorText = e.message ?: e.javaClass.simpleName
            null
        }
        val bytes = loaded?.second
        if (bytes == null && errorText == null) errorText = "无数据"
        val decoded = bytes?.let { data ->
            withContext(Dispatchers.Default) { decodeSampled(data) }
        }?.asImageBitmap()
        if (decoded == null) {
            failed = true
            if (errorText == null) errorText = "解码失败"
        } else {
            imageCache.put(source.cacheKey, decoded)
            loadedBitmap = decoded
        }
    }

    val bitmap = loadedBitmap ?: dataUrlBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(12.dp))
                .clickable { env.onPreview(bitmap) },
        )
    } else {
        ImagePlaceholder(side = side, text = if (failed) "图片无法加载\n" + (errorText ?: "") else "图片")
    }
}

@Composable
private fun ImagePlaceholder(side: Dp, text: String) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = typography.labelSmall, color = colors.onSurfaceVariant)
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

// ------------------------------------------------------------------ 加载 / 解码

/** 远程图片（Markdown http/https）：OkHttp 直接下载，15s 超时；失败返回 null。 */
internal suspend fun fetchRemoteImage(url: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    runCatching {
        remoteClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val mediaType = body.contentType()?.toString() ?: "application/octet-stream"
            mediaType to body.bytes()
        }
    }.getOrNull()
}

/** data URL → Bitmap（输入区附件与 Markdown data 图片共用）。 */
internal fun decodeDataUrl(dataUrl: String): ImageBitmap? {
    val marker = "base64,"
    val index = dataUrl.indexOf(marker)
    if (index < 0) return null
    return runCatching {
        val bytes = Base64.decode(dataUrl.substring(index + marker.length), Base64.DEFAULT)
        decodeSampled(bytes)?.asImageBitmap()
    }.getOrNull()
}

/** 采样解码，最长边不超过 1600px，防止大图 OOM。 */
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
