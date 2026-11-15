package com.smallora.dshmobile.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具结果中的图片解析（Tool 结果不再只显示「[图片]」文本，而是渲染真实图片）。 */
class ToolImageTest {

    private fun event(type: String, seq: Int, data: String): JSONObject = JSONObject(
        """{"type":"$type","seq":$seq,"time":$seq,"data":$data}""",
    )

    @Test
    fun toolResultImageIsParsedAndTextKeepsOnlyTextBlocks() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            event("tool/call", 1, """{"callId":"c1","name":"screenshot","arguments":"{}"}"""),
        )
        state = ChatReducer.applyEvent(
            state,
            event(
                "tool/result",
                2,
                """
                {"message":{"toolCallId":"c1","content":[
                  {"type":"text","text":"已截图"},
                  {"type":"image","mediaType":"image/png","data":"QUJD"}
                ]}}
                """.trimIndent(),
            ),
        )

        val tool = state.items.single() as ChatItem.Tool
        assertEquals("已截图", tool.result)
        assertEquals(1, tool.images.size)
        val image = tool.images.single()
        assertTrue("内联 data 图片应解析为 DataUrl", image is ImageRef.DataUrl)
        assertEquals("data:image/png;base64,QUJD", (image as ImageRef.DataUrl).url)
        assertEquals("screenshot", tool.name)
        assertEquals(false, tool.running)
    }

    @Test
    fun toolResultAttachmentImageIsParsedWithSessionId() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            event("tool/call", 1, """{"callId":"c2","name":"view_image","arguments":"{}"}"""),
        )
        state = ChatReducer.applyEvent(
            state,
            event(
                "tool/result",
                2,
                """
                {"message":{"toolCallId":"c2","content":[
                  {"type":"image","attachment":{"attachmentId":"sha256:abc123","mediaType":"image/jpeg"}}
                ]}}
                """.trimIndent(),
            ),
        )

        val tool = state.items.single() as ChatItem.Tool
        assertEquals(1, tool.images.size)
        val image = tool.images.single()
        assertTrue(image is ImageRef.Attachment)
        assertEquals("abc123", (image as ImageRef.Attachment).attachmentId)
        assertEquals("s1", image.sessionId)
        // 只有图片没有文本时 result 为空（不显示「[图片]」占位）
        assertTrue(tool.result.isNullOrBlank())
    }

    @Test
    fun toolResultWithoutImagesBehavesAsBefore() {
        var state = ChatReducer.initial("s1")
        state = ChatReducer.applyEvent(
            state,
            event("tool/call", 1, """{"callId":"c3","name":"bash","arguments":"{}"}"""),
        )
        state = ChatReducer.applyEvent(
            state,
            event("tool/result", 2, """{"message":{"toolCallId":"c3","content":[{"type":"text","text":"ok"}]}}"""),
        )

        val tool = state.items.single() as ChatItem.Tool
        assertEquals("ok", tool.result)
        assertTrue(tool.images.isEmpty())
    }
}
