package com.smallora.dshmobile.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** DeepSeek 官方简洁风品牌蓝（浅/深色均使用）。 */
private val BrandBlue = Color(0xFF4D6BFE)

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlue.copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = BrandBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF1FF),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = BrandBlue,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF5F6F7),
    onSurfaceVariant = Color(0xFF8B8B8B),
    outline = Color(0xFFECECEC),
    outlineVariant = Color(0xFFF2F2F2),
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFFD93025),
    scrim = Color(0x66000000),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFEDEDED),
    inversePrimary = Color(0xFF8FA3FF),
)

private val DarkScheme = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlue.copy(alpha = 0.24f),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = BrandBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A2F4A),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = BrandBlue,
    background = Color(0xFF191919),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF191919),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFF9B9B9B),
    outline = Color(0xFF2C2C2C),
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFF28B82),
    onError = Color(0xFF3A0A06),
    errorContainer = Color(0xFF3A0A06),
    onErrorContainer = Color(0xFFF28B82),
    scrim = Color(0x99000000),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF2B3B99),
)

/** 字体：标题 17sp/600，正文 15sp/400，辅助 13sp/400，行高 1.55。 */
private val DshTypography = Typography(
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
)

private val DshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun DshTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = findActivity(view.context) ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = DshTypography,
        shapes = DshShapes,
        content = content,
    )
}

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
