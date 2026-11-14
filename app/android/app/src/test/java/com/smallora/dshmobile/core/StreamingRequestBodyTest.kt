package com.smallora.dshmobile.core

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 流式上传单测（独立审查 P1：大文件不得 readBytes 全量入内存）。
 * 用 >2MB 临时文件验证：分块读取（多次 read、每次 ≤64KB）、内容/长度一致、错误路径可抛。
 */
class StreamingRequestBodyTest {

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun streamsLargeFileInChunks() {
        val size = 3 * 1024 * 1024 // 3MB > 2MB 审查阈值
        val payload = ByteArray(size) { index -> ((index * 31 + 7) and 0xff).toByte() }
        val file = File.createTempFile("dsh-upload-", ".bin").apply {
            deleteOnExit()
            writeBytes(payload)
        }

        val readCalls = AtomicInteger(0)
        val fileInput = FileInputStream(file)
        // 包装而非继承 FileInputStream：其 read(byte[]) 会走 native readBytes，绕开 3 参覆盖
        val input = object : InputStream() {
            override fun read(): Int = fileInput.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCalls.incrementAndGet()
                // 调用方每次最多请求 64KB，证明是分块而非一次性整读
                assertTrue("单次读取超过分块大小：$length", length <= StreamingRequestBody.BUFFER_SIZE)
                return fileInput.read(buffer, offset, length)
            }

            override fun close() {
                fileInput.close()
            }
        }
        val body = StreamingRequestBody("application/octet-stream".toMediaType(), size.toLong()) { input }
        assertEquals(size.toLong(), body.contentLength())

        val sink = Buffer()
        body.writeTo(sink)

        assertEquals("写入长度应与声明一致", size.toLong(), sink.size)
        assertTrue("应多次分块读取（实际 $readCalls 次），而非 readBytes 一次读入", readCalls.get() > 1)
        assertArrayEquals("流式写入内容不得损坏", sha256(payload), sha256(sink.readByteArray()))
    }

    @Test
    fun unknownLengthStreamsAllBytesWithChunkedEncoding() {
        val payload = ByteArray(256 * 1024) { index -> (index and 0xff).toByte() }
        val body = StreamingRequestBody(null, -1L) { payload.inputStream() }
        assertEquals("未知长度应使用 chunked（-1）", -1L, body.contentLength())
        val sink = Buffer()
        body.writeTo(sink)
        assertEquals(payload.size.toLong(), sink.size)
        assertArrayEquals(sha256(payload), sha256(sink.readByteArray()))
    }

    @Test
    fun declaredLengthMismatchThrows() {
        val body = StreamingRequestBody(null, 1024L) { ByteArray(100).inputStream() }
        val error = runCatching { body.writeTo(Buffer()) }.exceptionOrNull()
        assertTrue("长度不一致应抛 IOException，实际: $error", error is IOException)
    }

    @Test
    fun openStreamFailureThrows() {
        val body = StreamingRequestBody(null, 10L) { null }
        val error = runCatching { body.writeTo(Buffer()) }.exceptionOrNull()
        assertTrue("无法打开文件应抛 IOException，实际: $error", error is IOException)
    }

    @Test
    fun inputStreamIsClosedAfterWrite() {
        var closed = false
        val base: InputStream = ByteArray(10).inputStream()
        val tracking = object : InputStream() {
            override fun read(): Int = base.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = base.read(b, off, len)

            override fun close() {
                closed = true
                base.close()
            }
        }
        StreamingRequestBody(null, 10L) { tracking }.writeTo(Buffer())
        assertTrue("上传结束应关闭输入流（避免 fd 泄漏）", closed)
    }
}
