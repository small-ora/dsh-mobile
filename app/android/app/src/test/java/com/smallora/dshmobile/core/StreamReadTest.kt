package com.smallora.dshmobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** readAtMost：minSdk 31 兼容的「读至多 N 字节」实现（替代 API 33 的 readNBytes，lint 门禁）。 */
class StreamReadTest {

    @Test
    fun readsWholeShortStream() {
        val data = ByteArray(10) { it.toByte() }
        assertArrayEquals(data, readAtMost(ByteArrayInputStream(data), 100))
    }

    @Test
    fun truncatesAtMax() {
        val data = ByteArray(100) { it.toByte() }
        val out = readAtMost(ByteArrayInputStream(data), 10)
        assertEquals(10, out.size)
        assertArrayEquals(data.copyOfRange(0, 10), out)
    }

    @Test
    fun emptyStreamReturnsEmpty() {
        assertEquals(0, readAtMost(ByteArrayInputStream(ByteArray(0)), 16).size)
    }

    @Test
    fun zeroMaxReadsNothing() {
        assertEquals(0, readAtMost(ByteArrayInputStream(ByteArray(8) { 1 }), 0).size)
    }

    @Test
    fun handlesStreamReturningOneByteAtATime() {
        val data = ByteArray(7) { (it + 1).toByte() }
        val trickle = object : InputStream() {
            private var index = 0
            override fun read(): Int = if (index < data.size) data[index++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= data.size) return -1
                b[off] = data[index++]
                return 1
            }
        }
        assertArrayEquals(data, readAtMost(trickle, 64))
    }

    @Test
    fun exactlyMaxSizeIsNotTruncated() {
        val data = ByteArray(16) { (it * 3).toByte() }
        assertArrayEquals(data, readAtMost(ByteArrayInputStream(data), 16))
    }
}
