package com.smallora.dshmobile.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.ChatItem
import com.smallora.dshmobile.core.ChatUiState
import com.smallora.dshmobile.ui.AppNavState
import com.smallora.dshmobile.ui.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 聊天页：DeepSeek 官方简洁风。
 * 顶栏 44dp（返回/标题/运行态/模型/更多），消息区行式列表，审批/提问/目标卡片固定在输入框上方，
 * 输入框圆角 22dp + 蓝色圆形发送键，运行中显示停止与排队/插队。
 */
@Composable
fun ChatScreen(sessionId: String, container: AppContainer, nav: AppNavState) {
    val vm: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = ChatViewModel.factory(container, sessionId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val steer by vm.steer.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val busyEvents by vm.busyEventIds.collectAsStateWithLifecycle()
    val modelCatalog by vm.models.collectAsStateWithLifecycle()
    val thinkSeconds by vm.thinkSeconds.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showModels by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var stickToBottom by remember { mutableStateOf(true) }

    BackHandler { nav.back() }

    // 进入即打开会话流；离开（返回/系统返回/切页）立刻释放，避免后台流泄漏
    LaunchedEffect(sessionId) { vm.open() }
    DisposableEffect(sessionId) {
        onDispose { vm.close() }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attachImages(context, uris)
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            vm.clearToast()
        }
    }

    // 用户上滑到顶部时自动加载更早历史（直接读 StateFlow.value，避免闭包捕获旧状态）
    val stateFlow = vm.state
    LaunchedEffect(listState, stateFlow) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }
            .distinctUntilChanged()
            .collect { (index, offset, scrolling) ->
                val current = stateFlow.value
                if (scrolling && index == 0 && offset <= 24 && current.hasMore && !current.loadingOlder) {
                    vm.loadOlder()
                }
            }
    }

    // 用户在底部时跟随新内容（不产生多余滚动动效）
    LaunchedEffect(listState) {
        snapshotFlowListBottom(listState)
            .collect { stickToBottom = it }
    }
    val streamingTick = (state.items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(state.items.size, streamingTick) {
        if (state.items.isNotEmpty() && stickToBottom) {
            val lastIndex = state.items.size - 1 + if (state.hasMore) 1 else 0
            listState.scrollToItem(lastIndex.coerceAtLeast(0))
        }
    }

    val pendingForSession = state.pending.filter { it.sessionId == sessionId }
    val currentModelName = modelCatalog.firstOrNull {
        state.selection != null && it.provider == state.selection?.provider && it.model == state.selection?.model
    }?.name ?: state.selection?.model ?: "模型"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        ChatTopBar(
            state = state,
            modelLabel = currentModelName,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onBack = { nav.back() },
            onOpenModels = { showModels = true },
            onLoadOlder = { vm.loadOlder() },
            onFork = {
                vm.fork { newSessionId ->
                    if (!newSessionId.isNullOrBlank()) nav.go(Route.Chat(newSessionId))
                }
            },
        )

        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.items.isEmpty() && state.error == null -> Text(
                    text = "今天想聊点什么？",
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (state.hasMore) {
                        item(key = "load-more") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = if (state.loadingOlder) "加载中…" else "加载更早消息",
                                    style = typography.labelMedium,
                                    color = colors.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !state.loadingOlder) { vm.loadOlder() }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    state.error?.let { error ->
                        item(key = "error") {
                            Text(
                                text = error,
                                style = typography.labelMedium,
                                color = colors.error,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(state.items, key = { it.key }) { item ->
                        ChatItemRow(item = item, thinkSeconds = thinkSeconds[item.key])
                    }
                    item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

        Column(modifier = Modifier.fillMaxWidth().background(colors.background)) {
            if (toast != null) {
                Text(
                    text = toast.orEmpty(),
                    style = typography.labelMedium,
                    color = colors.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (pendingForSession.isNotEmpty() || state.goal != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    pendingForSession.forEach { request ->
                        when (request.kind) {
                            "approval" -> ApprovalCard(
                                request = request,
                                busy = request.eventId in busyEvents,
                                onDecision = { allow -> vm.answerApproval(request.eventId, allow) },
                            )

                            "question" -> QuestionCard(
                                request = request,
                                busy = request.eventId in busyEvents,
                                onSubmit = { answers -> vm.answerQuestion(request.eventId, answers) },
                            )
                        }
                    }
                    state.goal?.let { goal ->
                        GoalBar(
                            goal = goal,
                            todos = state.todos,
                            busy = false,
                            onPause = { vm.pauseGoal() },
                            onResume = { vm.resumeGoal() },
                            onComplete = { vm.completeGoal() },
                        )
                    }
                }
            }
            ChatInputBar(
                draft = draft,
                onDraftChange = { vm.onDraftChange(it) },
                attachments = attachments,
                onRemoveAttachment = { vm.removeAttachment(it) },
                onPickImages = {
                    runCatching { imagePicker.launch(arrayOf("image/*")) }
                        .onFailure { vm.showToast("无法打开系统图片选择器") }
                },
                running = state.running,
                steer = steer,
                onSteerChange = { vm.setSteer(it) },
                onSend = { vm.send() },
                onCancel = { vm.cancel() },
            )
        }
    }

    if (showModels) {
        ModelPickerDialog(
            models = modelCatalog,
            current = state.selection,
            onPick = { vm.selectModel(it) },
            onDismiss = { showModels = false },
        )
    }
}

// ------------------------------------------------------------------ 顶栏

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    modelLabel: String,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onLoadOlder: () -> Unit,
    onFork: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .height(44.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("←", style = typography.titleLarge, color = colors.onSurface)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "对话" },
                style = typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.running) {
                Text("正在生成…", style = typography.labelSmall, color = colors.primary)
            }
        }
        Text(
            text = modelLabel,
            style = typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenModels)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onMenuOpenChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                Text("⋯", style = typography.titleMedium, color = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(
                    text = { Text("从回合创建分支", style = typography.bodyMedium, color = colors.onSurface) },
                    onClick = {
                        onMenuOpenChange(false)
                        onFork()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "加载更早消息",
                            style = typography.bodyMedium,
                            color = if (state.hasMore) colors.onSurface else colors.onSurfaceVariant,
                        )
                    },
                    enabled = state.hasMore,
                    onClick = {
                        onMenuOpenChange(false)
                        onLoadOlder()
                    },
                )
            }
        }
    }
}

private fun snapshotFlowListBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
): kotlinx.coroutines.flow.Flow<Boolean> = snapshotFlow {
    val info = listState.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    info.totalItemsCount == 0 || (last != null && last.index >= info.totalItemsCount - 1)
}
