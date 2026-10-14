package com.kilombino.pyblockwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's colours: the BTC accent and the panels and text tinted with it. Purple by default,
 * or blue, chosen in settings; read once when the screen is built ([Palette.load]).
 */
object Palette {
    class Set(val accent: Color, val line: Color, val panel: Color, val panelSoft: Color,
              val text: Color, val textSoft: Color, val textFaint: Color)
    val PURPLE = Set(Color(0xFFB96BFF), Color(0x2EB96BFF), Color(0xFF0A0510), Color(0xFF0D0716),
        Color(0xFFDCD0EC), Color(0xFFA99CC4), Color(0xFF6C5C86))
    val BLUE = Set(Color(0xFF278BF5), Color(0x2E278BF5), Color(0xFF050A12), Color(0xFF08101C),
        Color(0xFFD0DCEC), Color(0xFF9CB0C8), Color(0xFF5C7090))
    @Volatile var current: Set = PURPLE
    val isBlue: Boolean get() = current === BLUE

    fun load(ctx: android.content.Context) {
        current = if (ctx.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE)
                .getString("palette", "purple") == "blue") BLUE else PURPLE
    }

    fun choose(ctx: android.content.Context, blue: Boolean) {
        ctx.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE).edit()
            .putString("palette", if (blue) "blue" else "purple").apply()
        current = if (blue) BLUE else PURPLE
    }
}

val Ink = Color(0xFF000000)
val PanelBg: Color get() = Palette.current.panel
val PanelSoft: Color get() = Palette.current.panelSoft
val Line: Color get() = Palette.current.line
val TextMain: Color get() = Palette.current.text
val TextSoft: Color get() = Palette.current.textSoft
val TextFaint: Color get() = Palette.current.textFaint
/** The BTC accent (named for the original colour; blue when chosen in settings). */
val Purple: Color get() = Palette.current.accent
val Orange = Color(0xFFF7931A)
val Good = Color(0xFF34D399)
/** Lightning (the LN tab): blue next to purple, gold next to blue so the two never look alike. */
val LnGold: Color get() = if (Palette.isBlue) Color(0xFFF5B83D) else Color(0xFF4DA3FF)
val Warn = Color(0xFFFFC53D)
val Bad = Color(0xFFFF5D5D)

/** Monospace throughout: this app shows hashes, paths and addresses. */
private val mono = FontFamily.Monospace

private val typo = Typography(
    displayLarge = TextStyle(fontFamily = mono, fontSize = 44.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = mono, fontSize = 18.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = mono, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.5.sp),
)

@Composable
fun PyBlockWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple, background = Ink, surface = PanelBg,
            onPrimary = Ink, onBackground = TextMain, onSurface = TextMain,
            error = Bad,
        ),
        typography = typo,
        content = content,
    )
}
