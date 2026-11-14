package com.smallora.dshmobile.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 图片路径解析 + 文件字节获取单测（task-19）。
 * 覆盖：http/data/file/绝对/相对（含 ./ ../、百分号编码）、cwd 为空、未知 scheme；
 * fetchFile 的 URL 编码、Bearer、mediaType 推断、401/403/404 映射；
 * ChatUiState.cwd 的 snapshot header / openSession 预填。
 */
class MarkdownImageTest {

    // ------------------------------------------------------------ 路径解析

    @Test
    fun resolvesRemoteAndDataUrls() {
        assertEquals(
            MarkdownImageSource.Remote("https://example.com/a.png"),
            resolveMarkdownImageSrc("https://example.com/a.png", null),
        )
        assertEquals(
            MarkdownImageSource.Remote("http://192.168.1.10/a.png"),
            resolveMarkdownImageSrc("  http://192.168.1.10/a.png  ", "/root"),
        )
        assertEquals(
            MarkdownImageSource.DataUrl("data:image/png;base64,QUJD"),
            resolveMarkdownImageSrc("data:image/png;base64,QUJD", null),
        )
    }

    @Test
    fun resolvesAbsolutePathsAndFileScheme() {
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/dsh-app/assets/a.png"),
            resolveMarkdownImageSrc("/root/dsh-app/assets/a.png", null),
        )
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/dsh-app/a.png"),
            resolveMarkdownImageSrc("/root/dsh-app/./a.png", null),
        )
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/a.png"),
            resolveMarkdownImageSrc("file:///root/a.png", null),
        )
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/a.png"),
            resolveMarkdownImageSrc("file://localhost/root/a.png", null),
        )
    }

    @Test
    fun resolvesRelativePathsAgainstCwd() {
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/dsh-app/assets/a.png"),
            resolveMarkdownImageSrc("assets/a.png", "/root/dsh-app"),
        )
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/dsh-app/assets/a.png"),
            resolveMarkdownImageSrc("./assets/a.png", "/root/dsh-app/"),
        )
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/img/a.png"),
            resolveMarkdownImageSrc("../img/a.png", "/root/dsh-app"),
        )
        // file:// 相对路径同样按 cwd 解析
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/dsh-app/assets/a.png"),
            resolveMarkdownImageSrc("file://assets/a.png", "/root/dsh-app"),
        )
    }

    @Test
    fun relativePathWithoutCwdOrInvalidReturnsNull() {
        assertNull(resolveMarkdownImageSrc("assets/a.png", null))
        assertNull(resolveMarkdownImageSrc("assets/a.png", "   "))
        assertNull(resolveMarkdownImageSrc("", "/root"))
        assertNull(resolveMarkdownImageSrc("   ", "/root"))
        assertNull(resolveMarkdownImageSrc(null, "/root"))
        assertNull(resolveMarkdownImageSrc("ftp://example.com/a.png", "/root"))
        assertNull(resolveMarkdownImageSrc("mailto:a@b.c", "/root"))
    }

    @Test
    fun decodesPercentEncodedPaths() {
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/dsh-app/assets/图 1.png"),
            resolveMarkdownImageSrc("assets/%E5%9B%BE%201.png", "/root/dsh-app"),
        )
        // '+' 是文件名合法字符，不应被解码成空格
        assertEquals(
            MarkdownImageSource.WorkspacePath("/root/a+b.png"),
            resolveMarkdownImageSrc("a+b.png", "/root"),
        )
    }

    // ------------------------------------------------------------ fetchFile

    private class FakeFileApi(
        private val code: Int,
        private val payload: ByteArray,
        private val contentType: String?,
    ) : ApiClient() {
        @Volatile
        var lastRequest: Request? = null

        override suspend fun executeStream(request: Request): Response {
            lastRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .apply { contentType?.let { header("Content-Type", it) } }
                .body(payload.toResponseBody(contentType?.toMediaTypeOrNull()))
                .build()
        }
    }

    @Test
    fun fetchFileEncodesPathAndReadsBytes() = runBlocking {
        val payload = byteArrayOf(9, 8, 7)
        val api = FakeFileApi(200, payload, "image/png")
        api.setSession("http://127.0.0.1:8790", "tok-file")

        val path = "/root/dsh-app/图 1.png"
        val (mediaType, bytes) = api.fetchFile(path)

        assertEquals("image/png", mediaType)
        assertArrayEquals(payload, bytes)
        val request = api.lastRequest ?: error("缺少请求")
        assertEquals("/api/files/download", request.url.encodedPath)
        assertEquals("Bearer tok-file", request.header("Authorization"))
        assertEquals("路径必须被 URL 编码后按原值还原", path, request.url.queryParameter("path"))
    }

    @Test
    fun fetchFileGuessesMediaTypeWhenHeaderMissing() = runBlocking {
        val api = FakeFileApi(200, byteArrayOf(1), null)
        api.setSession("http://127.0.0.1:8790", "tok")
        assertEquals("image/webp", api.fetchFile("/root/a.webp").first)
        assertEquals("image/jpeg", api.fetchFile("/root/b.JPEG").first)
        assertEquals("image/gif", api.fetchFile("/root/c.gif").first)
        assertEquals("image/png", api.fetchFile("/root/d.png").first)
    }

    @Test
    fun fetchFileMapsHttpErrors() = runBlocking {
        val api401 = FakeFileApi(401, """{"error":"unauthorized"}""".toByteArray(), "application/json")
        api401.setSession("http://127.0.0.1:8790", "expired")
        assertThrows(UnauthorizedException::class.java) {
            runBlocking { api401.fetchFile("/root/a.png") }
        }

        val api404 = FakeFileApi(404, """{"error":"not-found"}""".toByteArray(), "application/json")
        api404.setSession("http://127.0.0.1:8790", "tok")
        val notFound = runCatching { api404.fetchFile("/root/a.png") }.exceptionOrNull()
        assertTrue("404 应抛 ApiException，实际: $notFound", notFound is ApiException)
        assertEquals(404, (notFound as ApiException).httpStatus)

        val api403 = FakeFileApi(403, """{"error":"forbidden-path"}""".toByteArray(), "application/json")
        api403.setSession("http://127.0.0.1:8790", "tok")
        val forbidden = runCatching { api403.fetchFile("/etc/passwd") }.exceptionOrNull()
        assertTrue("403 应抛 ApiException，实际: $forbidden", forbidden is ApiException)
        assertEquals(403, (forbidden as ApiException).httpStatus)
        assertEquals("forbidden-path", (forbidden as ApiException).code)
    }

    // ------------------------------------------------------------ ChatUiState.cwd

    @Test
    fun snapshotHeaderCwdIsStored() {
        val snapshot = JSONObject(
            """{"type":"snapshot","cursor":1,"hasMore":false,
               "header":{"version":4,"id":"s1","createdAt":1,"cwd":"/root/dsh-app","isSeeded":false},
               "projections":{"kind":"sequenced","asOfSeq":1,"values":{}},"records":[]}""",
        )
        val state = ChatReducer.applyValue(ChatReducer.initial("s1"), snapshot)
        assertEquals("/root/dsh-app", state.cwd)
    }

    @Test
    fun openSessionPrefillsCwdFromSessionRow() = runBlocking {
        val api = NoopStreamApi()
        val repo = SessionRepository(api)
        val row = SessionRow(
            id = "s1", title = "会话", preview = null, updatedAt = 1, running = false,
            blank = false, parentSessionId = null, cwd = "/root/dsh-app", goal = null,
            todos = emptyList(), selection = null, agentAvailable = true,
        )
        repo.applySessionRows(listOf(row))
        try {
            repo.openSession("s1")
            assertEquals("/root/dsh-app", repo.chatState("s1").value.cwd)
        } finally {
            repo.releaseSession("s1")
            repo.stop()
        }
    }

    private class NoopStreamApi : ApiClient() {
        override suspend fun openStream(endpoint: String, args: JSONObject): String = "stream-1"

        override suspend fun pollStream(streamId: String, since: Long, waitMs: Long): StreamPoll {
            delay(20)
            return StreamPoll(emptyList(), since, closed = false, reset = false, error = null)
        }

        override suspend fun cancelStream(streamId: String) = Unit

        override suspend fun rpc(method: String, args: JSONObject): JSONObject = JSONObject()

        override suspend fun rpcArray(method: String, args: JSONObject): JSONArray = JSONArray()
    }
}
