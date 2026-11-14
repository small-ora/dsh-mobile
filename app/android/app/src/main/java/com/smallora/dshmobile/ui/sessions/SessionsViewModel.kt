package com.smallora.dshmobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.core.SessionRow
import com.smallora.dshmobile.core.UnauthorizedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 会话列表：刷新、搜索、新建、重命名、归档。 */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.repo

    val sessions: StateFlow<List<SessionRow>> = repo.sessions
    val connection = repo.connection

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SessionRow>>(emptyList())
    val searchResults: StateFlow<List<SessionRow>> = _searchResults.asStateFlow()

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

    fun search(raw: String) {
        _query.value = raw
        val query = raw.trim()
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching { repo.search(query) }
                .onSuccess { _searchResults.value = it }
                .onFailure { _toast.value = it.message ?: "搜索失败" }
        }
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = repo.createSession()
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
