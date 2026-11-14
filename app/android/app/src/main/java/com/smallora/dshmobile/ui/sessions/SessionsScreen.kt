package com.smallora.dshmobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.smallora.dshmobile.ui.AppNavState
import com.smallora.dshmobile.ui.Route
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(container: AppContainer, nav: AppNavState) {
    val vm: SessionsViewModel = viewModel(
        factory = viewModelFactory { initializer { SessionsViewModel(container) } },
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var searchMode by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionRow?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    val visibleSessions = if (searchMode && query.isNotBlank()) searchResults else sessions
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

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

            error?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                }
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = vm::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (visibleSessions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchMode && query.isNotBlank()) "没有匹配的会话" else "暂无会话，点右上角「新建」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
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
