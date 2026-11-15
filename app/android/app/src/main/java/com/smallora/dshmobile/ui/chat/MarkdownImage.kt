package com.smallora.dshmobile.ui.chat

/**
 * Markdown 图片语法（`![alt](src)`）的解析与剥离（纯 Kotlin，可 JVM 单测）。
 *
 * 规则：
 * - 按「段落 chunk」分组：空行分段、fenced code block（``` / ~~~）内整体不解析；
 * - 表格行（≥2 个 `|`）内的图片同样解析：从正文移除图片语法，单元格位置保留 alt 文本（空 alt → `[图片]`），
 *   图片渲染在该 chunk（整张表）之后；
 * - 行内 code（`...`）内的 `![...]` 不解析；转义 `\![...]`（前面有反斜杠）不解析；
 * - src 支持尖括号包裹带空格路径：`![a](<path with space>)`，渲染前去掉尖括号；
 * - 图片语法从正文中移除，原始 Markdown 交给 [com.smallora.dshmobile.ui.md.Markdown] 渲染，
 *   图片由调用方渲染在所属 chunk（段落）之后。
 */
object MarkdownImage {

    data class Image(val alt: String, val src: String, val raw: String)

    data class Chunk(val markdown: String, val images: List<Image>)

    fun split(raw: String): List<Chunk> {
        if (raw.isEmpty()) return emptyList()
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chunks = ArrayList<Chunk>()
        val markdown = StringBuilder()
        val images = ArrayList<Image>()
        var inFence = false
        var fenceMarker: String? = null

        fun flush() {
            if (markdown.isEmpty() && images.isEmpty()) return
            chunks.add(Chunk(markdown.toString().trimEnd('\n'), images.toList()))
            markdown.setLength(0)
            images.clear()
        }

        for (line in lines) {
            val trimmed = line.trimStart()
            val fence = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (fence != null) {
                if (!inFence) {
                    inFence = true
                    fenceMarker = fence
                } else if (fence == fenceMarker) {
                    inFence = false
                    fenceMarker = null
                }
                if (markdown.isNotEmpty()) markdown.append('\n')
                markdown.append(line)
                continue
            }
            if (inFence) {
                if (markdown.isNotEmpty()) markdown.append('\n')
                markdown.append(line)
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }
            val (cleanLine, lineImages) = extractLine(line)
            if (lineImages.isNotEmpty()) images.addAll(lineImages)
            if (markdown.isNotEmpty()) markdown.append('\n')
            markdown.append(cleanLine)
        }
        flush()
        return chunks
    }

    /** 从单行中抽取图片语法并返回剥离后的文本（跳过行内 code；表格行保留 alt 文本以免空单元格）。 */
    private fun extractLine(line: String): Pair<String, List<Image>> {
        val isTableRow = line.count { it == '|' } >= 2
        val out = StringBuilder(line.length)
        val found = ArrayList<Image>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            // 行内 code：原样跳过
            if (c == '`') {
                out.append(c)
                i++
                while (i < line.length && line[i] != '`') {
                    out.append(line[i])
                    i++
                }
                if (i < line.length) {
                    out.append('`')
                    i++
                }
                continue
            }
            // 转义 \! 不处理
            if (c == '!' && i + 1 < line.length && line[i + 1] == '[' && (i == 0 || line[i - 1] != '\\')) {
                val parsed = parseImage(line, i)
                if (parsed != null) {
                    val (image, end) = parsed
                    found.add(image)
                    // 表格单元格里的图片：保留 alt 占位，避免表格出现空单元格
                    if (isTableRow) out.append(image.alt.ifBlank { "[图片]" })
                    i = end
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString().trimEnd() to found
    }

    /** 解析从 [start] 开始的 `![alt](src)`；返回 image 与结束下标（不含）。 */
    private fun parseImage(line: String, start: Int): Pair<Image, Int>? {
        val closeBracket = line.indexOf(']', start + 2)
        if (closeBracket < 0) return null
        if (closeBracket + 1 >= line.length || line[closeBracket + 1] != '(') return null
        val closeParen = line.indexOf(')', closeBracket + 2)
        if (closeParen < 0) return null
        val alt = line.substring(start + 2, closeBracket)
        var src = line.substring(closeBracket + 2, closeParen).trim()
        if (src.length >= 2 && src.startsWith('<') && src.endsWith('>')) {
            src = src.substring(1, src.length - 1).trim()
        }
        if (src.isEmpty()) return null
        val raw = line.substring(start, closeParen + 1)
        return Image(alt = alt, src = src, raw = raw) to (closeParen + 1)
    }
}
