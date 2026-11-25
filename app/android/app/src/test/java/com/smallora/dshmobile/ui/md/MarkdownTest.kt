package com.smallora.dshmobile.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析器的纯 JVM 单测（不依赖 Android/Compose 运行时）：
 * 验证 HTML 转义、行内格式、代码块原样保留、危险链接不被渲染成可点击链接、块级结构。
 */
class MarkdownTest {

    @Test
    fun escapeHtml_neutralizesTags() {
        assertEquals(
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            Markdown.escapeHtml("<script>alert(1)</script>"),
        )
        assertEquals("&amp;", Markdown.escapeHtml("&"))
        assertEquals("&quot;x&quot;", Markdown.escapeHtml("\"x\""))
    }

    @Test
    fun unescapeHtml_roundTrips() {
        val raw = "<b>a & b</b> \"q\" 's'"
        assertEquals(raw, Markdown.unescapeHtml(Markdown.escapeHtml(raw)))
    }

    @Test
    fun htmlStaysLiteralText_notRichText() {
        val runs = Markdown.inlineRuns("<b>bold</b> 与 <img src=x onerror=alert(1)>")
        // 标签作为字面量保留，且不会被解析成任何格式/链接
        assertEquals("<b>bold</b> 与 <img src=x onerror=alert(1)>", runs.joinToString("") { it.text })
        assertTrue(runs.all { it.style == Markdown.Style.NORMAL && it.link == null })
    }

    @Test
    fun inlineStyles_boldItalicCode() {
        val runs = Markdown.inlineRuns("这是 **加粗** 和 *斜体* 与 `code()`")
        assertEquals("这是 加粗 和 斜体 与 code()", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.text == "加粗" && it.style == Markdown.Style.BOLD })
        assertTrue(runs.any { it.text == "斜体" && it.style == Markdown.Style.ITALIC })
        assertTrue(runs.any { it.text == "code()" && it.style == Markdown.Style.CODE })
    }

    @Test
    fun inlineCode_doesNotParseMarkupInside() {
        val runs = Markdown.inlineRuns("`**not bold** <x>`")
        assertEquals(1, runs.size)
        assertEquals("**not bold** <x>", runs[0].text)
        assertEquals(Markdown.Style.CODE, runs[0].style)
    }

    @Test
    fun links_onlySafeSchemes() {
        val safe = Markdown.inlineRuns("见 [官网](https://example.com/a?b=1&c=2)")
        val link = safe.firstOrNull { it.link != null }
        assertEquals("见 官网", safe.joinToString("") { it.text })
        assertEquals("https://example.com/a?b=1&c=2", link?.link)

        val unsafe = Markdown.inlineRuns("[点我](javascript:alert(1))")
        assertTrue(unsafe.none { it.link != null })
        assertEquals("[点我](javascript:alert(1))", unsafe.joinToString("") { it.text })

        assertFalse(Markdown.isSafeLink("data:text/html,<script>alert(1)</script>"))
        assertFalse(Markdown.isSafeLink("file:///etc/passwd"))
        assertTrue(Markdown.isSafeLink("mailto:a@b.com"))
    }

    @Test
    fun relativePathLinks_renderLabelOnly() {
        val runs = Markdown.inlineRuns(
            "见 [ReturnReportStartupCatchUp.kt](amazonData/src/main/kotlin/com/wc/amazon/task/ReturnReportStartupCatchUp.kt) 与 " +
                "[带空格](<docs/带 空格.md>) 与 [锚点](#section)",
        )
        assertEquals("见 ReturnReportStartupCatchUp.kt 与 带空格 与 锚点", runs.joinToString("") { it.text })
        assertTrue(runs.none { it.link != null })
        assertTrue(Markdown.isRelativePathUrl("amazonData/src/A.kt"))
        assertTrue(Markdown.isRelativePathUrl("./README.md"))
        assertFalse(Markdown.isRelativePathUrl("javascript:alert(1)"))
        assertFalse(Markdown.isRelativePathUrl("https://example.com"))
        assertFalse(Markdown.isRelativePathUrl("mailto:a@b.com"))
        assertFalse(Markdown.isRelativePathUrl("//example.com/x"))
    }

    @Test
    fun codeBlock_preservesRawContentAndLanguage() {
        val source = "```kotlin\nval x = \"<script>\"\nprintln(x)\n```"
        val blocks = Markdown.parseMarkdown(source)
        assertEquals(1, blocks.size)
        val code = blocks[0] as Markdown.Block.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = \"<script>\"\nprintln(x)", code.code)
    }

    @Test
    fun blockStructure_headingsListsQuotesDividers() {
        val source = """
            # 标题
            段落第一行
            段落第二行

            - 项目 A
            - 项目 B
            1. 有序
            > 引用
            ---
        """.trimIndent()
        val blocks = Markdown.parseMarkdown(source)

        val heading = blocks.filterIsInstance<Markdown.Block.Heading>().single()
        assertEquals(1, heading.level)
        assertEquals("标题", heading.text)

        val paragraph = blocks.filterIsInstance<Markdown.Block.Paragraph>().first()
        assertEquals("段落第一行\n段落第二行", paragraph.text)

        val items = blocks.filterIsInstance<Markdown.Block.ListItem>()
        assertEquals(3, items.size)
        assertEquals("项目 A", items[0].text)
        assertFalse(items[0].ordered)
        assertEquals(1, items[2].number)
        assertTrue(items[2].ordered)

        val quote = blocks.filterIsInstance<Markdown.Block.Quote>().single()
        assertEquals("引用", quote.text)

        assertTrue(blocks.any { it == Markdown.Block.Divider })
    }

    @Test
    fun unterminatedFence_doesNotCrash() {
        val blocks = Markdown.parseMarkdown("```\nabc")
        assertEquals(1, blocks.size)
        assertEquals("abc", (blocks[0] as Markdown.Block.CodeBlock).code)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(Markdown.parseMarkdown("").isEmpty())
        assertTrue(Markdown.inlineRuns("").isEmpty())
        assertNull((Markdown.inlineRuns("plain").singleOrNull()?.link))
    }
}
