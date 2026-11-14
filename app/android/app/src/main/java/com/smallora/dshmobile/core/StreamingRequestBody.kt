package com.smallora.dshmobile.core

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

/**
 * 流式上传 body（独立审查 P1）：writeTo 时按 64KB 分块从 InputStream 写入 sink，
 * 峰值内存 O(64KB)，不会像 readBytes() 那样把大文件整体载入导致 OOM。
 *
 * 纯 JVM 类（只依赖 okhttp/okio），可直接单测；FilesClient 用 ContentResolver 的
 * InputStream 工厂构造它 POST 到 `/api/files/upload`。
 */
class StreamingRequestBody(
    private val contentTypeValue: MediaType?,
    private val length: Long,
    private val openStream: () -> InputStream?,
) : RequestBody() {

    override fun contentType(): MediaType? = contentTypeValue

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = openStream() ?: throw IOException("无法读取上传文件")
        input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                total += read
            }
            if (length >= 0 && total != length) {
                throw IOException("上传大小不一致：声明 $length 字节，实际 $total 字节")
            }
        }
    }

    companion object {
        /** 分块大小：64KB（远小于常见大文件，避免一次性分配）。 */
        const val BUFFER_SIZE = 64 * 1024
    }
}
