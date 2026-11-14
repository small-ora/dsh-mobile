package com.smallora.dshmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smallora.dshmobile.core.AppGraph
import com.smallora.dshmobile.core.BackgroundService
import com.smallora.dshmobile.core.Notifier
import com.smallora.dshmobile.ui.DshApp
import com.smallora.dshmobile.ui.theme.DshTheme

class MainActivity : ComponentActivity() {

    private var openSessionId: String? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppGraph.init(this)
        Notifier.ensureChannels(this)
        openSessionId = intent?.getStringExtra(Notifier.EXTRA_SESSION_ID)
        requestNotificationPermissionIfNeeded()
        if (container.hasSession() && container.store.bgIntervalSec > 0) {
            BackgroundService.start(this)
        }
        setContent {
            val themeMode by container.store.themeModeFlow.collectAsStateWithLifecycle()
            DshTheme(themeMode) {
                DshApp(container, openSessionId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sessionId = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
        if (!sessionId.isNullOrBlank()) {
            openSessionId = sessionId
            recreate()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}
