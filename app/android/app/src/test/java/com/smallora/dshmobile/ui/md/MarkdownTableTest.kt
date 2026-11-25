package com.smallora.dshmobile.ui.md

import com.smallora.dshmobile.ui.chat.MarkdownImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GFM 管道表格解析的纯 JVM 单测（不依赖 Android/Compose）：
 * 表头/分隔行/数据行、前后竖线省略、转义竖线、行内 code、对齐、容错与流式前缀。
 */
class MarkdownTableTest {

    private fun tables(raw: String) = Markdown.parseMarkdown(raw).filterIsInstance<Markdown.Block.Table>()

    @Test
    fun twoColumnTable_withOuterPipes() {
        val raw = """
            | 位置 | 处理 |
            | --- | --- |
            | [A.kt](amazonData/src/main/kotlin/A.kt) | 删除组件（prod 启动监听） |
            | B.kt | 保留 |
        """.trimIndent()
        val table = tables(raw).single()
        assertEquals(listOf("位置", "处理"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(
            listOf("[A.kt](amazonData/src/main/kotlin/A.kt)", "删除组件（prod 启动监听）"),
            table.rows[0],
        )
        assertEquals(listOf("B.kt", "保留"), table.rows[1])
        assertEquals(listOf(Markdown.Align.NONE, Markdown.Align.NONE), table.align)
    }

    @Test
    fun indentedRowsWithLeadingPipeHaveNoEmptyColumn() {
        val raw = "  | a | b |\n  | --- | --- |\n  | 1 | 2 |"
        val table = tables(raw).single()
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun tableWithoutOuterPipes() {
        val raw = "a | b\n--- | ---\n1 | 2"
        val table = tables(raw).single()
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun alignmentMarkersParsed() {
        val raw = "| a | b | c | d |\n| :--- | ---: | :---: | --- |\n| 1 | 2 | 3 | 4 |"
        val table = tables(raw).single()
        assertEquals(
            listOf(Markdown.Align.LEFT, Markdown.Align.RIGHT, Markdown.Align.CENTER, Markdown.Align.NONE),
            table.align,
        )
    }

    @Test
    fun inlineMarkdownKeptInCells() {
        val raw = "| **粗体** | `code()` |\n| --- | --- |\n| *斜体* | 3 |"
        val table = tables(raw).single()
        assertEquals(listOf("**粗体**", "`code()`"), table.header)
        assertEquals(listOf("*斜体*", "3"), table.rows[0])

        // 单元格内的行内格式要在渲染时真正生效（而不是原样输出星号/反引号）
        val bold = Markdown.inlineRuns(table.header[0])
        assertEquals("粗体", bold.joinToString("") { it.text })
        assertTrue(bold.any { it.style == Markdown.Style.BOLD })
        val code = Markdown.inlineRuns(table.header[1])
        assertEquals("code()", code.joinToString("") { it.text })
        assertTrue(code.any { it.style == Markdown.Style.CODE })
        val italic = Markdown.inlineRuns(table.rows[0][0])
        assertEquals("斜体", italic.joinToString("") { it.text })
        assertTrue(italic.any { it.style == Markdown.Style.ITALIC })
    }

    @Test
    fun escapedPipeAndCodeSpanPipeDoNotSplitCells() {
        val raw = "| a \\| b | `x | y` |\n| --- | --- |\n| 1 | 2 |"
        val table = tables(raw).single()
        assertEquals(listOf("a | b", "`x | y`"), table.header)
        assertEquals(1, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
    }

    @Test
    fun escapedPipeInsideCodeSpanIsUnescapedButDoesNotSplit() {
        val raw = "| a | `x \\| y` |\n| --- | --- |\n| 1 \\| 2 | `p \\| q` |"
        val table = tables(raw).single()
        assertEquals(listOf("a", "`x | y`"), table.header)
        assertEquals(listOf("1 | 2", "`p | q`"), table.rows[0])
        val cellRuns = Markdown.inlineRuns(table.header[1])
        assertEquals("x | y", cellRuns.joinToString("") { it.text })
        assertTrue(cellRuns.all { it.style == Markdown.Style.CODE })
    }

    @Test
    fun malformedDelimiterFallsBackToParagraph() {
        val blocks = Markdown.parseMarkdown("| a | b |\n| -- | not-delimiter |")
        assertTrue(blocks.none { it is Markdown.Block.Table })
        val paragraph = blocks.filterIsInstance<Markdown.Block.Paragraph>().single()
        assertEquals("| a | b |\n| -- | not-delimiter |", paragraph.text)
    }

    @Test
    fun columnCountMismatchFallsBackToParagraph() {
        assertTrue(tables("| a | b |\n| --- |").isEmpty())
        assertTrue(tables("| a |\n| --- | --- |").isEmpty())
    }

    @Test
    fun tableEndsAtBlankLineAndKeepsFollowingParagraph() {
        val blocks = Markdown.parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n\n普通段落")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Markdown.Block.Table)
        assertEquals("普通段落", (blocks[1] as Markdown.Block.Paragraph).text)
    }

    @Test
    fun tableEndsAtNonTableBlockStart() {
        val heading = Markdown.parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n# 标题")
        assertEquals(2, heading.size)
        assertTrue(heading[0] is Markdown.Block.Table)
        assertEquals(1, (heading[1] as Markdown.Block.Heading).level)

        // 表格后的列表 / 引用 / 围栏 / 无竖线段落都不能被吞进表格
        val list = Markdown.parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n- 列表项")
        assertTrue(list[0] is Markdown.Block.Table)
        assertEquals(1, list.filterIsInstance<Markdown.Block.ListItem>().size)

        val quote = Markdown.parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n> 引用")
        assertTrue(quote[0] is Markdown.Block.Table)
        assertEquals("引用", (quote[1] as Markdown.Block.Quote).text)

        val fence = Markdown.parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n```\ncode\n```")
        assertTrue(fence[0] is Markdown.Block.Table)
        assertEquals("code", (fence[1] as Markdown.Block.CodeBlock).code)

        val plain = Markdown.parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n普通段落")
        assertTrue(plain[0] is Markdown.Block.Table)
        assertEquals("普通段落", (plain[1] as Markdown.Block.Paragraph).text)
    }

    @Test
    fun shortRowsPaddedAndExtraCellsTruncated() {
        val raw = "| a | b |\n| --- | --- |\n| 1 |\n| 1 | 2 | 3 |"
        val table = tables(raw).single()
        assertEquals(listOf("1", ""), table.rows[0])
        assertEquals(listOf("1", "2"), table.rows[1])
    }

    @Test
    fun listItemsWithPipesAreNotTables() {
        val blocks = Markdown.parseMarkdown("- a | b\n- --- | ---")
        assertEquals(2, blocks.filterIsInstance<Markdown.Block.ListItem>().size)
        assertTrue(blocks.none { it is Markdown.Block.Table })
    }

    @Test
    fun streamingPrefixesAreSafe() {
        // 只有表头（分隔行未到）：仍是段落，不误判
        assertTrue(tables("| a | b |").isEmpty())
        // 表头 + 分隔行：合法空表
        val empty = tables("| a | b |\n| --- | --- |").single()
        assertEquals(listOf("a", "b"), empty.header)
        assertTrue(empty.rows.isEmpty())
        // 数据行逐行到达：不崩溃，行数递增
        assertEquals(
            1,
            tables("| a | b |\n| --- | --- |\n| 1 | 2 |").single().rows.size,
        )
    }

    @Test
    fun columnWeights_proportionalAndClamped() {
        val weights = Markdown.tableColumnWeights(
            header = listOf("位置", "处理"),
            rows = listOf(listOf("ReturnReportStartupCatchUp.kt", "删除组件")),
        )
        // 长路径列取上限（20），短列取下限（6），不会把短列压成竖排单字
        assertEquals(listOf(20f, 6f), weights)
    }

    @Test
    fun columnWeights_equalForEqualColumns() {
        val weights = Markdown.tableColumnWeights(
            header = listOf("功能", "结果"),
            rows = listOf(listOf("收藏", "成功"), listOf("关注", "失败")),
        )
        assertEquals(weights[0], weights[1])
    }

    @Test
    fun columnWeights_insideClampRange() {
        val weights = Markdown.tableColumnWeights(
            header = listOf("a", "b", "c"),
            rows = listOf(
                listOf("1", "amazonData/src/main/kotlin/com/wc/amazon/task/ReturnReportStartupCatchUp.kt", "x"),
            ),
        )
        assertEquals(3, weights.size)
        weights.forEach { assertTrue("weight=$it", it >= 6f && it <= 20f) }
        assertTrue(weights[1] > weights[0])
    }

    @Test
    fun tableWithImage_cellKeepsAltAndImageExtracted() {
        val chunks = MarkdownImage.split("| a | b |\n| --- | --- |\n| ![预览](img.png) | y |")
        val table = Markdown.parseMarkdown(chunks[0].markdown)
            .filterIsInstance<Markdown.Block.Table>()
            .single()
        assertEquals("预览", table.rows[0][0])
        assertEquals(1, chunks[0].images.size)
        assertFalse(chunks[0].markdown.contains("!["))
    }

    @Test
    fun relativePathLinkInCell_rendersLabelOnly() {
        val table = tables("| 文件 | 说明 |\n| --- | --- |\n| [A.kt](amazonData/src/A.kt) | x |").single()
        val runs = Markdown.inlineRuns(table.rows[0][0])
        assertEquals("A.kt", runs.joinToString("") { it.text })
        assertTrue(runs.none { it.link != null })
    }
}
