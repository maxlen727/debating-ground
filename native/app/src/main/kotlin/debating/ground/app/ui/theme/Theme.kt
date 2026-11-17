package debating.ground.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// 字体分工（AGENTS.md）：衬线只写“知识本身”——概念、讨论题、要点、自己的小结、复盘结论、术语、检验题；
// 对话、按钮、标签、提示用无衬线。只用系统字体（App 要离线可用）：安卓上分别落到 Noto Serif / Sans CJK。
val Serif = FontFamily.Serif
val Sans = FontFamily.SansSerif

// 字号阶梯，对应 styles.css 的 --t-*（px 换成 sp，跟随系统字体大小）
object TextSize {
    val xs = 12.5.sp
    val sm = 14.sp
    val md = 16.sp
    val lg = 18.sp
    val xl = 22.sp
    val x2l = 28.sp
    val x3l = 34.sp
}

// 行距：无衬线 1.65，衬线正文 1.85（--lh-sans、--lh-serif）
const val LINE_HEIGHT_SANS = 1.65f
const val LINE_HEIGHT_SERIF = 1.85f

object TextStyles {
    val body = TextStyle(fontFamily = Sans, fontSize = TextSize.md, lineHeight = LINE_HEIGHT_SANS.em)
    val small = TextStyle(fontFamily = Sans, fontSize = TextSize.sm, lineHeight = LINE_HEIGHT_SANS.em)
    val label = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 1.3.em)

    // 首页的大标题（.display）
    val display = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = TextSize.x3l, lineHeight = 1.22.em, letterSpacing = 0.03.em)

    // 知识本身的正文（讨论题、小结等）
    val knowledge = TextStyle(fontFamily = Serif, fontSize = TextSize.md, lineHeight = LINE_HEIGHT_SERIF.em)
    val title = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = TextSize.lg, lineHeight = 1.35.em)
}

val LocalTokens = staticCompositionLocalOf { LightTokens }

@Composable
fun SibianTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    // Material 组件的默认配色也接到令牌上，免得某个组件冒出 Material 默认的紫色
    val scheme = if (dark) {
        darkColorScheme(
            primary = t.brand.color(), onPrimary = t.brandInk.color(), secondary = t.accent.color(), onSecondary = t.brandInk.color(),
            background = t.surface.color(), onBackground = t.ink.color(), surface = t.surface.color(), onSurface = t.ink.color(),
            surfaceVariant = t.bg.color(), onSurfaceVariant = t.muted.color(), surfaceContainer = t.raised.color(),
            outline = t.line.color(), outlineVariant = t.line.color(), error = t.bad.color(), errorContainer = t.badSoft.color(),
            secondaryContainer = t.lit.color(), onSecondaryContainer = t.litInk.color(),
        )
    } else {
        lightColorScheme(
            primary = t.brand.color(), onPrimary = t.brandInk.color(), secondary = t.accent.color(), onSecondary = t.brandInk.color(),
            background = t.surface.color(), onBackground = t.ink.color(), surface = t.surface.color(), onSurface = t.ink.color(),
            surfaceVariant = t.bg.color(), onSurfaceVariant = t.muted.color(), surfaceContainer = t.raised.color(),
            outline = t.line.color(), outlineVariant = t.line.color(), error = t.bad.color(), errorContainer = t.badSoft.color(),
            secondaryContainer = t.lit.color(), onSecondaryContainer = t.litInk.color(),
        )
    }
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
