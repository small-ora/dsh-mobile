package com.smallora.dshmobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.BackgroundService
import com.smallora.dshmobile.core.ConnState
import com.smallora.dshmobile.core.DeviceInfo
import com.smallora.dshmobile.core.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页状态机：服务器管理 / 改口令 / 设备撤销 / 通知 / 后台间隔 / 外观。
 * 远程访问只读说明（Cloudflare 通道 → 本机 8790），v3 不在 App 内配置隧道凭据。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val servers: List<ServerProfile> = emptyList(),
        val currentServerId: String? = null,
        val themeMode: String = "system",
        val notifyApprovals: Boolean = true,
        val notifyTasks: Boolean = true,
        val bgIntervalSec: Int = 300,
        val deviceName: String = "",
        val devices: List<DeviceInfo> = emptyList(),
        val devicesLoading: Boolean = false,
        val busy: Boolean = false,
        val toast: String? = null,
    ) {
        val currentServer: ServerProfile?
            get() = servers.firstOrNull { it.id == currentServerId }
    }

    private val store = container.store
    private val admin = container.admin

    private val _state = MutableStateFlow(readStore())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val connection: StateFlow<ConnState> = container.repo.connection

    init {
        // 首次进入刷新一次设备列表（失败静默，用户可点开设备管理重试）
        refreshDevices()
    }

    private fun readStore(): UiState = UiState(
        servers = store.servers,
        currentServerId = store.currentServerId,
        themeMode = store.themeMode,
        notifyApprovals = store.notifyApprovals,
        notifyTasks = store.notifyTasks,
        bgIntervalSec = store.bgIntervalSec,
        deviceName = store.deviceName,
    )

    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    // ------------------------------------------------------------ 服务器

    fun switchServer(id: String, onNeedsLogin: () -> Unit) {
        val profile = store.servers.firstOrNull { it.id == id } ?: return
        val hasToken = runCatching { container.switchServer(id) }.getOrDefault(false)
        _state.update {
            it.copy(servers = store.servers, currentServerId = store.currentServerId)
        }
        if (!hasToken) {
            onNeedsLogin()
        } else {
            viewModelScope.launch { runCatching { container.repo.refresh() } }
            _state.update { it.copy(toast = "已切换到「${profile.name}」") }
        }
    }

    fun addServer(base: String, switchToIt: Boolean, onNeedsLogin: () -> Unit): String? {
        val trimmedBase = base.trim().trimEnd('/')
        if (trimmedBase.isEmpty()) return null
        val profile = runCatching { store.upsertServer(trimmedBase) }.getOrNull() ?: return null
        _state.update { it.copy(servers = store.servers) }
        if (switchToIt) switchServer(profile.id, onNeedsLogin)
        return profile.id
    }

    fun removeServer(id: String, onNeedsLogin: () -> Unit) {
        val removingCurrent = store.currentServerId == id
        runCatching { store.removeServer(id) }
        val remaining = store.servers
        _state.update { it.copy(servers = remaining, currentServerId = store.currentServerId) }
        if (removingCurrent && remaining.isNotEmpty()) {
            switchServer(remaining.first().id, onNeedsLogin)
        }
        _state.update { it.copy(toast = "服务器已删除") }
    }

    // ------------------------------------------------------------ 退出登录

    /** 退出登录：撤销服务端 token、清本地 token、停后台轮询，然后由 UI 回登录页。 */
    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.logout() }
            runCatching { BackgroundService.stop(container.appContext) }
            _state.value = readStore()
            _state.update { it.copy(devices = emptyList(), toast = null) }
            onLoggedOut()
        }
    }

    // ------------------------------------------------------------ 口令 / 设备

    fun changePassword(current: String, next: String, onResult: (Boolean) -> Unit) {
        if (current.isBlank() || next.isBlank()) {
            onResult(false)
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ok = runCatching { admin.changePassword(current, next) }.getOrDefault(false)
            _state.update {
                it.copy(busy = false, toast = if (ok) "口令已更新" else "修改失败：请检查当前口令")
            }
            onResult(ok)
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _state.update { it.copy(devicesLoading = true) }
            val devices = runCatching { admin.devices() }.getOrDefault(emptyList())
            _state.update { it.copy(devices = devices, devicesLoading = false) }
        }
    }

    /**
     * 撤销设备；若撤销的是当前设备，本地 token 已被服务端作废 → 清本地态、停后台轮询并回登录页。
     */
    fun revokeDevice(id: String, onNeedsLogin: () -> Unit = {}) {
        viewModelScope.launch {
            // 先读当前设备身份（撤销后 token 失效，/api/me 会 401）
            val currentDeviceId = runCatching { container.api.meDevice()?.id }.getOrNull()
            val ok = runCatching { admin.revokeDevice(id) }.getOrDefault(false)
            if (!ok) {
                _state.update { it.copy(toast = "撤销失败") }
                return@launch
            }
            if (id == currentDeviceId) {
                runCatching { container.logout() }
                runCatching { BackgroundService.stop(container.appContext) }
                _state.value = readStore()
                _state.update { it.copy(devices = emptyList(), toast = null) }
                onNeedsLogin()
            } else {
                _state.update { it.copy(toast = "设备已撤销") }
                refreshDevices()
            }
        }
    }

    // ------------------------------------------------------------ 偏好

    fun setNotify(value: Boolean) {
        store.notifyApprovals = value
        _state.update { it.copy(notifyApprovals = value) }
    }

    fun setNotifyTasks(value: Boolean) {
        store.notifyTasks = value
        _state.update { it.copy(notifyTasks = value) }
    }

    fun setBgInterval(seconds: Int) {
        store.bgIntervalSec = seconds
        _state.update { it.copy(bgIntervalSec = seconds) }
    }

    fun setTheme(mode: String) {
        store.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
