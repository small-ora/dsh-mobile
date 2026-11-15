package com.smallora.dshmobile.core

/**
 * 「当前主机未启用会话搜索」的识别与用户提示。
 *
 * 背景：`session/search` 依赖 DSH 的 `session-query` 组件；未挂载该组件的部署会返回
 * `gateway/internal: session search failed: SessionQueryError: session search is disabled…`。
 * App 不应把这段英文原文直接弹给用户，应给出可理解的中文提示，并避免反复请求。
 */
internal const val SEARCH_UNAVAILABLE_MESSAGE = "当前主机未启用会话搜索（需 DSH 挂载 session-query 组件）"

/** 识别「搜索未启用」类失败；命中返回友好提示，否则返回 null（由调用方按普通错误处理）。 */
internal fun searchUnavailableReason(error: Throwable): String? {
    val raw = (error as? ApiException)?.message ?: error.message ?: return null
    val lower = raw.lowercase()
    val unavailable = "search is disabled" in lower ||
        "session-query" in lower ||
        "session search is unavailable" in lower
    return if (unavailable) SEARCH_UNAVAILABLE_MESSAGE else null
}
