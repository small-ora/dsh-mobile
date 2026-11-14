package com.smallora.dshmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.smallora.dshmobile.core.AppContainer
import com.smallora.dshmobile.ui.chat.ChatScreen
import com.smallora.dshmobile.ui.files.FilesScreen
import com.smallora.dshmobile.ui.login.LoginScreen
import com.smallora.dshmobile.ui.sessions.SessionsScreen
import com.smallora.dshmobile.ui.settings.SettingsScreen

/** 导航路由（interfaces §5.4；v3 已去掉 Tunnel）。 */
sealed interface Route {
    data object Login : Route
    data object Sessions : Route
    data class Chat(val sessionId: String) : Route
    data object Files : Route
    data object Settings : Route
}

/** 轻量导航状态（无 Navigation 依赖）。 */
class AppNavState(initial: Route = Route.Sessions) {
    var route: Route by mutableStateOf(initial)
        private set

    private val stack = ArrayList<Route>()

    fun go(route: Route) {
        if (this.route == route) return
        stack.add(this.route)
        this.route = route
    }

    /** 根级切换（登录成功/底部 Tab），清空返回栈。 */
    fun reset(route: Route) {
        stack.clear()
        this.route = route
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        this.route = stack.removeAt(stack.size - 1)
        return true
    }

    fun canGoBack(): Boolean = stack.isNotEmpty()
}

private data class TabItem(val route: Route, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Route.Sessions, "对话", Icons.Filled.Home),
    TabItem(Route.Files, "文件", Icons.Filled.List),
    TabItem(Route.Settings, "设置", Icons.Filled.Settings),
)

@Composable
fun DshApp(container: AppContainer, openSessionId: String? = null) {
    val initial = remember { if (container.hasSession()) Route.Sessions else Route.Login }
    val nav = remember { AppNavState(initial) }
    var openedFromIntent by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openSessionId) {
        val id = openSessionId
        if (!id.isNullOrBlank() && !openedFromIntent) {
            openedFromIntent = true
            nav.go(Route.Chat(id))
        }
    }

    val showBottomBar = nav.route == Route.Sessions || nav.route == Route.Files || nav.route == Route.Settings

    BackHandler(enabled = nav.canGoBack()) {
        nav.back()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = nav.route == tab.route,
                            onClick = {
                                if (nav.route != tab.route) nav.reset(tab.route)
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val route = nav.route) {
                Route.Login -> LoginScreen(container) {
                    nav.reset(Route.Sessions)
                }

                Route.Sessions -> SessionsScreen(container, nav)

                is Route.Chat -> ChatScreen(route.sessionId, container, nav)

                Route.Files -> FilesScreen(container)

                Route.Settings -> SettingsScreen(container, nav)
            }
        }
    }
}
