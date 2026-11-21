package com.smallora.dshmobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.SessionRow
import com.smallora.dshmobile.core.UnauthorizedException
import com.smallora.dshmobile.core.WorkspaceRow
import com.smallora.dshmobile.core.searchUnavailableReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 会话列表：工作区筛选、刷新、搜索、新建、重命名、归档。 */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.repo

    /** core 已按选中工作区过滤后的会话列表（默认全部工作区）。 */
    val sessions: StateFlow<List<SessionRow>> = repo.filteredSessions

    val workspaces: StateFlow<List<WorkspaceRow>> = repo.workspaces

    /** null = 全部工作区。 */
    val selectedWorkspace: StateFlow<String?> = repo.selectedWorkspace

    val connection = repo.connection

    /** DSH Workspace 注册表（用于「新建会话」时选择工作区）。 */
    val workspaceInfos = repo.workspaceInfos

    /** 已归档会话 id（列表已过滤；保留给 UI 诊断/后续「显示归档」用）。 */
    val archivedSessionIds = repo.archivedSessionIds

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SessionRow>>(emptyList())
    val searchResults: StateFlow<List<SessionRow>> = _searchResults.asStateFlow()

    /** 非空表示「当前主机未启用会话搜索」，值为给用户的中文提示；命中后不再重复请求。 */
    private val _searchUnavailable = MutableStateFlow<String?>(null)
    val searchUnavailable: StateFlow<String?> = _searchUnavailable.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                repo.refresh()
                _error.value = null
            } catch (e: UnauthorizedException) {
                _error.value = "登录已过期，请重新登录"
            } catch (e: Exception) {
                _error.value = e.message ?: "刷新失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectWorkspace(path: String?) {
        repo.selectWorkspace(path)
    }

    fun search(raw: String) {
        _query.value = raw
        val query = raw.trim()
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        // 已知当前主机未启用搜索：不再发请求，直接展示提示（避免每次输入都弹英文错误）
        if (_searchUnavailable.value != null) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching { repo.search(query) }
                .onSuccess { _searchResults.value = it }
                .onFailure { e ->
                    val reason = searchUnavailableReason(e)
                    if (reason != null) {
                        _searchUnavailable.value = reason
                        _searchResults.value = emptyList()
                        _toast.value = reason
                    } else {
                        _toast.value = e.message ?: "搜索失败"
                    }
                }
        }
    }

    /** 新建会话；[workspaceId] 为空时由 DSH 使用默认工作区。 */
    fun create(workspaceId: String?, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = repo.createSession(workspaceId)
            if (id != null) {
                onCreated(id)
            } else {
                _toast.value = "新建会话失败"
            }
        }
    }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            if (!repo.rename(id, title)) _toast.value = "重命名失败"
        }
    }

    fun archive(id: String) {
        viewModelScope.launch {
            if (!repo.archive(id)) _toast.value = "归档失败"
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}
