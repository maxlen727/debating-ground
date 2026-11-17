package debating.ground.app

import debating.ground.app.ui.theme.DarkTokens
import debating.ground.app.ui.theme.LightTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 原生版的颜色令牌必须和网页版 src/styles.css 一致：两边是同一套设计，改一边要改另一边
class TokensTest {
    private val css = File(System.getProperty("web.styles")!!).readText()

    // 取出某一段里的 --名字: #RRGGBB
    private fun colors(block: String): Map<String, Long> =
        Regex("""--([a-z-]+):\s*#([0-9A-Fa-f]{6})\b""").findAll(block)
            .associate { it.groupValues[1] to (0xFF000000 or it.groupValues[2].toLong(16)) }

    private val light = colors(css.substringAfter(":root {").substringBefore("}"))
    private val dark = colors(css.substringAfter("@media (prefers-color-scheme: dark)").substringAfter(":root {").substringBefore("}"))

    @Test fun lightTokensMatchTheWebStyles() = compare(light, LightTokens.byCssName(), "浅色")
    @Test fun darkTokensMatchTheWebStyles() = compare(dark, DarkTokens.byCssName(), "深色")

    @Test fun everyWebColorTokenExistsNatively() {
        // 网页版新增了颜色令牌，原生版也要有
        val missing = light.keys - LightTokens.byCssName().keys
        assertTrue("原生版缺少这些颜色令牌：$missing", missing.isEmpty())
    }

    private fun compare(web: Map<String, Long>, native: Map<String, Long>, label: String) {
        assertTrue("没有从 styles.css 读到$label 令牌", web.size >= 20)
        for ((name, value) in native) {
            val expected = web[name] ?: error("styles.css 的$label 部分没有 --$name")
            assertEquals("$label 令牌 --$name", "%08X".format(expected), "%08X".format(value))
        }
    }
}
