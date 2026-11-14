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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.ChatItem
import com.smallora.dshmobile.core.ChatUiState
import com.smallora.dshmobile.core.resolveMarkdownImageSrc
import com.smallora.dshmobile.ui.AppNavState
import com.smallora.dshmobile.ui.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 聊天页：DeepSeek 官方简洁风。
 * 顶栏 44dp（返回/标题/运行态/模型/更多），消息区行式列表，审批/提问/目标卡片固定在输入框上方，
 * 输入框圆角 22dp + 蓝色圆形发送键，运行中显示停止与排队/插队。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val permissionOverride by vm.permissionOverride.collectAsStateWithLifecycle()
    val queueBusyIds by vm.queueBusyIds.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showModels by remember { mutableStateOf(false) }
    val previewImage = remember { mutableStateOf<ImageBitmap?>(null) }
    // 图片环境：附件 fetchAttachment / Markdown 远程 OkHttp(15s) / 工作区 fetchFile；失败返回 null 由组件显示占位
    val imageEnv = remember(container, state.cwd) {
        ChatImageEnv(
            loadBytes = { source ->
                when (source) {
                    is ChatImageSource.Attachment -> container.api.fetchAttachment(source.sessionId, source.attachmentId)

                    is ChatImageSource.Remote -> fetchRemoteImage(source.url)
                    is ChatImageSource.Workspace -> container.api.fetchFile(source.path)
                    is ChatImageSource.DataUrl -> null
                }
            },
            onPreview = { previewImage.value = it },
            resolve = { src -> resolveMarkdownImageSrc(src, state.cwd)?.toChatImageSource() },
        )
    }
    var showPermissions by remember { mutableStateOf(false) }
    var showFunctions by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var stickToBottom by remember { mutableStateOf(true) }
    var commandMenuDismissed by remember { mutableStateOf(false) }

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

    // 斜杠指令：输入以 / 开头时按前缀匹配 core 的 commands，点击补全
    val slashText = draft.trimStart()
    val commandQuery = if (slashText.startsWith("/") && slashText.length <= 40) {
        slashText.removePrefix("/").substringBefore(' ').trim()
    } else {
        null
    }
    val matchedCommands = if (commandQuery == null) {
        emptyList()
    } else {
        state.commands.filter { it.name.startsWith(commandQuery, ignoreCase = true) }
    }
    LaunchedEffect(draft) { commandMenuDismissed = false }
    val showCommandMenu = commandQuery != null && matchedCommands.isNotEmpty() && !commandMenuDismissed
    val permissionCurrent = permissionOverride ?: state.permission?.current
    // 有 permission 数据才提供入口（current 为 null 时副标题显示「未提供」占位）
    val permissionLabel = permissionOverride ?: state.permission?.current

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
    // 中间过程合并：一个回合内的工具/思考/中间步骤折叠为「过程 · N 项」，最终回答保持独立
    val displayItems = remember(state.items) { buildDisplayItems(state.items) }
    val streamingTick = state.items.lastOrNull { it is ChatItem.Assistant }
        ?.let { (it as ChatItem.Assistant).text.length + if (it.streaming) 1 else 0 } ?: 0
    LaunchedEffect(state.items.size, displayItems.size, streamingTick) {
        if (displayItems.isNotEmpty() && stickToBottom) {
            val lastIndex = displayItems.size - 1 + if (state.hasMore) 1 else 0
            listState.scrollToItem(lastIndex.coerceAtLeast(0))
        }
    }

    val pendingForSession = state.pending.filter { it.sessionId == sessionId }
    val currentModelName = modelCatalog.firstOrNull {
        state.selection != null && it.provider == state.selection?.provider && it.model == state.selection?.model
    }?.name ?: state.selection?.model ?: "模型"
    val effortLabel = state.selection?.reasoningEffort?.let { reasoningLabel(it) } ?: "自动"
    val modelSubtitle = "$currentModelName · $effortLabel"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        ChatTopBar(
            state = state,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onBack = { nav.back() },
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
                    items(displayItems, key = { displayKey(it) }) { display ->
                        when (display) {
                            is DisplayItem.Single -> ChatItemRow(
                                item = display.item,
                                thinkSeconds = thinkSeconds[display.item.key],
                                imageEnv = imageEnv,
                            )

                            is DisplayItem.Group -> ProcessGroupRow(
                                group = display,
                                thinkSeconds = thinkSeconds,
                                imageEnv = imageEnv,
                            )
                        }
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
            QueuedMessagesRow(
                queued = state.queued,
                busyIds = queueBusyIds,
                onSteer = { vm.steerQueued(it) },
                onRemove = { vm.removeQueued(it) },
            )
            ChatInputBar(
                draft = draft,
                onDraftChange = { vm.onDraftChange(it) },
                attachments = attachments,
                onRemoveAttachment = { vm.removeAttachment(it) },
                onOpenFunctions = { showFunctions = true },
                running = state.running,
                steer = steer,
                onSteerChange = { vm.setSteer(it) },
                onSend = { vm.send() },
                onCancel = { vm.cancel() },
                commands = matchedCommands,
                showCommandMenu = showCommandMenu,
                onDismissCommandMenu = { commandMenuDismissed = true },
                onPickCommand = { name ->
                    vm.onDraftChange("/$name ")
                    commandMenuDismissed = true
                },
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

    if (showFunctions) {
        ModalBottomSheet(
            onDismissRequest = { showFunctions = false },
            containerColor = colors.surface,
        ) {
            FunctionSheetContent(
                modelSubtitle = modelSubtitle,
                permissionSubtitle = permissionLabel ?: "未提供",
                onModel = {
                    showFunctions = false
                    showModels = true
                },
                onPermission = {
                    showFunctions = false
                    if (state.permission != null) {
                        showPermissions = true
                    } else {
                        vm.showToast("暂无权限数据")
                    }
                },
                onImages = {
                    showFunctions = false
                    runCatching { imagePicker.launch(arrayOf("image/*")) }
                        .onFailure { vm.showToast("无法打开系统图片选择器") }
                },
            )
        }
    }

    val permission = state.permission
    if (showPermissions && permission != null) {
        PermissionDialog(
            current = permissionCurrent,
            options = permission.options,
            onPick = { option ->
                showPermissions = false
                vm.setPermission(option.value)
            },
            onDismiss = { showPermissions = false },
        )
    }

    previewImage.value?.let { bitmap ->
        FullScreenImagePreview(bitmap = bitmap, onDismiss = { previewImage.value = null })
    }
}

private fun displayKey(item: DisplayItem): String = when (item) {
    is DisplayItem.Single -> "s:${item.item.key}"
    is DisplayItem.Group -> "g:${item.key}"
}

// ------------------------------------------------------------------ 顶栏

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
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
        Text(
            text = state.title.ifBlank { "对话" },
            style = typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
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
