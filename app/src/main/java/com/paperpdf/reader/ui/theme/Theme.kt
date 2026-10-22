package com.paperpdf.reader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.paperpdf.reader.data.ColorMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color(0xFFFFFDF8),
    primaryContainer = Color(0xFFD7E8DF),
    onPrimaryContainer = Color(0xFF12362D),
    secondary = Color(0xFFB56238),
    onSecondary = Color(0xFFFFFDF8),
    secondaryContainer = Color(0xFFF6DED0),
    onSecondaryContainer = Color(0xFF49200D),
    background = Color(0xFFF6F0E4),
    onBackground = Color(0xFF25251F),
    surface = Color(0xFFFFFCF5),
    onSurface = Color(0xFF25251F),
    surfaceVariant = Color(0xFFECE4D6),
    onSurfaceVariant = Color(0xFF5D5A50),
    outline = Color(0xFF8D887A),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCFBC),
    onPrimary = Color(0xFF0D2E25),
    primaryContainer = Color(0xFF26463C),
    onPrimaryContainer = Color(0xFFC7EBDD),
    secondary = Color(0xFFE8A67A),
    onSecondary = Color(0xFF4A2410),
    secondaryContainer = Color(0xFF5F3A23),
    onSecondaryContainer = Color(0xFFF8DCC8),
    background = Color(0xFF111411),
    onBackground = Color(0xFFE7E4DB),
    surface = Color(0xFF1A1E1A),
    onSurface = Color(0xFFE7E4DB),
    surfaceVariant = Color(0xFF2A302B),
    onSurfaceVariant = Color(0xFFB9B8AC),
    outline = Color(0xFF85887E),
    error = Color(0xFFFFB4AB),
)

private val PaperTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun PaperTheme(
    colorMode: ColorMode = ColorMode.DAY,
    content: @Composable () -> Unit,
) {
    val dark = colorMode == ColorMode.NIGHT ||
        (colorMode == ColorMode.SEPIA && isSystemInDarkTheme())
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = PaperTypography, content = content)
}
