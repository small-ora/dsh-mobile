package com.smallora.dshmobile.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.FileEntry
import com.smallora.dshmobile.core.FileListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件页：允许根浏览（面包屑）、文本/图片预览、下载到 Downloads、上传。
 * 预览走 ApiClient.download 取字节（不引 Coil，图片用 BitmapFactory 采样解码）。
 */
class FilesViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface Preview {
        data class Text(val name: String, val content: String) : Preview
        data class Image(val name: String, val bitmap: Bitmap) : Preview
    }

    data class UiState(
        val path: String? = null,
        val roots: List<String> = emptyList(),
        val entries: List<FileEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val preview: Preview? = null,
        val previewLoading: Boolean = false,
        val uploading: Boolean = false,
        val downloadingPath: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        load(null)
    }

    fun clearToast() {
        _toast.value = null
    }

    fun load(path: String?) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { container.files.list(path) }
                .onSuccess { listing -> applyListing(listing) }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = describe(error))
                    }
                }
        }
    }

    fun refresh() {
        load(_state.value.path)
    }

    fun open(entry: FileEntry) {
        if (entry.dir) {
            load(entry.path)
        } else {
            preview(entry)
        }
    }

    fun up() {
        val current = _state.value.path ?: return
        val trimmed = current.trimEnd('/')
        val parent = if (trimmed.count { it == '/' } <= 1) null else trimmed.substringBeforeLast('/')
        load(parent)
    }

    fun download(context: Context, entry: FileEntry) {
        if (_state.value.downloadingPath != null) return
        viewModelScope.launch {
            _state.update { it.copy(downloadingPath = entry.path) }
            val ok = runCatching { container.files.downloadTo(context, entry.path) }.getOrDefault(false)
            _toast.value = if (ok) "已保存到「下载/dsh-mobile」" else "下载失败"
            _state.update { it.copy(downloadingPath = null) }
        }
    }

    fun upload(context: Context, uri: Uri) {
        if (_state.value.uploading) return
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
            val dir = _state.value.path ?: _state.value.roots.firstOrNull() ?: ""
            val ok = runCatching { container.files.upload(context, dir, uri) }.getOrDefault(false)
            _toast.value = if (ok) "上传完成" else "上传失败"
            _state.update { it.copy(uploading = false) }
            if (ok) load(_state.value.path)
        }
    }

    fun closePreview() {
        _state.update { it.copy(preview = null, previewLoading = false) }
    }

    fun preview(entry: FileEntry) {
        if (_state.value.previewLoading) return
        viewModelScope.launch {
            _state.update { it.copy(previewLoading = true, preview = null, error = null) }
            runCatching { withContext(Dispatchers.IO) { fetchPreview(entry) } }
                .onSuccess { preview ->
                    _state.update { it.copy(preview = preview, previewLoading = false) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(previewLoading = false, error = describe(error))
                    }
                }
        }
    }

    private suspend fun fetchPreview(entry: FileEntry): Preview {
        val (mime, bytes) = container.files.preview(entry.path)
            ?: throw IllegalStateException("该文件无法预览（可能是二进制或超过大小限制）")
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val isImage = mime.startsWith("image/") || extension in IMAGE_EXTENSIONS
        if (isImage) {
            if (bytes.size > MAX_IMAGE_BYTES) throw PreviewTooLarge()
            val bitmap = decodeSampled(bytes) ?: throw IllegalStateException("图片解码失败")
            return Preview.Image(entry.name, bitmap)
        }
        if (bytes.size > MAX_TEXT_BYTES) throw PreviewTooLarge()
        if (looksBinary(bytes)) throw IllegalStateException("该文件不支持预览")
        return Preview.Text(entry.name, bytes.toString(Charsets.UTF_8))
    }

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

    private fun applyListing(listing: FileListing) {
        _state.update {
            it.copy(
                path = listing.path,
                roots = if (listing.roots.isNotEmpty()) listing.roots else it.roots,
                entries = listing.entries.sortedWith(compareByDescending<FileEntry> { e -> e.dir }.thenBy { e -> e.name.lowercase() }),
                loading = false,
                error = null,
            )
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 4096)
        for (i in 0 until limit) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun describe(error: Throwable): String = when (error) {
        is PreviewTooLarge -> "文件过大，无法预览（文本 ≤ 1MiB / 图片 ≤ 8MiB）"
        else -> error.message ?: "操作失败"
    }

    private class PreviewTooLarge : Exception()

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private const val MAX_TEXT_BYTES = 1024 * 1024L
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024L
        private const val MAX_IMAGE_EDGE = 2048

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FilesViewModel(container) }
        }
    }
}
