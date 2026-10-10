package com.knobnext.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightFallback = lightColorScheme(
    primary = Color(0xFF4A55A8),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondaryContainer = Color(0xFFE1E0F9),
    tertiaryContainer = Color(0xFFFFD8EC),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    primaryContainer = Color(0xFF313C8E),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondaryContainer = Color(0xFF444559),
    tertiaryContainer = Color(0xFF5F3A51),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KnobTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
