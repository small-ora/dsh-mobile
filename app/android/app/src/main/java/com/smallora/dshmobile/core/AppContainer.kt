package com.smallora.dshmobile.core

import android.content.Context

/**
 * 依赖容器（interfaces §5.3）：UI 通过 container.store/api/repo/files/admin 访问 core。
 * 登录成功后 token 落 SharedPreferences，并同步到 ApiClient。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val store = SettingsStore(appContext)
    val api = ApiClient(appContext)
    val repo = SessionRepository(api, store)
    val files = FilesClient(api)
    val admin = AdminClient(api)

    init {
        restoreSession()
    }

    /** 启动/切换服务器后恢复当前连接凭据。 */
    fun restoreSession() {
        val server = store.currentServer()
        val token = server?.let { store.token(it.id) }
        api.setSession(server?.base ?: "", token)
    }

    fun hasSession(): Boolean =
        store.currentServer() != null && !store.currentToken().isNullOrBlank()

    /** 切换已保存的服务器；返回是否已有可用 token（不需要重新登录）。 */
    fun switchServer(id: String): Boolean {
        val server = store.servers.firstOrNull { it.id == id } ?: return false
        store.switchServer(id)
        repo.stop()
        val token = store.token(id)
        api.setSession(server.base, token)
        return !token.isNullOrBlank()
    }

    suspend fun login(base: String, password: String, deviceName: String): LoginResult {
        val result = api.login(base, password, deviceName)
        val profile = store.upsertServer(base)
        store.saveToken(profile.id, result.token)
        store.currentServerId = profile.id
        store.deviceName = deviceName
        store.passwordChanged = !result.mustChangePassword
        repo.stop()
        api.setSession(profile.base, result.token)
        // 登录成功且用户开启了后台通知：启动前台轮询服务（C）
        if (store.bgIntervalSec > 0) {
            runCatching { BackgroundService.start(appContext) }
        }
        return result
    }

    suspend fun logout() {
        repo.stop()
        runCatching { BackgroundService.stop(appContext) }
        runCatching { api.logout() }
        store.clearSession()
        api.clearSession()
    }

    fun removeServer(id: String) {
        store.removeServer(id)
        if (store.currentServerId == null) repo.stop()
        restoreSession()
    }
}

/** 进程级单例：Activity / Service 共享同一份状态。 */
object AppGraph {

    @Volatile
    private var instance: AppContainer? = null

    fun init(context: Context): AppContainer {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = AppContainer(context.applicationContext)
            instance = created
            return created
        }
    }

    fun get(context: Context): AppContainer = init(context)

    fun current(): AppContainer? = instance
}
