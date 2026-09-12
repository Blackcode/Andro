package com.blackcode.cascoscan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * A deliberately sober palette: this app is read in daylight, on a phone, next to a concrete wall.
 * Status colour is reserved for statuses, so nothing in the chrome competes with a red marker.
 */
private val Ink = Color(0xFF1B2430)
private val Slate = Color(0xFF3D5A80)
private val SlateLight = Color(0xFFD6E2F0)
private val Amber = Color(0xFFB46A00)

private val LightScheme = lightColorScheme(
    primary = Slate,
    onPrimary = Color.White,
    primaryContainer = SlateLight,
    onPrimaryContainer = Ink,
    secondary = Amber,
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = Color(0xFF48525F),
    error = Color(0xFFD32F2F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9DB8D8),
    onPrimary = Color(0xFF101B27),
    primaryContainer = Color(0xFF26384C),
    onPrimaryContainer = Color(0xFFD6E2F0),
    secondary = Color(0xFFE0A85C),
    background = Color(0xFF11141A),
    onBackground = Color(0xFFE7EAF0),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE7EAF0),
    surfaceVariant = Color(0xFF272D37),
    onSurfaceVariant = Color(0xFFB6BECB),
    error = Color(0xFFEF6D6D),
)

@Composable
fun CascoScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
