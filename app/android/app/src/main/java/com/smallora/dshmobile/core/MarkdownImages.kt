package com.smallora.dshmobile.core

/**
 * 解析 Markdown 图片 `![alt](src)` 的 src（纯函数，JVM 可测）。
 *
 * 规则：
 * - `http://` / `https://` → [MarkdownImageSource.Remote]（外链，UI 直接加载）；
 * - `data:`（含 data:image/…;base64）→ [MarkdownImageSource.DataUrl]；
 * - `file://` → 去 scheme 后按工作区路径处理（绝对或相对 cwd）；
 * - `/` 开头绝对路径 → [MarkdownImageSource.WorkspacePath]；
 * - 相对路径 → `cwd + "/" + src` 归一化（处理 `./`、`../`）；**cwd 为空返回 null**；
 * - 空白、未知 scheme（ftp://、mailto: 等）→ null。
 *
 * 路径中的百分号编码会被解码（`+` 保留为字面量，避免误变空格）。
 */
fun resolveMarkdownImageSrc(src: String?, cwd: String?): MarkdownImageSource? {
    val raw = src?.trim() ?: return null
    if (raw.isEmpty()) return null
    val lower = raw.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return MarkdownImageSource.Remote(raw)
    }
    if (lower.startsWith("data:")) return MarkdownImageSource.DataUrl(raw)
    if (lower.startsWith("file://")) {
        val filePath = decodeIfPercentEncoded(stripFileScheme(raw))
        if (filePath.isEmpty()) return null
        return if (filePath.startsWith("/")) {
            MarkdownImageSource.WorkspacePath(normalizePathComponents(filePath))
        } else {
            joinWithCwd(filePath, cwd)?.let { MarkdownImageSource.WorkspacePath(it) }
        }
    }
    if (raw.contains("://")) return null // 不支持的 scheme
    // mailto:、tel: 等无 "//" 的 scheme 也跳过（避免被当成相对路径拼 cwd）
    if (UNSUPPORTED_SCHEME.containsMatchIn(raw)) return null
    val decoded = decodeIfPercentEncoded(raw)
    return if (decoded.startsWith("/")) {
        MarkdownImageSource.WorkspacePath(normalizePathComponents(decoded))
    } else {
        joinWithCwd(decoded, cwd)?.let { MarkdownImageSource.WorkspacePath(it) }
    }
}

private fun stripFileScheme(raw: String): String {
    var value = raw.substring("file://".length)
    // file://localhost/root/a.png → /root/a.png
    if (value.startsWith("localhost/")) value = value.removePrefix("localhost")
    return value
}

private fun decodeIfPercentEncoded(value: String): String {
    if (!value.contains('%')) return value
    return try {
        // 保留 '+'：URLDecoder 默认会把 '+' 解码为空格，文件名里的 '+' 是合法字符
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        value
    }
}

/** 相对路径拼 cwd；cwd 为空返回 null。结果做 `.`/`..` 归一化。 */
internal fun joinWithCwd(relative: String, cwd: String?): String? {
    val base = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val combined = when {
        relative.startsWith("/") -> relative
        base.endsWith("/") -> base + relative
        else -> "$base/$relative"
    }
    return normalizePathComponents(combined)
}

/** 文本级归一化路径（不访问文件系统）：处理空段、`.`、`..`，保留绝对路径前导 `/`。 */
internal fun normalizePathComponents(path: String): String {
    val absolute = path.startsWith("/")
    val parts = ArrayList<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (parts.isNotEmpty() && parts.last() != "..") {
                    parts.removeAt(parts.lastIndex)
                } else if (!absolute) {
                    parts.add("..")
                }
            }

            else -> parts.add(segment)
        }
    }
    val joined = parts.joinToString("/")
    return if (absolute) "/$joined" else joined.ifEmpty { "." }
}

private val UNSUPPORTED_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
