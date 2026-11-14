package com.smallora.dshmobile.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.DeviceInfo
import com.smallora.dshmobile.core.ServerProfile
import com.smallora.dshmobile.ui.AppNavState
import com.smallora.dshmobile.ui.Route
import kotlinx.coroutines.delay

private const val REPO_URL = "https://github.com/small-ora/dsh-mobile"

/**
 * 设置页：分组行式列表（无卡片阴影）。
 * v3：不提供隧道配置，只保留「远程访问」说明卡（Cloudflare 通道 → 本机 8790）。
 */
@Composable
fun SettingsScreen(container: AppContainer, nav: AppNavState) {
    val vm: SettingsViewModel = viewModel(key = "settings", factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val context = LocalContext.current

    var showServers by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showBgInterval by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2600)
            vm.clearToast()
        }
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = typography.titleMedium, color = colors.onSurface)
        }
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)

        LazyColumn(modifier = Modifier.weight(1f)) {
            item { SectionHeader("账号") }
            item {
                SettingRow(
                    title = "服务器",
                    subtitle = state.currentServer?.base ?: "未配置服务器",
                    value = state.currentServer?.name,
                    showDivider = true,
                    onClick = { showServers = true },
                )
            }
            item {
                SettingRow(
                    title = "修改口令",
                    subtitle = "登录口令（scrypt 存储）",
                    showDivider = true,
                    onClick = { showPassword = true },
                )
            }
            item {
                SettingRow(
                    title = "设备管理",
                    subtitle = "查看已登录设备并撤销",
                    value = if (state.devicesLoading) "加载中…" else "${state.devices.size} 台",
                    showDivider = true,
                    onClick = {
                        showDevices = true
                        vm.refreshDevices()
                    },
                )
            }
            item {
                SettingRow(
                    title = "退出登录",
                    subtitle = "清除本机 token 并停止后台轮询",
                    titleColor = colors.error,
                    showDivider = false,
                    onClick = { showLogout = true },
                )
            }

            item { SectionHeader("连接") }
            item {
                SettingRow(
                    title = "连接状态",
                    subtitle = connection.server ?: state.currentServer?.base,
                    value = if (connection.online) "已连接" else "未连接",
                    valueColor = if (connection.online) colors.primary else colors.error,
                    showDivider = true,
                )
            }
            item { RemoteAccessCard(server = state.currentServer?.base) }
            item {
                SettingRow(
                    title = "通知提醒",
                    subtitle = "审批 / 提问到达时提醒",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = state.notifyApprovals,
                            onCheckedChange = { vm.setNotify(it) },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "任务结束提醒",
                    subtitle = "回合结束 / 任务完成时提醒",
                    showDivider = true,
                    trailing = {
                        Switch(
                            checked = state.notifyTasks,
                            onCheckedChange = { vm.setNotifyTasks(it) },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "后台刷新",
                    subtitle = "有审批 / 提问时轮询网关的频率",
                    value = intervalLabel(state.bgIntervalSec),
                    showDivider = true,
                    onClick = { showBgInterval = true },
                )
            }
            item {
                SettingRow(
                    title = "外观",
                    value = themeLabel(state.themeMode),
                    showDivider = false,
                    onClick = { showTheme = true },
                )
            }

            item { SectionHeader("关于") }
            item {
                SettingRow(
                    title = "版本",
                    value = versionName,
                    showDivider = true,
                )
            }
            item {
                SettingRow(
                    title = "开源仓库",
                    subtitle = "small-ora/dsh-mobile",
                    showDivider = true,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                        }
                    },
                )
            }
            item {
                SettingRow(
                    title = "DSH 移动远程客户端",
                    subtitle = "DeepSeek 官方简洁风 · 原生 Compose",
                    showDivider = false,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        if (state.toast != null) {
            Text(
                text = state.toast.orEmpty(),
                style = typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (showServers) {
        ServerManagerDialog(
            servers = state.servers,
            currentId = state.currentServerId,
            onSwitch = { id ->
                showServers = false
                vm.switchServer(id) { nav.go(Route.Login) }
            },
            onRemove = { id ->
                vm.removeServer(id) { nav.go(Route.Login) }
            },
            onAdd = { base ->
                showServers = false
                vm.addServer(base, switchToIt = true) { nav.go(Route.Login) }
            },
            onDismiss = { showServers = false },
        )
    }

    if (showPassword) {
        ChangePasswordDialog(
            busy = state.busy,
            onSubmit = { current, next, done ->
                vm.changePassword(current, next) { ok -> done(ok) }
            },
            onDismiss = { showPassword = false },
        )
    }

    if (showDevices) {
        DevicesDialog(
            devices = state.devices,
            loading = state.devicesLoading,
            onRefresh = { vm.refreshDevices() },
            onRevoke = { id -> vm.revokeDevice(id) { nav.reset(Route.Login) } },
            onDismiss = { showDevices = false },
        )
    }

    if (showBgInterval) {
        ChoiceDialog(
            title = "后台刷新间隔",
            options = listOf(30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟", 900 to "15 分钟"),
            selected = state.bgIntervalSec,
            onSelect = { vm.setBgInterval(it) },
            onDismiss = { showBgInterval = false },
        )
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    vm.logout { nav.reset(Route.Login) }
                }) {
                    Text("退出登录", style = typography.bodyMedium, color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) {
                    Text("取消", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            },
            title = {
                Text(
                    "退出登录",
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
            },
            text = {
                Text(
                    "将撤销当前设备的 token 并停止后台轮询；再次使用需要重新输入口令。",
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            },
            containerColor = colors.surface,
        )
    }

    if (showTheme) {
        val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
        AlertDialog(
            onDismissRequest = { showTheme = false },
            confirmButton = {
                TextButton(onClick = { showTheme = false }) {
                    Text("完成", style = typography.bodyMedium, color = colors.primary)
                }
            },
            title = {
                Text(
                    "外观",
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
            },
            text = {
                Column {
                    options.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    vm.setTheme(mode)
                                    showTheme = false
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.themeMode == mode, onClick = {
                                vm.setTheme(mode)
                                showTheme = false
                            })
                            Text(label, style = typography.bodyLarge, color = colors.onSurface)
                        }
                    }
                }
            },
            containerColor = colors.surface,
        )
    }
}

// ------------------------------------------------------------------ 行 / 分组

@Composable
private fun SectionHeader(text: String) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Text(
        text = text,
        style = typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color? = null,
    valueColor: Color? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val base = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = Modifier.fillMaxWidth().then(base)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = typography.bodyLarge, color = titleColor ?: colors.onSurface)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = typography.labelMedium,
                    color = valueColor ?: colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Text("›", style = typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.outlineVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun RemoteAccessCard(server: String?) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "远程访问",
                    style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text("说明", style = typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Text(
                text = "当前服务器：${server ?: "未配置"}",
                style = typography.bodyMedium,
                color = colors.onSurface,
            )
            Text(
                text = "地址格式：局域网用 http://IP:8790；公网请用 https://域名",
                style = typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = "公网访问由你自己的 Cloudflare 通道把域名转发到本机 8790 端口；App 内不保存任何隧道凭据。",
                style = typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = "公网已暴露：请使用强口令，并定期在「设备管理」中撤销不用的设备。",
                style = typography.labelMedium,
                color = colors.error,
            )
        }
    }
}

// ------------------------------------------------------------------ 对话框

@Composable
private fun ServerManagerDialog(
    servers: List<ServerProfile>,
    currentId: String?,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var base by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", style = typography.bodyMedium, color = colors.primary)
            }
        },
        title = {
            Text(
                "服务器",
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (servers.isEmpty()) {
                    Text("还没有保存的服务器", style = typography.labelMedium, color = colors.onSurfaceVariant)
                }
                servers.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = profile.id == currentId,
                            onClick = { onSwitch(profile.id) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = typography.bodyMedium,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = profile.base,
                                style = typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "删除",
                            style = typography.labelSmall,
                            color = colors.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onRemove(profile.id) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                Text("添加服务器", style = typography.bodyMedium, color = colors.onSurface)
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("服务器地址（公网用 https://，局域网 http://192.168.x.x:8790）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        if (base.isNotBlank()) onAdd(base)
                    },
                    enabled = base.isNotBlank(),
                ) {
                    Text("添加并切换", style = typography.bodyMedium, color = colors.primary)
                }
            }
        },
        containerColor = colors.surface,
    )
}

