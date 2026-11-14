package com.smallora.dshmobile.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.FileEntry
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * 文件页：顶部面包屑 + 行式列表（无卡片），文件行可预览/下载，目录行可进入；
 * 顶部「+」上传（ACTION_OPEN_DOCUMENT），图片用 BitmapFactory 采样预览。
 */
@Composable
fun FilesScreen(container: AppContainer) {
    val vm: FilesViewModel = viewModel(key = "files", factory = FilesViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val context = LocalContext.current

    BackHandler(enabled = state.preview != null) { vm.closePreview() }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(context, uri)
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            vm.clearToast()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "文件",
                style = typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (state.uploading) "上传中…" else "＋",
                style = if (state.uploading) typography.labelMedium else typography.titleMedium,
                color = if (state.uploading) colors.onSurfaceVariant else colors.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !state.uploading) { uploadPicker.launch(arrayOf("*/*")) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "↻",
                style = typography.titleMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.refresh() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

        BreadcrumbBar(
            path = state.path,
            roots = state.roots,
            onNavigate = { vm.load(it) },
            onUp = { vm.up() },
        )
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

        // 刷新失败但仍有旧列表时，保留内容并给一行错误提示
        val listError = state.error
        if (listError != null && state.entries.isNotEmpty()) {
            Text(
                text = listError,
                style = typography.labelMedium,
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val entries = state.entries
            when {
                state.loading && entries.isEmpty() -> Text(
                    text = "加载中…",
                    style = typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.error != null && entries.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error.orEmpty(), style = typography.labelMedium, color = colors.error)
                    Text(
                        text = "重试",
                        style = typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.refresh() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }

                entries.isEmpty() -> Text(
                    text = "这里还没有文件",
                    style = typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            downloading = state.downloadingPath == entry.path,
                            onClick = { vm.open(entry) },
                            onDownload = { vm.download(context, entry) },
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = colors.outlineVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }

        if (toast != null) {
            Text(
                text = toast.orEmpty(),
                style = typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }

    if (state.previewLoading) {
        PreviewDialog(name = "预览", onClose = { vm.closePreview() }) {
            Text("加载中…", style = typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
    when (val preview = state.preview) {
        is FilesViewModel.Preview.Text -> PreviewDialog(name = preview.name, onClose = { vm.closePreview() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = preview.content,
                        style = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurface,
                    )
                }
            }
        }

        is FilesViewModel.Preview.Image -> PreviewDialog(name = preview.name, onClose = { vm.closePreview() }) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = preview.bitmap.asImageBitmap(),
                    contentDescription = preview.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            }
        }

        null -> Unit
    }
}

@Composable
private fun BreadcrumbBar(
    path: String?,
    roots: List<String>,
    onNavigate: (String?) -> Unit,
    onUp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    val root = remember(path, roots) {
        roots.firstOrNull { candidate ->
            path != null && (path == candidate || path.startsWith(candidate.trimEnd('/') + "/"))
        } ?: roots.firstOrNull()
    }
    val segments = remember(path, root) {
        val relative = when {
            path == null -> ""
            root != null && path.startsWith(root) -> path.removePrefix(root)
            else -> path
        }
        relative.trim('/').split('/').filter { it.isNotEmpty() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (path != null) {
            Text(
                text = "↑",
                style = typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onUp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            text = root?.substringAfterLast('/')?.ifEmpty { "根目录" } ?: "根目录",
            style = typography.labelMedium,
            color = if (segments.isEmpty()) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNavigate(root) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        var prefix = root.orEmpty()
        segments.forEach { segment ->
            prefix = "${prefix.trimEnd('/')}/$segment"
            val target = prefix
            Text("/", style = typography.labelMedium, color = colors.outlineVariant)
            Text(
                text = segment,
                style = typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onNavigate(target) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    downloading: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (entry.dir) "▸" else " ",
            style = typography.titleSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.dir) {
                Text(
                    text = "${formatSize(entry.size)} · ${formatTime(entry.mtimeMs)}",
                    style = typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                Text("文件夹", style = typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        if (!entry.dir) {
            Text(
                text = if (downloading) "保存中…" else "保存",
                style = typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !downloading, onClick = onDownload)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PreviewDialog(
    name: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "关闭",
                        style = typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 格式化

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun formatTime(mtimeMs: Long): String {
    if (mtimeMs <= 0L) return ""
    val now = Calendar.getInstance()
    val time = Calendar.getInstance().apply { timeInMillis = mtimeMs }
    val sameYear = now.get(Calendar.YEAR) == time.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
    val yesterday = sameYear && now.get(Calendar.DAY_OF_YEAR) - time.get(Calendar.DAY_OF_YEAR) == 1
    val hh = String.format("%02d", time.get(Calendar.HOUR_OF_DAY))
    val mm = String.format("%02d", time.get(Calendar.MINUTE))
    return when {
        sameDay -> "$hh:$mm"
        yesterday -> "昨天 $hh:$mm"
        sameYear -> "${time.get(Calendar.MONTH) + 1}月${time.get(Calendar.DAY_OF_MONTH)}日"
        else -> "${time.get(Calendar.YEAR)}年${time.get(Calendar.MONTH) + 1}月${time.get(Calendar.DAY_OF_MONTH)}日"
    }
}
