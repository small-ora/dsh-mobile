package com.smallora.dshmobile.ui.chat

import com.smallora.dshmobile.core.ChatItem

/**
 * 聊天页展示分组（纯 Kotlin，可 JVM 单测）：
 * 一个回合内的中间过程（工具调用 / 思考 / 中间步骤助手文本 / Notice 等）合并成一行可展开的
 * 「过程 · N 项」，避免几十步后聊天页过长；每回合的最终回答单独保留。
 *
 * 规则（以 [ChatItem.User] 分段，不跨用户合并）：
 *  - 段内最后一条「文本非空且 streaming=false」的 Assistant 视为最终回答，保留单条并放在段尾；
 *  - 若段内没有最终回答（典型：正在流式输出），保留 streaming 的 Assistant 单条实时显示；
 *  - 段内其余项合并为一个 Group（key 取首项 key）；running = 组内任一项 running 或该段正在流式输出；
 *  - 只有 User 的空段不产生 Group。
 */
sealed interface DisplayItem {
    data class Single(val item: ChatItem) : DisplayItem

    data class Group(
        val key: String,
        val items: List<ChatItem>,
        val running: Boolean,
    ) : DisplayItem
}

fun buildDisplayItems(items: List<ChatItem>): List<DisplayItem> {
    val result = ArrayList<DisplayItem>(items.size)
    var segment = ArrayList<ChatItem>()

    fun flushSegment() {
        if (segment.isEmpty()) return
        appendSegment(result, segment)
        segment = ArrayList()
    }

    for (item in items) {
        if (item is ChatItem.User) {
            flushSegment()
            result.add(DisplayItem.Single(item))
        } else {
            segment.add(item)
        }
    }
    flushSegment()
    return result
}

private fun appendSegment(out: MutableList<DisplayItem>, segment: List<ChatItem>) {
    val streaming = segment.any { it is ChatItem.Assistant && it.streaming }

    // 最终回答：最后一条「有文本且已结束」的 Assistant
    val finalIndex = segment.indexOfLast { item ->
        item is ChatItem.Assistant && item.text.isNotBlank() && !item.streaming
    }
    // 没有最终回答时，保留正在流式输出的那条 Assistant 实时显示
    val streamingIndex = if (finalIndex < 0) {
        segment.indexOfLast { item -> item is ChatItem.Assistant && item.streaming }
    } else {
        -1
    }
    val keepIndex = if (finalIndex >= 0) finalIndex else streamingIndex

    val groupItems = if (keepIndex >= 0) {
        segment.filterIndexed { index, _ -> index != keepIndex }
    } else {
        segment
    }

    if (groupItems.isNotEmpty()) {
        val running = streaming || groupItems.any { it is ChatItem.Tool && it.running }
        out.add(
            DisplayItem.Group(
                key = groupItems.first().key,
                items = groupItems,
                running = running,
            ),
        )
    }
    if (keepIndex >= 0) {
        out.add(DisplayItem.Single(segment[keepIndex]))
    }
}