@Composable
private fun ChangePasswordDialog(
    busy: Boolean,
    onSubmit: (String, String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    when {
                        current.isBlank() || next.isBlank() -> error = "请填写当前口令与新口令"
                        next.length < 8 -> error = "新口令至少 8 位"
                        next != confirm -> error = "两次输入的新口令不一致"
                        else -> {
                            error = null
                            onSubmit(current, next) { ok ->
                                if (ok) onDismiss() else error = "修改失败：请检查当前口令"
                            }
                        }
                    }
                },
            ) {
                Text(if (busy) "提交中…" else "确定", style = typography.bodyMedium, color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        },
        title = {
            Text(
                "修改口令",
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = { Text("当前口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = { Text("新口令（至少 8 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("确认新口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error.orEmpty(), style = typography.labelMedium, color = colors.error)
                }
            }
        },
        containerColor = colors.surface,
    )
}

@Composable
private fun DevicesDialog(
    devices: List<DeviceInfo>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var pendingRevoke by remember { mutableStateOf<DeviceInfo?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = typography.bodyMedium, color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh) {
                Text("刷新", style = typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        },
        title = {
            Text(
                "设备管理",
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (loading && devices.isEmpty()) {
                    Text("加载中…", style = typography.labelMedium, color = colors.onSurfaceVariant)
                } else if (devices.isEmpty()) {
                    Text("暂无已登录设备", style = typography.labelMedium, color = colors.onSurfaceVariant)
                }
                devices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.name,
                                style = typography.bodyMedium,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = device.id.take(12),
                                style = typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "撤销",
                            style = typography.labelMedium,
                            color = colors.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { pendingRevoke = device }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        containerColor = colors.surface,
    )

    val target = pendingRevoke
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            confirmButton = {
                TextButton(onClick = {
                    onRevoke(target.id)
                    pendingRevoke = null
                }) {
                    Text("撤销", style = typography.bodyMedium, color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text("取消", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            },
            title = {
                Text(
                    "撤销设备",
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
            },
            text = {
                Text(
                    "撤销「${target.name}」后，该设备需要重新输入口令登录。",
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
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
                title,
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
            )
        },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = {
                            onSelect(value)
                            onDismiss()
                        })
                        Text(label, style = typography.bodyLarge, color = colors.onSurface)
                    }
                }
            }
        },
        containerColor = colors.surface,
    )
}

// ------------------------------------------------------------------ 标签

private fun themeLabel(mode: String): String = when (mode) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

private fun intervalLabel(seconds: Int): String = when (seconds) {
    30 -> "30 秒"
    60 -> "1 分钟"
    300 -> "5 分钟"
    900 -> "15 分钟"
    else -> "${seconds / 60} 分钟"
}
