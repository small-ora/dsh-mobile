package com.smallora.dshmobile.ui.chat

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smallora.dshmobile.core.ChatItem
import com.smallora.dshmobile.core.Goal
import com.smallora.dshmobile.core.ModelOption
import com.smallora.dshmobile.core.ModelSelection
import com.smallora.dshmobile.core.PendingRequest
import com.smallora.dshmobile.core.Question
import com.smallora.dshmobile.core.Todo
import com.smallora.dshmobile.ui.md.Markdown
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ 聊天条目

@Composable
internal fun ChatItemRow(
    item: ChatItem,
    thinkSeconds: Int?,
) {
    when (item) {
        is ChatItem.User -> UserBubble(item)
        is ChatItem.Assistant -> AssistantMessage(item, thinkSeconds)
        is ChatItem.Tool -> ToolRow(item)
        is ChatItem.Notice -> NoticeLine(item)
    }
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.images.take(3).forEach { image ->
                        DataUrlImage(
                            dataUrl = image,
                            modifier = Modifier.size(120.dp),
                            corner = 12.dp,
                        )
                    }
                }
            }
            if (item.text.isNotBlank()) {
                Text(
                    text = item.text,
                    style = typography.bodyLarge,
                    color = colors.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(item: ChatItem.Assistant, thinkSeconds: Int?) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!item.reasoning.isNullOrBlank()) {
            ReasoningRow(reasoning = item.reasoning, seconds = thinkSeconds, streaming = item.streaming)
        }
        if (item.text.isNotEmpty()) {
            MarkdownBody(text = item.text, streaming = item.streaming)
        } else if (item.streaming) {
            Text("▌", style = typography.bodyLarge, color = colors.primary)
        }
        if (item.interrupted) {
            Text("已停止", style = typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReasoningRow(reasoning: String, seconds: Int?, streaming: Boolean) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var expanded by remember(reasoning.hashCode()) { mutableStateOf(false) }
    val label = when {
        streaming -> "思考中…"
        seconds != null -> "思考 · $seconds 秒"
        else -> "思考"
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = typography.labelMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "▾" else "▸", style = typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (expanded) {
            Text(
                text = reasoning,
                style = typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ToolRow(
    item: ChatItem.Tool,
    horizontalPadding: Dp = 16.dp,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var expanded by remember(item.key) { mutableStateOf(false) }
    val dotColor = when {
        item.running -> colors.primary
        item.isError -> colors.error
        else -> colors.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", style = typography.labelSmall, color = dotColor)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (item.running) "工具 · ${item.name} · 运行中" else "工具 · ${item.name}",
                style = if (compact) typography.bodySmall else typography.bodyMedium,
                color = when {
                    item.isError -> colors.error
                    compact -> colors.onSurfaceVariant
                    else -> colors.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▾" else "▸", style = typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp)) {
                if (item.args.isNotBlank()) {
                    Text("参数", style = typography.labelSmall, color = colors.onSurfaceVariant)
                    CodeSurface(item.args, isError = false)
                }
                val result = item.result
                if (!result.isNullOrBlank()) {
                    Text("结果", style = typography.labelSmall, color = colors.onSurfaceVariant)
                    CodeSurface(result, isError = item.isError)
                } else if (item.running) {
                    Text("运行中…", style = typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(item: ChatItem.Notice, horizontalPadding: Dp = 16.dp) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val isError = item.kind.contains("error", ignoreCase = true) || item.kind.contains("fail", ignoreCase = true)
    Text(
        text = item.text,
        style = typography.labelMedium,
        color = if (isError) colors.error else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 3.dp),
    )
}

// ------------------------------------------------------------------ 中间过程分组

/**
 * 一个回合的中间过程合并行：默认收起为「▸ 过程 · N 项」，运行中显示「（运行中…）」；
 * 点击展开后按原顺序显示工具调用（可再展开 args/result）、思考、中间助手文本、Notice。
 */
@Composable
internal fun ProcessGroupRow(
    group: DisplayItem.Group,
    thinkSeconds: Map<String, Int>,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var expanded by remember(group.key) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "过程 · ${group.items.size} 项",
                style = typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            if (group.running) {
                Text(
                    text = "（运行中…）",
                    style = typography.labelMedium,
                    color = colors.primary,
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.items.forEach { item ->
                    when (item) {
                        is ChatItem.Tool -> ToolRow(item, horizontalPadding = 0.dp, compact = true)
                        is ChatItem.Notice -> NoticeLine(item, horizontalPadding = 0.dp)
                        is ChatItem.Assistant -> GroupAssistant(item, thinkSeconds[item.key])
                        is ChatItem.User -> Unit // 分段后不会出现
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupAssistant(item: ChatItem.Assistant, thinkSeconds: Int?) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!item.reasoning.isNullOrBlank()) {
            ReasoningRow(reasoning = item.reasoning, seconds = thinkSeconds, streaming = item.streaming)
        }
        if (item.text.isNotBlank()) {
            MarkdownBody(text = item.text, streaming = item.streaming, compact = true)
        } else if (item.streaming) {
            Text("▌", style = typography.bodySmall, color = colors.primary)
        }
        if (item.interrupted) {
            Text("已停止", style = typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Markdown

@Composable
internal fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    // 过程分组里用 13sp 次要色，正文仍是 15sp 主色
    val bodyStyle = if (compact) typography.bodySmall else typography.bodyLarge
    val bodyColor = if (compact) colors.onSurfaceVariant else colors.onSurface
    val blocks = remember(text) { Markdown.parseMarkdown(text) }
    val linkColor = colors.primary
    val codeBackground = colors.surfaceVariant
    val inlines = remember(text, streaming, linkColor, codeBackground) {
        blocks.mapIndexed { index, block ->
            val raw = when (block) {
                is Markdown.Block.Paragraph -> block.text
                is Markdown.Block.Heading -> block.text
                is Markdown.Block.ListItem -> block.text
                is Markdown.Block.Quote -> block.text
                else -> null
            } ?: return@mapIndexed null
            val base = Markdown.inlineAnnotated(raw, linkColor = linkColor, codeBackground = codeBackground)
            if (streaming && index == blocks.lastIndex && block is Markdown.Block.Paragraph) {
                buildAnnotatedString {
                    append(base)
                    withStyle(SpanStyle(color = colors.primary)) { append("▌") }
                }
            } else {
                base
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val inline = inlines.getOrNull(index)
            when (block) {
                is Markdown.Block.Paragraph -> Text(
                    text = inline ?: AnnotatedString(block.text),
                    style = bodyStyle,
                    color = bodyColor,
                )

                is Markdown.Block.Heading -> Text(
                    text = inline ?: AnnotatedString(block.text),
                    style = if (compact) {
                        typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        (if (block.level <= 2) typography.titleMedium else typography.titleSmall)
                            .copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = bodyColor,
                    modifier = Modifier.padding(top = 2.dp),
                )

                is Markdown.Block.CodeBlock -> CodeBlockView(block.language, block.code)

                is Markdown.Block.ListItem -> Row(
                    modifier = Modifier.padding(start = (block.indent * 16).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = if (block.ordered) "${block.number}." else "•",
                        style = bodyStyle,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = inline ?: AnnotatedString(block.text),
                        style = bodyStyle,
                        color = bodyColor,
                        modifier = Modifier.weight(1f),
                    )
                }

                is Markdown.Block.Quote -> Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(colors.outlineVariant),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = inline ?: AnnotatedString(block.text),
                        style = if (compact) typography.bodySmall else typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }

                Markdown.Block.Divider -> HorizontalDivider(
                    thickness = 1.dp,
                    color = colors.outlineVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun CodeBlockView(language: String?, code: String) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.ifBlank { null } ?: "代码",
                style = typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (copied) "已复制" else "复制",
                style = typography.labelSmall,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("markdown", code))
                        }
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = code,
                    style = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CodeSurface(text: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (isError) colors.error else colors.onSurface,
            )
        }
    }
}

// ------------------------------------------------------------------ 图片

@Composable
internal fun DataUrlImage(dataUrl: String, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val bitmap = remember(dataUrl) { decodeDataUrl(dataUrl) }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(corner)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(corner))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("图片", style = typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun decodeDataUrl(dataUrl: String): ImageBitmap? {
    val marker = "base64,"
    val index = dataUrl.indexOf(marker)
    if (index < 0) return null
    return runCatching {
        val bytes = Base64.decode(dataUrl.substring(index + marker.length), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

// ------------------------------------------------------------------ 待处理：审批 / 提问

@Composable
internal fun ApprovalCard(
    request: PendingRequest,
    busy: Boolean,
    onDecision: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("需要批准", style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
                Spacer(Modifier.weight(1f))
                if (!request.toolName.isNullOrBlank()) {
                    Text(
                        text = request.toolName,
                        style = typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (!request.reason.isNullOrBlank()) {
                Text(
                    text = request.reason,
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onDecision(false) }, enabled = !busy) {
                    Text("拒绝", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onDecision(true) },
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Text("允许一次", style = typography.bodyMedium)
                }
            }
        }
    }
}

private data class AnswerDraft(val selected: Set<String>, val custom: String)

@Composable
internal fun QuestionCard(
    request: PendingRequest,
    busy: Boolean,
    onSubmit: (List<ChatViewModel.Answer>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val drafts = remember(request.eventId) { mutableStateMapOf<String, AnswerDraft>() }

    fun draftOf(id: String): AnswerDraft = drafts[id] ?: AnswerDraft(emptySet(), "")

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要回答", style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
            request.questions.forEach { question ->
                QuestionBlock(
                    question = question,
                    draft = draftOf(question.id),
                    onDraftChange = { drafts[question.id] = it },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        val answers = request.questions.map { question ->
                            val draft = draftOf(question.id)
                            ChatViewModel.Answer(
                                id = question.id,
                                selected = draft.selected.toList(),
                                custom = draft.custom.takeIf { it.isNotBlank() },
                            )
                        }
                        onSubmit(answers)
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Text("提交", style = typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun QuestionBlock(question: Question, draft: AnswerDraft, onDraftChange: (AnswerDraft) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!question.header.isNullOrBlank()) {
            Text(question.header, style = typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Text(question.question, style = typography.bodyLarge, color = colors.onSurface)
        if (!question.detail.isNullOrBlank()) {
            Text(question.detail, style = typography.labelMedium, color = colors.onSurfaceVariant)
        }
        question.options.forEach { option ->
            val checked = option.label in draft.selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val next = when {
                            !question.multiSelect -> setOf(option.label)
                            checked -> draft.selected - option.label
                            else -> draft.selected + option.label
                        }
                        onDraftChange(draft.copy(selected = next))
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question.multiSelect) {
                    Checkbox(checked = checked, onCheckedChange = {
                        val next = if (checked) draft.selected - option.label else draft.selected + option.label
                        onDraftChange(draft.copy(selected = next))
                    })
                } else {
                    RadioButton(selected = checked, onClick = { onDraftChange(draft.copy(selected = setOf(option.label))) })
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(option.label, style = typography.bodyMedium, color = colors.onSurface)
                    if (!option.description.isNullOrBlank()) {
                        Text(option.description, style = typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        // 任何题型都允许「其他/填空」补充（上游 answer 支持 selected + custom 并存）
        run {
            BasicTextField(
                value = draft.custom,
                onValueChange = { onDraftChange(draft.copy(custom = it)) },
                singleLine = true,
                textStyle = typography.bodyMedium.copy(color = colors.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (draft.custom.isEmpty()) {
                        Text("填写答案（可选）", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    inner()
                },
            )
        }
    }
}

// ------------------------------------------------------------------ 目标 / 待办

private fun goalPhaseLabel(phase: String): String = when (phase) {
    "active" -> "进行中"
    "paused" -> "已暂停"
    "blocked" -> "受阻"
    "complete" -> "已完成"
    else -> phase
}

@Composable
internal fun GoalBar(
    goal: Goal,
    todos: List<Todo>,
    busy: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var showTodos by remember(goal.id) { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("目标", style = typography.labelMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    text = goalPhaseLabel(goal.phase),
                    style = typography.labelSmall,
                    color = if (goal.phase == "active") colors.primary else colors.onSurfaceVariant,
                )
            }
            Text(
                text = goal.objective,
                style = typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (goal.phase == "blocked" && !goal.blockedReason.isNullOrBlank()) {
                Text(goal.blockedReason, style = typography.labelMedium, color = colors.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (goal.phase == "active") {
                    TextButton(onClick = onPause, enabled = !busy) {
                        Text("暂停", style = typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                if (goal.phase == "paused" || goal.phase == "blocked") {
                    TextButton(onClick = onResume, enabled = !busy) {
                        Text("恢复", style = typography.labelMedium, color = colors.primary)
                    }
                }
                if (goal.phase != "complete") {
                    TextButton(onClick = onComplete, enabled = !busy) {
                        Text("完成", style = typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (todos.isNotEmpty()) {
                    Text(
                        text = "待办 · $done/${todos.size}",
                        style = typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showTodos = !showTodos }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (showTodos && todos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    todos.forEach { todo ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = when (todo.status) {
                                    "completed" -> "✓"
                                    "in_progress" -> "●"
                                    else -> "○"
                                },
                                style = typography.labelMedium,
                                color = when (todo.status) {
                                    "completed" -> colors.onSurfaceVariant
                                    "in_progress" -> colors.primary
                                    else -> colors.onSurfaceVariant
                                },
                                modifier = Modifier.width(18.dp),
                            )
                            Text(
                                text = todo.content,
                                style = typography.labelMedium,
                                color = if (todo.status == "completed") colors.onSurfaceVariant else colors.onSurface,
                                textDecoration = if (todo.status == "completed") TextDecoration.LineThrough else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 输入区

@Composable
internal fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<String>,
    onRemoveAttachment: (Int) -> Unit,
    onPickImages: () -> Unit,
    running: Boolean,
    steer: Boolean,
    onSteerChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    // 生成中也可发送（排队 / 插队），由 steer 决定模式
    val canSend = draft.isNotBlank() || attachments.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 44.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEachIndexed { index, dataUrl ->
                    Box {
                        DataUrlImage(dataUrl = dataUrl, modifier = Modifier.size(56.dp), corner = 10.dp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(colors.surface)
                                .border(1.dp, colors.outline, CircleShape)
                                .clickable { onRemoveAttachment(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("×", style = typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (running) {
            Row(
                modifier = Modifier.padding(start = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("生成中", style = typography.labelSmall, color = colors.onSurfaceVariant)
                Text(
                    text = "排队",
                    style = typography.labelSmall,
                    color = if (!steer) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSteerChange(false) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Text(
                    text = "插队",
                    style = typography.labelSmall,
                    color = if (steer) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSteerChange(true) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, colors.outlineVariant, CircleShape)
                    .clickable(onClick = onPickImages),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = typography.titleMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                textStyle = typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text("给 DSH 发消息…", style = typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(1.dp, colors.outlineVariant, CircleShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("■", style = typography.labelMedium, color = colors.primary)
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.primary else colors.surfaceVariant)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↑",
                    style = typography.titleMedium,
                    color = if (canSend) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 模型选择

private fun reasoningLabel(effort: String): String = when (effort.lowercase()) {
    "low", "minimal" -> "低"
    "medium", "normal" -> "中"
    "high" -> "高"
    "xhigh", "max" -> "最高"
    else -> effort
}

@Composable
internal fun ModelPickerDialog(
    models: List<ModelOption>,
    current: ModelSelection?,
    onPick: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", style = typography.bodyMedium, color = colors.primary)
            }
        },
        title = {
            Text(
                "模型与思考档位",
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (models.isEmpty()) {
                    Text("暂无可用模型", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                models.forEach { option ->
                    val selected = current != null && current.provider == option.provider && current.model == option.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onPick(
                                    ModelSelection(
                                        provider = option.provider,
                                        model = option.model,
                                        reasoningEffort = if (selected) current?.reasoningEffort else null,
                                    ),
                                )
                            }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.name,
                                style = typography.bodyLarge,
                                color = if (selected) colors.primary else colors.onSurface,
                            )
                            Text(
                                text = option.provider,
                                style = typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Text("✓", style = typography.labelMedium, color = colors.primary)
                        }
                    }
                    if (selected && option.reasoning.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val effort = current?.reasoningEffort
                            Text(
                                text = "自动",
                                style = typography.labelSmall,
                                color = if (effort == null) colors.primary else colors.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (effort == null) colors.primaryContainer else Color.Transparent)
                                    .clickable {
                                        onPick(ModelSelection(option.provider, option.model, null))
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            option.reasoning.forEach { value ->
                                val active = effort == value
                                Text(
                                    text = reasoningLabel(value),
                                    style = typography.labelSmall,
                                    color = if (active) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) colors.primaryContainer else Color.Transparent)
                                        .clickable {
                                            onPick(ModelSelection(option.provider, option.model, value))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = colors.surface,
    )
}
