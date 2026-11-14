package com.smallora.dshmobile.core

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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片附件引用解析 + 附件字节获取单测（task-17）。
 *
 * 真实 DSH 结构：`{type:'image', attachment:{attachmentId:'sha256:<64hex>', mediaType, name, bytes, width, height}}`
 * （不在 content 里内联 data URL）——App 之前按 data URL 解，所以图片显示不出来。
 */
class ImageRefTest {

    @Test
    fun parsesAttachmentAndInlineDataUrlBlocks() {
        val content = JSONArray(
            """
            [
              {"type": "image", "attachment": {
                "attachmentId": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "mediaType": "image/webp", "name": "shot.webp", "bytes": 1234, "width": 800, "height": 600}},
              {"type": "image", "data": "data:image/png;base64,QUJD", "mediaType": "image/png"},
              {"type": "image", "data": "QUJD", "mediaType": "image/jpeg"},
              {"type": "image", "attachment": {"mediaType": "image/png"}},
              {"type": "image"},
              {"type": "text", "text": "不是图片块"}
            ]
            """.trimIndent(),
        )
        val refs = Json.parseImageRefs(content, "sess-1")
        assertEquals(3, refs.size)

        val attachment = refs[0] as ImageRef.Attachment
        assertEquals("sess-1", attachment.sessionId)
        assertEquals("a".repeat(64), attachment.attachmentId)
        assertEquals("image/webp", attachment.mediaType)
        assertEquals("shot.webp", attachment.name)

        assertEquals(ImageRef.DataUrl("data:image/png;base64,QUJD"), refs[1])
        assertEquals(ImageRef.DataUrl("data:image/jpeg;base64,QUJD"), refs[2])
    }

    @Test
    fun parsesImagesFromRealChatEvents() {
        val snapshot = JSONObject(
            """
            {
              "type": "snapshot", "cursor": 2, "hasMore": false,
              "records": [
                {"type": "event", "event": {"type": "user/message", "seq": 1, "time": 1, "data": {
                  "source": {"kind": "user"},
                  "message": {"role": "user", "content": [
                    {"type": "text", "text": "看图"},
                    {"type": "image", "attachment": {
                      "attachmentId": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "mediaType": "image/png", "name": "u.png"}}
                  ]}}}},
                {"type": "event", "event": {"type": "assistant/message", "seq": 2, "time": 2, "data": {
                  "message": {"role": "assistant", "content": [
                    {"type": "text", "text": "收到图片"},
                    {"type": "image", "attachment": {
                      "attachmentId": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                      "mediaType": "image/jpeg", "name": "a.jpg"}}
                  ]}}}}
              ]
            }
            """.trimIndent(),
        )
        val state = ChatReducer.applyValue(ChatReducer.initial("s-real"), snapshot)
        val user = state.items[0] as ChatItem.User
        assertEquals("看图", user.text)
        assertEquals(1, user.images.size)
        val userImage = user.images[0] as ImageRef.Attachment
        assertEquals("s-real", userImage.sessionId)
        assertEquals("b".repeat(64), userImage.attachmentId)

        val assistant = state.items[1] as ChatItem.Assistant
        assertEquals(1, assistant.images.size)
        assertEquals("image/jpeg", (assistant.images[0] as ImageRef.Attachment).mediaType)

        // 流式占位项的 images 为空（等 assistant/message 定稿）
        val streaming = ChatReducer.applyAssistantStream(
            ChatReducer.initial("s-real"),
            JSONObject("""{"type":"start","attemptId":"a1","revision":1}"""),
        )
        assertTrue((streaming.items.single() as ChatItem.Assistant).images.isEmpty())
    }

    private class FakeAttachmentApi(
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
    fun fetchAttachmentBuildsUrlWithBearerAndReturnsBytes() = runBlocking {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val api = FakeAttachmentApi(200, payload, "image/webp")
        api.setSession("http://127.0.0.1:8790", "tok-123")

        val (mediaType, bytes) = api.fetchAttachment("sess-1", "abc123")

        assertEquals("image/webp", mediaType)
        assertArrayEquals(payload, bytes)
        val request = api.lastRequest ?: error("缺少请求")
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("/api/attachment", request.url.encodedPath)
        assertEquals("sess-1", request.url.queryParameter("sessionId"))
        assertEquals("abc123", request.url.queryParameter("attachmentId"))
    }

    @Test
    fun fetchAttachmentMaps401ToUnauthorized() {
        runBlocking {
            val api = FakeAttachmentApi(401, """{"error":"unauthorized"}""".toByteArray(), "application/json")
            api.setSession("http://127.0.0.1:8790", "expired-token")
            assertThrows(UnauthorizedException::class.java) {
                runBlocking { api.fetchAttachment("sess-1", "abc123") }
            }
        }
    }

    @Test
    fun fetchAttachmentMaps404ToApiException() = runBlocking {
        val api = FakeAttachmentApi(404, """{"error":"not-found"}""".toByteArray(), "application/json")
        api.setSession("http://127.0.0.1:8790", "tok")
        val error = runCatching { api.fetchAttachment("sess-1", "abc123") }.exceptionOrNull()
        assertTrue("404 应抛 ApiException，实际: $error", error is ApiException)
        assertEquals("not-found", (error as ApiException).code)
        assertEquals(404, (error as ApiException).httpStatus)
    }
}
