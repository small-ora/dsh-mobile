package com.smallora.dshmobile.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 本地偏好（SharedPreferences "dsh_mobile"，键名见 interfaces §7）。
 * 说明：设备 token 存在应用私有 SharedPreferences（SPEC §5.1 允许，明确说明不加密）。
 */
class SettingsStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME, "system") ?: "system")

    /** 供 Compose 观察的主题模式（Settings 页修改后 App 自动重组）。 */
    val themeModeFlow: StateFlow<String> = _themeMode.asStateFlow()

    var servers: List<ServerProfile>
        get() = parseServers(prefs.getString(KEY_SERVERS, null))
        set(value) {
            prefs.edit().putString(KEY_SERVERS, serversToJson(value)).apply()
        }

    var currentServerId: String?
        get() = prefs.getString(KEY_CURRENT_SERVER, null)
        set(value) {
            prefs.edit().putString(KEY_CURRENT_SERVER, value).apply()
        }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName()
        set(value) {
            prefs.edit().putString(KEY_DEVICE_NAME, value).apply()
        }

    fun token(serverId: String): String? =
        prefs.getString(KEY_TOKEN_PREFIX + serverId, null)

    fun saveToken(serverId: String, token: String) {
        prefs.edit().putString(KEY_TOKEN_PREFIX + serverId, token).apply()
    }

    fun clearToken(serverId: String) {
        prefs.edit().remove(KEY_TOKEN_PREFIX + serverId).apply()
    }

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) {
            prefs.edit().putString(KEY_THEME, value).apply()
            _themeMode.value = value
        }

    var notifyApprovals: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_APPROVALS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_APPROVALS, value).apply()
        }

    var notifyTasks: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_TASKS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFY_TASKS, value).apply()
        }

    /** 后台轮询间隔（秒）：0 关闭；30|60|300|900 可选（改值即时启停服务）。 */
    var bgIntervalSec: Int
        get() = prefs.getInt(KEY_BG_INTERVAL_SEC, 300)
        set(value) {
            val seconds = value.coerceAtLeast(0)
            prefs.edit().putInt(KEY_BG_INTERVAL_SEC, seconds).apply()
            if (seconds <= 0) {
                runCatching { BackgroundService.stop(appContext) }
            } else if (currentToken() != null) {
                runCatching { BackgroundService.start(appContext) }
            }
        }

    /** 兼容契约里的分钟单位（0.5 分钟不能用 Int 表达，故 0.5 用 bgIntervalSec=30）。 */
    var bgIntervalMin: Int
        get() = (bgIntervalSec / 60).coerceAtLeast(1)
        set(value) {
            bgIntervalSec = if (value <= 0) 0 else value * 60
        }

    var passwordChanged: Boolean
        get() = prefs.getBoolean(KEY_PASSWORD_CHANGED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PASSWORD_CHANGED, value).apply()
        }

    // ---- 服务器 ----

    fun upsertServer(base: String): ServerProfile {
        val normalized = normalizeServerBase(base)
        require(normalized.isNotEmpty()) { "服务器地址无效" }
        val existing = servers.firstOrNull { it.base == normalized }
        if (existing != null) return existing
        val profile = ServerProfile(
            id = normalized,
            name = hostLabel(normalized),
            base = normalized,
        )
        servers = servers + profile
        return profile
    }

    fun removeServer(id: String) {
        servers = servers.filterNot { it.id == id }
        clearToken(id)
        if (currentServerId == id) currentServerId = servers.firstOrNull()?.id
    }

    fun switchServer(id: String) {
        currentServerId = id
    }

    fun currentServer(): ServerProfile? {
        val id = currentServerId ?: return null
        return servers.firstOrNull { it.id == id }
    }

    fun currentToken(): String? {
        val id = currentServerId ?: return null
        return token(id)
    }

    /** 退出登录/撤销设备：清空本地 token 与缓存（SPEC §5.1）。 */
    fun clearSession() {
        val id = currentServerId ?: return
        clearToken(id)
    }

    // ---- 草稿 ----

    fun draft(sessionId: String): String? = prefs.getString(KEY_DRAFT_PREFIX + sessionId, null)

    fun saveDraft(sessionId: String, text: String) {
        if (text.isBlank()) {
            prefs.edit().remove(KEY_DRAFT_PREFIX + sessionId).apply()
        } else {
            prefs.edit().putString(KEY_DRAFT_PREFIX + sessionId, text).apply()
        }
    }

    private fun defaultDeviceName(): String {
        val model = android.os.Build.MODEL ?: "Android"
        return model
    }

    private fun hostLabel(base: String): String {
        val withoutScheme = base.substringAfter("://", base)
        return withoutScheme.substringBefore('/')
    }

    private fun parseServers(raw: String?): List<ServerProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = Json.parseArray(raw) ?: return emptyList()
        return arr.toObjectList().mapNotNull { item ->
            val base = item.strOrNull("base") ?: return@mapNotNull null
            val id = item.strOrNull("id") ?: base
            ServerProfile(id = id, name = item.strOrNull("name") ?: hostLabel(base), base = base)
        }
    }

    private fun serversToJson(items: List<ServerProfile>): String {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("base", item.base),
            )
        }
        return arr.toString()
    }

    companion object {
        const val PREFS_NAME = "dsh_mobile"
        private const val KEY_SERVERS = "servers"
        private const val KEY_CURRENT_SERVER = "currentServer"
        private const val KEY_DEVICE_NAME = "deviceName"
        private const val KEY_TOKEN_PREFIX = "token."
        private const val KEY_THEME = "theme"
        private const val KEY_NOTIFY_APPROVALS = "notifyApprovals"
        private const val KEY_NOTIFY_TASKS = "notifyTasks"
        private const val KEY_BG_INTERVAL_SEC = "bgIntervalSec"
        private const val KEY_PASSWORD_CHANGED = "passwordChanged"
        private const val KEY_DRAFT_PREFIX = "draft."
    }
}
