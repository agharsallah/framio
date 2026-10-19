package dev.framio.lab.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Laid out for the frame: 1280×800 at 160 dpi, so 1.dp == 1 px. Same palette as the launcher.

val Ink = Color(0xFFF1ECE4)
val InkDim = Color(0xFF9A968E)
val InkFaint = Color(0xFF55575D)
val Surface0 = Color(0xFF0E0F12)
val Surface1 = Color(0xFF16181D)
val Surface2 = Color(0xFF1E2127)
val Hairline = Color(0xFF262A31)

val Amber = Color(0xFFE9A23B)
val Teal = Color(0xFF5BC6B4)
val Coral = Color(0xFFE5735C)
val Sky = Color(0xFF6AA8E8)
val Violet = Color(0xFFA38BE8)
val Lime = Color(0xFFA8CF5A)
val Rose = Color(0xFFE07AA6)
val Sand = Color(0xFFD2B48C)

val Good = Color(0xFF6CC48A)
val Caution = Color(0xFFE9B949)
val Bad = Color(0xFFE5675C)
val Neutral = Color(0xFF7D8590)

// Named family on purpose: below API 28 Compose only reaches Roboto Light/Thin via "sans-serif-light" etc.
private val Base = TextStyle(fontFamily = FontFamily.SansSerif, color = Ink)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, color = Ink, fontSize = 15.sp, lineHeight = 20.sp)

private val LabTypography = Typography(
    headlineMedium = Base.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light),
    titleLarge = Base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = Base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Base.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = Base.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = Base.copy(fontSize = 14.sp, lineHeight = 19.sp, color = InkDim),
    labelLarge = Base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = Base.copy(fontSize = 14.sp, lineHeight = 18.sp, color = InkDim),
    labelSmall = Base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = InkDim, letterSpacing = 0.6.sp),
)

@Composable
fun LabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal,
            onPrimary = Surface0,
            secondary = Amber,
            onSecondary = Surface0,
            background = Surface0,
            onBackground = Ink,
            surface = Surface1,
            onSurface = Ink,
            surfaceVariant = Surface2,
            onSurfaceVariant = InkDim,
            outline = Hairline,
            error = Bad,
        ),
        typography = LabTypography,
        content = content,
    )
}
