package com.smallora.dshmobile.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smallora.dshmobile.core.ApiException
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.ServerProfile
import com.smallora.dshmobile.core.UnauthorizedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 登录页状态机：多服务器、口令登录、首次强制改密。 */
class LoginViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val base: String = "",
        val password: String = "",
        val deviceName: String = "",
        val servers: List<ServerProfile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val loggedIn: Boolean = false,
        val mustChangePassword: Boolean = false,
        val changingPassword: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(
            base = container.store.currentServer()?.base ?: "",
            deviceName = container.store.deviceName,
            servers = container.store.servers,
            mustChangePassword = !container.store.passwordChanged && container.store.currentToken() != null,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setBase(value: String) = _state.update { it.copy(base = value, error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun setDeviceName(value: String) = _state.update { it.copy(deviceName = value, error = null) }

    fun login() {
        val current = _state.value
        if (current.loading) return
        if (current.base.isBlank()) {
            _state.update { it.copy(error = "请填写服务器地址") }
            return
        }
        if (current.password.isBlank()) {
            _state.update { it.copy(error = "请填写口令") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val result = container.login(
                    base = current.base,
                    password = current.password,
                    deviceName = current.deviceName.ifBlank { container.store.deviceName },
                )
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        servers = container.store.servers,
                        mustChangePassword = result.mustChangePassword,
                        loggedIn = !result.mustChangePassword,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    /** 首次登录强制改密（current 用登录时输入的口令）。 */
    fun changePassword(next: String) {
        if (next.length < 8) {
            _state.update { it.copy(error = "新口令至少 8 位") }
            return
        }
        if (_state.value.changingPassword) return
        viewModelScope.launch {
            _state.update { it.copy(changingPassword = true, error = null) }
            val ok = container.admin.changePassword(_state.value.password, next)
            if (ok) {
                container.store.passwordChanged = true
                _state.update {
                    it.copy(changingPassword = false, mustChangePassword = false, loggedIn = true)
                }
            } else {
                _state.update {
                    it.copy(changingPassword = false, error = "修改口令失败，请重试")
                }
            }
        }
    }

    /** 选择已保存服务器：有 token 直接进入，无 token 则回填地址待输入口令。 */
    fun selectServer(id: String) {
        val server = container.store.servers.firstOrNull { it.id == id } ?: return
        val hasToken = container.switchServer(id)
        _state.update {
            it.copy(
                base = server.base,
                servers = container.store.servers,
                loggedIn = hasToken,
                error = if (hasToken) null else "该服务器需要重新输入口令",
            )
        }
    }

    fun removeServer(id: String) {
        container.removeServer(id)
        _state.update { it.copy(servers = container.store.servers) }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnauthorizedException -> e.message ?: "登录已过期"
        is ApiException -> when (e.code) {
            "invalid-password" -> "口令错误"
            "rate-limited" -> "尝试过于频繁，请稍后再试"
            "locked" -> "尝试次数过多，已临时锁定，请稍后再试"
            "network-error" -> "无法连接服务器，请检查地址与网络"
            "no-server", "bad-server" -> "服务器地址无效"
            else -> e.message ?: "登录失败"
        }

        else -> e.message ?: "登录失败"
    }
}
