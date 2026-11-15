package com.smallora.dshmobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MarkdownImage.split] 的纯 JVM 单测：剥离语法、段落归属、尖括号路径、代码块/行内 code/表格不解析。 */
class MarkdownImageTest {

    @Test
    fun simpleImage_removedFromText_andAttachedToParagraph() {
        val chunks = MarkdownImage.split("看这张图：\n\n![启动器](.ai/dsh-mobile/evidence/screens/a.png)\n\n结束")

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].images.isEmpty())
        assertEquals("看这张图：", chunks[0].markdown)
        assertEquals(1, chunks[1].images.size)
        assertEquals(".ai/dsh-mobile/evidence/screens/a.png", chunks[1].images[0].src)
        assertEquals("启动器", chunks[1].images[0].alt)
        // 图片语法不残留在正文中
        assertEquals("", chunks[1].markdown)
        assertEquals("结束", chunks[2].markdown)
    }

    @Test
    fun angleBracketSrc_stripsBrackets_andKeepsSpaces() {
        val chunks = MarkdownImage.split("![a](<.ai/evidence/screen shots/a b.png>)")

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].images.size)
        assertEquals(".ai/evidence/screen shots/a b.png", chunks[0].images[0].src)
    }

    @Test
    fun multipleImagesSameParagraph_keepOrder() {
        val chunks = MarkdownImage.split("前 ![a](x.png) 中 ![b](y.png) 后")

        assertEquals(1, chunks.size)
        assertEquals(2, chunks[0].images.size)
        assertEquals(listOf("x.png", "y.png"), chunks[0].images.map { it.src })
        assertEquals("前 中 后", chunks[0].markdown.replace(Regex(" +"), " "))
    }

    @Test
    fun fencedCodeBlock_notParsed() {
        val source = "```\n![a](x.png)\n```"
        val chunks = MarkdownImage.split(source)

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].images.isEmpty())
        assertTrue(chunks[0].markdown.contains("![a](x.png)"))
    }

    @Test
    fun inlineCode_notParsed() {
        val chunks = MarkdownImage.split("写法 `![a](x.png)` 示例")

        assertEquals(0, chunks[0].images.size)
        assertTrue(chunks[0].markdown.contains("![a](x.png)"))
    }

    @Test
    fun tableRow_imageExtracted_andAltKeptAsCellPlaceholder() {
        val chunks = MarkdownImage.split("| 列 | 图 |\n| --- | --- |\n| a | ![x](y.png) |")

        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].images.size)
        assertEquals("y.png", chunks[0].images[0].src)
        // 表格单元格保留 alt，避免出现空单元格；图片语法本身从正文移除
        assertTrue(chunks[0].markdown.contains("| a | x |"))
        assertTrue(!chunks[0].markdown.contains("![x](y.png)"))
    }

    @Test
    fun tableRow_imageWithEmptyAlt_usesPlaceholderText() {
        val chunks = MarkdownImage.split("| a | ![](y.png) |")

        assertEquals(1, chunks[0].images.size)
        assertTrue(chunks[0].markdown.contains("[图片]"))
        assertTrue(!chunks[0].markdown.contains("![]("))
    }

    @Test
    fun escapedBang_notParsed() {
        val chunks = MarkdownImage.split("\\![a](x.png)")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].images.isEmpty())
    }

    @Test
    fun imageBetweenTwoParagraphs_attachedToOwnParagraph() {
        val source = "第一段\n\n![a](1.png)\n\n第二段\n正文"
        val chunks = MarkdownImage.split(source)

        assertEquals(3, chunks.size)
        assertEquals("第一段", chunks[0].markdown)
        assertEquals(1, chunks[1].images.size)
        assertEquals("第二段\n正文", chunks[2].markdown)
    }

    @Test
    fun plainText_noImages_singleChunk() {
        val chunks = MarkdownImage.split("普通文本\n第二行")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].images.isEmpty())
        assertEquals("普通文本\n第二行", chunks[0].markdown)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(MarkdownImage.split("").isEmpty())
    }
}
