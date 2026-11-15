package com.smallora.dshmobile.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 文件浏览/上传/下载（interfaces §2.5）。
 * 下载写入 MediaStore.Downloads（Download/dsh-mobile/），无需存储权限（API 29+）。
 */
class FilesClient(private val api: ApiClient) {

    suspend fun list(path: String?): FileListing {
        val query = if (path.isNullOrBlank()) "" else "?path=" + encode(path)
        val resp = api.getJson("/api/files$query")
        val roots = resp.arrOrNull("roots")?.toStringList() ?: emptyList()
        val entries = resp.arrOrNull("entries")?.toObjectList()?.map { item ->
            FileEntry(
                name = item.strOrNull("name") ?: "",
                path = item.strOrNull("path") ?: "",
                dir = item.strOrNull("type") == "dir" || item.boolOr("dir", false),
                size = item.longOr("size", 0L),
                mtimeMs = item.longOr("mtimeMs", item.longOr("mtime", 0L)),
            )
        } ?: emptyList()
        return FileListing(path = resp.strOrNull("path") ?: (path ?: ""), roots = roots, entries = entries)
    }

    /** 文本/图片预览（≤1MiB 由网关保证；此处再限制 4MiB 防内存）。 */
    suspend fun preview(path: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
        val response = api.download(path)
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body?.byteStream()?.let { readAtMost(it, 4 * 1024 * 1024) }
                ?: return@withContext null
            val mime = resp.header("Content-Type")?.substringBefore(';') ?: mimeOf(path)
            mime to bytes
        }
    }

    suspend fun downloadTo(context: Context, path: String): Boolean = withContext(Dispatchers.IO) {
        val response = try {
            api.download(path)
        } catch (_: Exception) {
            return@withContext false
        }
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val name = contentDispositionName(resp.header("Content-Disposition"))
                ?: path.substringAfterLast('/', "download.bin")
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/dsh-mobile",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            try {
                val stream = resp.body?.byteStream() ?: return@withContext false
                resolver.openOutputStream(uri)?.use { out -> stream.copyTo(out, 64 * 1024) }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                true
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        }
    }

    /**
     * 流式上传（独立审查 P1）：不再 readBytes() 全量入内存；拿到 ContentResolver InputStream
     * 后交给 StreamingRequestBody 分块写入，contentLength 取 OpenableColumns.SIZE。
     */
    suspend fun upload(context: Context, dir: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: "upload-${System.currentTimeMillis()}"
        val size = querySize(resolver, uri) ?: -1L
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: mimeOf(name)
        try {
            api.uploadStream(
                dir = dir,
                name = name,
                contentLength = size,
                contentType = mime,
                openStream = { resolver.openInputStream(uri) },
            )
            true
        } catch (_: UnauthorizedException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 只查文件大小（OpenableColumns.SIZE），不读取内容。 */
    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun contentDispositionName(header: String?): String? {
        if (header == null) return null
        val marker = "filename*=UTF-8''"
        val raw = if (header.contains(marker)) {
            header.substringAfter(marker).substringBefore(';').trim()
        } else if (header.contains("filename=")) {
            header.substringAfter("filename=").substringBefore(';').trim().trim('"')
        } else {
            return null
        }
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log", "json", "kt", "js", "ts", "py", "sh", "yml", "yaml", "xml", "html", "css" ->
            "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** 管理接口：改密、设备列表/撤销（无隧道管理，SPEC v3）。 */
class AdminClient(private val api: ApiClient) {

    suspend fun changePassword(current: String, next: String): Boolean {
        return try {
            api.postJson(
                "/api/password",
                JSONObject().put("current", current).put("next", next),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun devices(): List<DeviceInfo> {
        val resp = api.getJson("/api/devices")
        return resp.arrOrNull("items")?.toObjectList()?.mapNotNull { item ->
            val id = item.strOrNull("id") ?: return@mapNotNull null
            DeviceInfo(id = id, name = item.strOrNull("name") ?: "未知设备")
        } ?: emptyList()
    }

    suspend fun revokeDevice(id: String): Boolean {
        return try {
            val encoded = URLEncoder.encode(id, "UTF-8")
            api.deleteJson("/api/devices/$encoded")
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * 读取至多 [max] 字节（minSdk 31 兼容实现，替代 API 33 才有的 `InputStream.readNBytes`，lint NewApi 门禁）。
 * 超过上限的部分丢弃；流提前结束则返回已读内容。
 */
internal fun readAtMost(stream: java.io.InputStream, max: Int): ByteArray {
    require(max >= 0) { "max must be >= 0" }
    val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var remaining = max
    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}
