package com.jarvis.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Тёплая тёмная тема по макету (Jarvis Mobile App.pdf): мальва/пыльная роза —
 * действия пользователя (свои сообщения, кнопка отправки), персиковый крем —
 * ответы ассистента. Фон почти чёрный, но с тёплым оттенком, а не холодно-серый.
 *
 * Роли surfaceContainer* заданы явно: Material3 1.3 красит ими карточки,
 * диалоги и чипы — без них поверх тёплого фона вылезли бы стандартные
 * холодные серые плашки.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD29E9D),
    onPrimary = Color(0xFF2E1516),
    // Пузырь пользователя и кнопка «отправить».
    primaryContainer = Color(0xFFC79392),
    onPrimaryContainer = Color(0xFF2A1617),
    secondary = Color(0xFFE9CFBD),
    onSecondary = Color(0xFF2B211B),
    // Пузырь ассистента.
    secondaryContainer = Color(0xFFEDD6C5),
    onSecondaryContainer = Color(0xFF2B211B),
    tertiary = Color(0xFF9FCB9F),
    onTertiary = Color(0xFF0E2A12),
    background = Color(0xFF161312),
    onBackground = Color(0xFFEDE1DD),
    surface = Color(0xFF161312),
    onSurface = Color(0xFFEDE1DD),
    surfaceVariant = Color(0xFF231F1E),
    onSurfaceVariant = Color(0xFFB8ACA8),
    surfaceContainerLowest = Color(0xFF110E0D),
    surfaceContainerLow = Color(0xFF1C1817),
    surfaceContainer = Color(0xFF211D1C),
    surfaceContainerHigh = Color(0xFF2A2524),
    surfaceContainerHighest = Color(0xFF342E2C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1B17),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3D3634),
    outlineVariant = Color(0xFF2C2624),
)

/** Светлый вариант той же палитры — для тех, у кого система в светлой теме. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF8E4E50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6B8B7),
    onPrimaryContainer = Color(0xFF2E1415),
    secondary = Color(0xFF7A5A45),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4DFD0),
    onSecondaryContainer = Color(0xFF2B211B),
    tertiary = Color(0xFF2E7D32),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF7F5),
    onBackground = Color(0xFF221A18),
    surface = Color(0xFFFBF7F5),
    onSurface = Color(0xFF221A18),
    surfaceVariant = Color(0xFFF1EAE7),
    onSurfaceVariant = Color(0xFF5C504C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F0ED),
    surfaceContainer = Color(0xFFF1EAE7),
    surfaceContainerHigh = Color(0xFFEBE3E0),
    surfaceContainerHighest = Color(0xFFE5DCD8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD3C5C0),
    outlineVariant = Color(0xFFE5DAD6),
)

/** Следует системной настройке; основной (макетный) вариант — тёмный. */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    // Значки строки состояния и навигации — тёмные на светлой теме и светлые на
    // тёмной: иначе в светлой теме часы и батарея были белыми на белом.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * «В сети» — зелёная точка статуса. Отдельно от primary: в новой палитре
 * primary розовый и не читается как «всё хорошо».
 */
@Composable
fun onlineColor(): Color = MaterialTheme.colorScheme.tertiary
