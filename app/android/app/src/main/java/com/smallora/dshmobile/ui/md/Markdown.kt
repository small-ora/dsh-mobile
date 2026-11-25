package com.smallora.dshmobile.ui.md

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * 安全 Markdown → AnnotatedString（DeepSeek 简洁风，只做纯排版，不执行任何 HTML/脚本）。
 *
 * 解析分两层：
 *  1. [parseMarkdown]：块级结构（标题 / 段落 / 代码块 / 列表 / 引用 / 分隔线），纯 Kotlin 数据，可 JVM 单测。
 *  2. [inlineRuns] / [inlineAnnotated]：行内格式（**粗体**、*斜体*、`代码`、[链接](url)）。
 *
 * 安全约定：
 *  - 行内文本进入解析前先用 [escapeHtml] 转义 `& < > " '`，因此文本里的 HTML 标签永远只以字面量出现，
 *    不会被当成标记解析（Compose 端不使用 AnnotatedString.fromHtml，没有任何 HTML 执行路径）。
 *  - 链接只允许 `http://` / `https://` / `mailto:`；`javascript:`、`data:`、`file:` 等一律按普通文本渲染。
 *  - 代码块内容原样保留（不做 Markdown 解析），仅用于等宽展示与复制。
 */
object Markdown {

    /** 块级节点。`Paragraph` / `Heading` / `ListItem` / `Quote` / `Table` 单元格的文本是未转义的原始内联 Markdown。 */
    sealed interface Block {
        data class Paragraph(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        data class CodeBlock(val language: String?, val code: String) : Block
        data class ListItem(val text: String, val ordered: Boolean, val number: Int, val indent: Int) : Block
        data class Quote(val text: String) : Block
        data object Divider : Block

        /**
         * GFM 管道表格：`header` 为表头单元格，`rows` 为数据行（每行长度已归一化为 `header.size`），
         * `align` 与 `header` 等长（来自分隔行的 `:---` / `---:` / `:---:`）。
         */
        data class Table(
            val align: List<Align>,
            val header: List<String>,
            val rows: List<List<String>>,
        ) : Block
    }

    /** 表格列对齐。 */
    enum class Align { NONE, LEFT, CENTER, RIGHT }

    /** 行内样式。 */
    enum class Style { NORMAL, BOLD, ITALIC, BOLD_ITALIC, CODE }

    /** 行内片段：text 已是可直接展示的字面量（HTML 实体已解码），link 非空表示可点击链接。 */
    data class Run(val text: String, val style: Style, val link: String?)

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UNORDERED = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val THEMATIC_BREAK = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")

    /** 表格分隔行单元格：`---` / `:---` / `---:` / `:---:`。 */
    private val TABLE_DELIMITER_CELL = Regex("^:?-{1,}:?$")

    // ---------------------------------------------------------------- 块级

    fun parseMarkdown(raw: String): List<Block> {
        if (raw.isEmpty()) return emptyList()
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(Block.Paragraph(paragraph.toString().trimEnd()))
                paragraph.setLength(0)
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            val fence = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (fence != null) {
                flushParagraph()
                val language = trimmed.removePrefix(fence).trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // 跳过结束围栏
                blocks.add(Block.CodeBlock(language, code.toString().trimEnd('\n')))
                continue
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                flushParagraph()
                blocks.add(Block.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
                i++
                continue
            }

            if (THEMATIC_BREAK.matches(trimmed)) {
                flushParagraph()
                blocks.add(Block.Divider)
                i++
                continue
            }

            if (QUOTE.matches(trimmed)) {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size) {
                    val q = QUOTE.matchEntire(lines[i].trimStart()) ?: break
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(q.groupValues[1])
                    i++
                }
                blocks.add(Block.Quote(quote.toString()))
                continue
            }

            val unordered = UNORDERED.matchEntire(line)
            val ordered = if (unordered == null) ORDERED.matchEntire(line) else null
            if (unordered != null || ordered != null) {
                flushParagraph()
                if (unordered != null) {
                    val indent = unordered.groupValues[1].length / 2
                    blocks.add(Block.ListItem(unordered.groupValues[2].trim(), ordered = false, number = 0, indent = indent))
                } else if (ordered != null) {
                    val indent = ordered.groupValues[1].length / 2
                    val number = ordered.groupValues[2].toIntOrNull() ?: 1
                    blocks.add(Block.ListItem(ordered.groupValues[3].trim(), ordered = true, number = number, indent = indent))
                }
                i++
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            // GFM 管道表格：表头行 + 分隔行（列数一致）+ 连续数据行
            val table = matchTable(lines, i)
            if (table != null) {
                flushParagraph()
                blocks.add(table.block)
                i = table.nextIndex
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trimEnd())
            i++
        }
        flushParagraph()
        return blocks
    }

    // ---------------------------------------------------------------- 表格

    private data class TableMatch(val block: Block.Table, val nextIndex: Int)

