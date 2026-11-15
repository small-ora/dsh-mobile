package com.smallora.dshmobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.SessionRow
import com.smallora.dshmobile.core.WorkspaceRow
import com.smallora.dshmobile.ui.AppNavState
import com.smallora.dshmobile.ui.Route
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话页（DeepSeek 简洁风）：44–52dp 顶栏 + 工作区筛选 + 下拉刷新 + 行式会话列表。
 * - 工作区选择：`全部工作区 ▾`，列表来自 repo.workspaces（名称 + 会话数），选中打勾。
 * - 列表使用 repo.filteredSessions；搜索时对搜索结果同样按工作区过滤。
 * - 错误条显示 core 的 connection.error 原文并提供「重试」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(container: AppContainer, nav: AppNavState) {
    val vm: SessionsViewModel = viewModel(
        factory = viewModelFactory { initializer { SessionsViewModel(container) } },
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val selectedWorkspace by vm.selectedWorkspace.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var searchMode by remember { mutableStateOf(false) }
    var workspaceMenuOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionRow?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    val searching = searchMode && query.isNotBlank()
    val visibleSessions = if (searching) {
        searchResults.filter { matchesWorkspace(it, selectedWorkspace) }
    } else {
        sessions
    }
    val expiring = error?.contains("登录") == true || error?.contains("过期") == true

    if (renameTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = renameTarget
                    if (target != null) vm.rename(target.id, renameText.trim())
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        // 根 Scaffold（App.kt）已统一处理状态栏/导航栏安全区；内层若再消费一次，
        // 顶部会多出一整条状态栏高度的空白（v0.1.10 修复）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (searchMode) "搜索会话" else "对话",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (searchMode) {
                    TextButton(onClick = {
                        searchMode = false
                        vm.search("")
                    }) { Text("取消") }
                } else {
                    TextButton(onClick = { searchMode = true }) { Text("搜索") }
                    TextButton(
                        onClick = {
                            vm.create { id -> nav.go(Route.Chat(id)) }
                        },
                    ) { Text("+ 新建") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 工作区选择器：顶栏标题下方一行可点文字（全部工作区 ▾）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceSelector(
                    workspaces = workspaces,
                    selected = selectedWorkspace,
                    menuOpen = workspaceMenuOpen,
                    onMenuOpenChange = { workspaceMenuOpen = it },
                    onSelect = {
                        vm.selectWorkspace(it)
                        workspaceMenuOpen = false
                    },
                )
                Spacer(Modifier.weight(1f))
                if (selectedWorkspace != null) {
                    Text(
                        text = "清除筛选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.selectWorkspace(null) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (searchMode) {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::search,
                    placeholder = { Text("搜索会话内容…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // 可操作错误条：显示 core 的 connection.error 原文 + 重试
            val errorText = connection.error?.takeIf { it.isNotBlank() } ?: error
            errorText?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (expiring) {
                        TextButton(onClick = { nav.reset(Route.Login) }) { Text("去登录") }
                    }
                    TextButton(onClick = { vm.refresh() }) { Text("重试") }
                }
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = vm::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 始终用 LazyColumn（空态也放在里面），保证空列表时下拉刷新仍可用
                LazyColumn(Modifier.fillMaxSize()) {
                    if (visibleSessions.isEmpty()) {
                        item(key = "empty") {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        searching -> "没有匹配的会话"
                                        selectedWorkspace != null -> "该工作区暂无会话"
                                        else -> "暂无会话，点右上角「新建」"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(visibleSessions, key = { it.id }) { row ->
                            SessionRowItem(
                                row = row,
                                onOpen = { nav.go(Route.Chat(row.id)) },
                                onRename = {
                                    renameTarget = row
                                    renameText = row.title ?: ""
                                },
                                onArchive = { vm.archive(row.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- 工作区选择器 */

@Composable
private fun WorkspaceSelector(
    workspaces: List<WorkspaceRow>,
    selected: String?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val currentName = workspaces.firstOrNull { it.path == selected }?.name ?: "全部工作区"

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onMenuOpenChange(true) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentName,
                style = typography.labelMedium,
                color = if (selected != null) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", style = typography.labelSmall, color = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            DropdownMenuItem(
                text = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "全部工作区",
                            style = typography.bodyMedium,
                            color = if (selected == null) colors.primary else colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected == null) {
                            Text("✓", style = typography.labelMedium, color = colors.primary)
                        }
                    }
                },
                onClick = { onSelect(null) },
            )
            workspaces.forEach { workspace ->
                val checked = workspace.path == selected
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = workspace.name,
                                    style = typography.bodyMedium,
                                    color = if (checked) colors.primary else colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = workspace.path,
                                    style = typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${workspace.count}",
                                style = typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            if (checked) {
                                Spacer(Modifier.width(6.dp))
                                Text("✓", style = typography.labelMedium, color = colors.primary)
                            }
                        }
                    },
                    onClick = { onSelect(workspace.path) },
                )
            }
        }
    }
}

private fun matchesWorkspace(row: SessionRow, selected: String?): Boolean {
    if (selected == null) return true
    val cwd = row.cwd ?: return false
    val root = selected.trimEnd('/')
    return cwd == root || cwd.startsWith("$root/")
}

/* ---------------------------------------------------------------- 会话行 */

@Composable
private fun SessionRowItem(
    row: SessionRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.title?.takeIf { it.isNotBlank() } ?: "未命名会话",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.running) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    // 工作区名（cwd basename）：多工作区时避免混淆
                    workspaceName(row.cwd)?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        formatTime(row.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.goal?.let { goal ->
                        Spacer(Modifier.width(10.dp))
                        Text(
                            goalLabel(goal.phase),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.todos.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${row.todos.count { it.status == "completed" }}/${row.todos.size} 待办",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋯") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("归档") },
                        onClick = {
                            menuOpen = false
                            onArchive()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun workspaceName(cwd: String?): String? {
    val path = cwd?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    return path.substringAfterLast('/').ifBlank { null }
}

private fun goalLabel(phase: String): String = when (phase) {
    "active" -> "目标进行中"
    "paused" -> "目标已暂停"
    "blocked" -> "目标受阻"
    "complete" -> "目标已完成"
    else -> "目标：$phase"
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = System.currentTimeMillis()
    val delta = now - timestamp
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L} 天前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
