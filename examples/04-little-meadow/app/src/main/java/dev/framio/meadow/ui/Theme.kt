package dev.framio.meadow.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.framio.meadow.R

/**
 * Little Meadow's palette: warm paper, muted pastels, nothing neon. Teaching colours are clear
 * enough to name but softened (no pure red/blue), so a toddler's screen stays calm.
 */
object Palette {
    val paper = Color(0xFFF6EFE4)
    val paperDeep = Color(0xFFEDE3D3)
    val cream = Color(0xFFFBF6EE)
    val ink = Color(0xFF3E3346)
    val inkSoft = Color(0xFF7A6E80)
    val line = Color(0x1F3E3346)

    val honey = Color(0xFFE7B05A)
    val terracotta = Color(0xFFDC8B62)
    val sky = Color(0xFF8FAFD3)
    val sage = Color(0xFF8DB48A)
    val lavender = Color(0xFF9D97D2)
    val rose = Color(0xFFE9A3A0)
    val mint = Color(0xFF9FD3C1)

    val night = Color(0xFF2D2E4F)
    val nightDeep = Color(0xFF1E1F38)
    val moon = Color(0xFFF3E7C9)

    // colours children learn to name (Color hunt, mixing, sorting)
    val red = Color(0xFFDE6A5E)
    val orange = Color(0xFFEE9B55)
    val yellow = Color(0xFFF1CD5E)
    val green = Color(0xFF7DBA70)
    val blue = Color(0xFF6E9FD6)
    val purple = Color(0xFFA487C9)
    val pink = Color(0xFFEFA6C0)
    val brown = Color(0xFFA9785A)
    val white = Color(0xFFF8F4EC)
    val black = Color(0xFF45404A)
    val grey = Color(0xFFA9A6AD)

    fun of(argb: Long) = Color(argb)
}

val Nunito = FontFamily(
    Font(R.font.nunito_600, FontWeight.SemiBold),
    Font(R.font.nunito_800, FontWeight.ExtraBold),
)

object Type {
    val label = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Palette.ink)
    val body = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Palette.ink)
    val small = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.inkSoft)
    val title = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Palette.ink)

    /** Big friendly numerals and letters with a soft clay shadow. */
    fun clay(size: TextUnit, color: Color = Palette.ink) = TextStyle(
        fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = size, color = color,
        shadow = Shadow(Color(0x40000000), Offset(0f, size.value * 0.06f), size.value * 0.08f),
    )
}

/** Everything draws with BasicText and an explicit [Type] style; this is the hook for anything global. */
@Composable
fun MeadowTheme(content: @Composable () -> Unit) = content()
