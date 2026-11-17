package debating.ground.app.ui.theme

import androidx.compose.ui.graphics.Color

// 颜色令牌：和网页版 src/styles.css 的 :root（浅色）、prefers-color-scheme: dark（深色）一一对应。
// 界面里只用这些令牌，不写死颜色（AGENTS.md 界面规范）。TokensTest 会逐项和 styles.css 比对，改一边漏了另一边会失败。
data class Tokens(
    val page: Long, val bg: Long, val surface: Long, val raised: Long,
    val ink: Long, val muted: Long, val line: Long,
    val brand: Long, val brandInk: Long, val accent: Long, val accentSoft: Long,
    val lit: Long, val litInk: Long, val light: Long,
    val aBg: Long, val aFg: Long, val bBg: Long, val bFg: Long,
    val cBg: Long, val cFg: Long, val pBg: Long, val pFg: Long,
    val good: Long, val goodSoft: Long, val warn: Long, val warnSoft: Long, val bad: Long, val badSoft: Long,
) {
    // CSS 变量名 → 颜色，供测试比对，也方便按名字查
    fun byCssName(): Map<String, Long> = mapOf(
        "page" to page, "bg" to bg, "surface" to surface, "raised" to raised,
        "ink" to ink, "muted" to muted, "line" to line,
        "brand" to brand, "brand-ink" to brandInk, "accent" to accent, "accent-soft" to accentSoft,
        "lit" to lit, "lit-ink" to litInk, "light" to light,
        "a-bg" to aBg, "a-fg" to aFg, "b-bg" to bBg, "b-fg" to bFg,
        "c-bg" to cBg, "c-fg" to cFg, "p-bg" to pBg, "p-fg" to pFg,
        "good" to good, "good-soft" to goodSoft, "warn" to warn, "warn-soft" to warnSoft, "bad" to bad, "bad-soft" to badSoft,
    )
}

val LightTokens = Tokens(
    page = 0xFFE8E7DE, bg = 0xFFF4F3EC, surface = 0xFFFBFAF5, raised = 0xFFFFFFFF,
    ink = 0xFF18241F, muted = 0xFF6B756F, line = 0xFFE0E0D4,
    brand = 0xFF1F3B36, brandInk = 0xFFF2F5EC, accent = 0xFFB8603A, accentSoft = 0xFFF6E7DC,
    lit = 0xFFDCE9D2, litInk = 0xFF2C5A3D, light = 0xFF3FB27A,
    aBg = 0xFFDDE7D8, aFg = 0xFF3D5E45, bBg = 0xFFF1E0D2, bFg = 0xFF96583A,
    cBg = 0xFFDCE3EE, cFg = 0xFF455D7D, pBg = 0xFFE8E2EE, pFg = 0xFF5B4876,
    good = 0xFF2D6E45, goodSoft = 0xFFE0EEDF, warn = 0xFF8A5F12, warnSoft = 0xFFF6EBD3, bad = 0xFFA2432D, badSoft = 0xFFF7E0D8,
)

val DarkTokens = Tokens(
    page = 0xFF0B0F0E, bg = 0xFF111715, surface = 0xFF151B19, raised = 0xFF1C2421,
    ink = 0xFFE4EBE7, muted = 0xFF98A49E, line = 0xFF2A3430,
    brand = 0xFF8FCBB2, brandInk = 0xFF0D1714, accent = 0xFFE2996F, accentSoft = 0xFF3A2A20,
    lit = 0xFF21382D, litInk = 0xFFA3D9B9, light = 0xFF7FE3B0,
    aBg = 0xFF233328, aFg = 0xFFA7CDB0, bBg = 0xFF3A2A21, bFg = 0xFFE7B293,
    cBg = 0xFF232D3B, cFg = 0xFFA9BEDB, pBg = 0xFF2C2636, pFg = 0xFFC9B8E2,
    good = 0xFF8AD2A3, goodSoft = 0xFF1C3326, warn = 0xFFE3BE6E, warnSoft = 0xFF382E18, bad = 0xFFEE9A86, badSoft = 0xFF3D231C,
)

fun Long.color() = Color(this)