    /**
     * 从第 [start] 行尝试识别一张 GFM 管道表格：
     *  - `start` 行是表头（必须含未转义 `|`），`start+1` 行是分隔行（每个单元格匹配 `:?-+:?`，列数一致）；
     *  - 数据行持续到空行、围栏/标题/引用/列表等块起始、或不含竖线的行为止（保守，避免吞掉后续内容）。
     */
    private fun matchTable(lines: List<String>, start: Int): TableMatch? {
        val headerCells = splitTableRow(lines[start]) ?: return null
        if (headerCells.isEmpty()) return null
        val delimiterCells = splitTableRow(lines.getOrNull(start + 1) ?: return null) ?: return null
        if (delimiterCells.size != headerCells.size) return null
        if (delimiterCells.any { !TABLE_DELIMITER_CELL.matches(it) }) return null

        val align = delimiterCells.map { parseAlign(it) }
        val header = headerCells.map { it.trim() }

        val rows = ArrayList<List<String>>()
        var j = start + 2
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank()) break
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) break
            if (HEADING.matchEntire(trimmed) != null) break
            if (QUOTE.matches(trimmed)) break
            if (UNORDERED.matchEntire(line) != null || ORDERED.matchEntire(line) != null) break
            val cells = splitTableRow(line) ?: break
            rows.add(normalizeCells(cells, header.size))
            j++
        }
        return TableMatch(Block.Table(align = align, header = header, rows = rows), j)
    }

    private fun parseAlign(cell: String): Align {
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        return when {
            left && right -> Align.CENTER
            left -> Align.LEFT
            right -> Align.RIGHT
            else -> Align.NONE
        }
    }

    /** 按未转义、且不在行内 code 里的 `|` 切分单元格；前后竖线可省略；`\|` 还原为字面竖线。 */
    private fun splitTableRow(line: String): List<String>? {
        // 先去掉行首缩进/行尾空白，避免「前导空格 + 竖线」被误当成一个空单元格
        val source = line.trim()
        var hasPipe = false
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var first = true
        var i = 0

        fun endCell(dropIfEmptyLeading: Boolean) {
            val text = cell.toString().trim()
            if (dropIfEmptyLeading && text.isEmpty()) {
                // 前导竖线产生的空首格：丢弃
            } else {
                cells.add(text)
            }
            cell.setLength(0)
        }

        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && i + 1 < source.length && source[i + 1] == '|') {
                cell.append('|')
                i += 2
                first = false
                continue
            }
            if (c == '`') {
                cell.append(c)
                i++
                while (i < source.length && source[i] != '`') {
                    cell.append(source[i])
                    i++
                }
                if (i < source.length) {
                    cell.append('`')
                    i++
                }
                first = false
                continue
            }
            if (c == '|') {
                hasPipe = true
                endCell(dropIfEmptyLeading = first && cell.isEmpty())
                first = false
                i++
                continue
            }
            cell.append(c)
            first = false
            i++
        }
        if (!hasPipe) return null
        val tail = cell.toString().trim()
        // 尾随竖线产生的空尾格：丢弃；无尾随竖线时保留最后一段
        if (tail.isNotEmpty() || !source.endsWith("|")) cells.add(tail)
        return cells
    }

    private fun normalizeCells(cells: List<String>, size: Int): List<String> {
        if (cells.size == size) return cells
        val out = ArrayList<String>(size)
        for (index in 0 until size) out.add(cells.getOrElse(index) { "" })
        return out
    }

    /**
     * 列宽权重：按每列「可见文本长度」（剥离 Markdown 标记后的近似长度，含表头）分配，
     * 下限 [MIN_COLUMN_WEIGHT]、上限 [MAX_COLUMN_WEIGHT]，避免超长路径把短列挤成竖排单字。
     */
    fun tableColumnWeights(header: List<String>, rows: List<List<String>>): List<Float> {
        if (header.isEmpty()) return emptyList()
        val maxLength = IntArray(header.size)
        for (column in header.indices) {
            maxLength[column] = plainTextLength(header[column])
        }
        for (row in rows) {
            for (column in header.indices) {
                val length = plainTextLength(row.getOrElse(column) { "" })
                if (length > maxLength[column]) maxLength[column] = length
            }
        }
        return header.indices.map {
            maxLength[it].toFloat().coerceIn(MIN_COLUMN_WEIGHT, MAX_COLUMN_WEIGHT)
        }
    }

    private fun plainTextLength(cell: String): Int = inlineRuns(cell).sumOf { it.text.length }

    private const val MIN_COLUMN_WEIGHT = 6f
    private const val MAX_COLUMN_WEIGHT = 20f

    // ---------------------------------------------------------------- 行内

    fun inlineRuns(raw: String): List<Run> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Run>()
        parseInlineInto(escapeHtml(raw), bold = false, italic = false, link = null, out = out)
        return mergeRuns(out)
    }

    /** 行内 Markdown → AnnotatedString；[linkColor] / [codeBackground] 由调用方从 MaterialTheme 传入。 */
    fun inlineAnnotated(
        raw: String,
        linkColor: Color = Color.Unspecified,
        codeBackground: Color = Color.Unspecified,
    ): AnnotatedString = buildAnnotatedString {
        for (run in inlineRuns(raw)) {
            val span = when (run.style) {
                Style.NORMAL -> null
                Style.BOLD, Style.BOLD_ITALIC -> SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (run.style == Style.BOLD_ITALIC) FontStyle.Italic else null,
                )
                Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            }
            if (run.link != null) {
                withLink(
                    LinkAnnotation.Url(
                        run.link,
                        TextLinkStyles(
                            SpanStyle(
                                color = if (linkColor == Color.Unspecified) Color.Unspecified else linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) { append(run.text) }
            } else if (span != null) {
                withStyle(span) { append(run.text) }
            } else {
                append(run.text)
            }
        }
    }

    private fun parseInlineInto(
        source: String,
        bold: Boolean,
        italic: Boolean,
        link: String?,
        out: MutableList<Run>,
    ) {
        var i = 0
        val buffer = StringBuilder()

        fun style(): Style = when {
            bold && italic -> Style.BOLD_ITALIC
            bold -> Style.BOLD
            italic -> Style.ITALIC
            else -> Style.NORMAL
        }

        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(Run(unescapeHtml(buffer.toString()), style(), link))
                buffer.setLength(0)
            }
        }

        while (i < source.length) {
            val c = source[i]

            // **粗体**
            if (!bold && source.startsWith("**", i)) {
                val end = source.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    parseInlineInto(source.substring(i + 2, end), true, italic, link, out)
                    i = end + 2
                    continue
                }
            }

            // *斜体*
            if (!italic && c == '*') {
                val end = findClosing(source, i + 1, '*')
                if (end > i + 1) {
                    flush()
                    parseInlineInto(source.substring(i + 1, end), bold, true, link, out)
                    i = end + 1
                    continue
                }
            }

            // _斜体_（要求两侧有边界，避免误伤 snake_case）
            if (!italic && c == '_' && i + 1 < source.length && !source[i + 1].isWhitespace()) {
                val end = findClosing(source, i + 1, '_')
                if (end > i + 1 && source[end - 1].isLetterOrDigit()) {
                    flush()
                    parseInlineInto(source.substring(i + 1, end), bold, true, link, out)
                    i = end + 1
                    continue
                }
            }

            // `行内代码`：不再解析内部格式
            if (c == '`') {
                val end = source.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out.add(Run(unescapeHtml(source.substring(i + 1, end)), Style.CODE, link))
                    i = end + 1
                    continue
                }
            }

            // [文本](链接)：http(s)/mailto 渲染为可点击链接；工作区相对路径只显示标签；危险 scheme 保持字面
            if (link == null && c == '[') {
                val closeBracket = source.indexOf(']', i + 1)
                if (closeBracket > i && closeBracket + 1 < source.length && source[closeBracket + 1] == '(') {
                    val closeParen = source.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = source.substring(i + 1, closeBracket)
                        val url = normalizeLinkTarget(unescapeHtml(source.substring(closeBracket + 2, closeParen)))
                        if (isSafeLink(url)) {
                            flush()
                            parseInlineInto(label, bold, italic, url, out)
                            i = closeParen + 1
                            continue
                        }
                        if (isRelativePathUrl(url)) {
                            // 工作区相对路径（`[A.kt](amazonData/src/A.kt)`）：只显示可读标签，丢弃路径
                            flush()
                            parseInlineInto(label, bold, italic, null, out)
                            i = closeParen + 1
                            continue
                        }
                    }
                }
            }

            buffer.append(c)
            i++
        }
        flush()
    }

    private fun findClosing(source: String, from: Int, marker: Char): Int {
        var j = from
        while (j < source.length) {
            if (source[j] == marker) {
                // 跳过 `**` 中第二个 `*` 作为闭合的情况由调用方处理
                return j
            }
            j++
        }
        return -1
    }

    private fun mergeRuns(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>(runs.size)
        for (run in runs) {
            val last = out.lastOrNull()
            if (last != null && last.style == run.style && last.link == run.link) {
                out[out.size - 1] = last.copy(text = last.text + run.text)
            } else {
                out.add(run)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- HTML 转义 / 链接校验

    /** 先转义 HTML，保证任何标签都只会变成字面量文本。 */
    fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    fun unescapeHtml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    /** 只接受 http(s) / mailto，拒绝 javascript:/data:/file: 等可执行或本地路径 scheme。 */
    fun isSafeLink(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
    }

    /**
     * 无 scheme 的相对路径（工作区文件、`./x`、`#anchor`、`<path with space>` 去掉尖括号后）：
     * 这类链接在 App 里不可跳转，渲染时只显示标签文本，不显示也不能点击路径。
     * `//host` 协议相对 URL 与 `javascript:`/`data:`/`file:` 等带 scheme 的目标一律返回 false（保持既有字面行为）。
     */
    fun isRelativePathUrl(url: String): Boolean {
        val value = url.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("//")) return false
        return !SCHEME_PREFIX.containsMatchIn(value)
    }

    /** 去掉 `[label](<path with space>)` 的尖括号包裹。 */
    private fun normalizeLinkTarget(raw: String): String {
        val value = raw.trim()
        if (value.length >= 2 && value.startsWith('<') && value.endsWith('>')) {
            return value.substring(1, value.length - 1).trim()
        }
        return value
    }

    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}
