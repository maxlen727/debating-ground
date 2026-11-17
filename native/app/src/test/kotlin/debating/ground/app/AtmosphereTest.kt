package debating.ground.app

import debating.ground.app.ui.Glows
import debating.ground.app.ui.driftPhase
import debating.ground.app.ui.theme.LightTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 背景氛围：原生版和网页版（styles.css 的“背景氛围”一节）是同一套参数，改一边要改另一边
class AtmosphereTest {
    private val css = File(System.getProperty("web.styles")!!).readText()

    // .atmosphere i:nth-child(n) { --x: 78%; --y: 12%; --r: .75; --c: var(--accent-soft); --a: .85; --t: 97s; --dx: -4vmax; --dy: 3vmax; }
    private val web = Regex("""\.atmosphere i:nth-child\((\d)\) \{([^}]*)\}""").findAll(css).associate { m ->
        m.groupValues[1].toInt() to Regex("""--([a-z]+):\s*([^;]+);""").findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun num(v: String) = v.removeSuffix("%").removeSuffix("s").removeSuffix("vmax").toFloat()

    @Test
    fun nativeGlowsMatchTheWebStyles() {
        assertEquals("色块数量", web.size, Glows.size)
        Glows.forEachIndexed { i, g ->
            val w = web[i + 1] ?: error("styles.css 里没有第 ${i + 1} 团色块")
            val label = "第 ${i + 1} 团色块"
            assertEquals("$label 的 x", num(w.getValue("x")) / 100, g.x, 1e-4f)
            assertEquals("$label 的 y", num(w.getValue("y")) / 100, g.y, 1e-4f)
            assertEquals("$label 的半径", num(w.getValue("r")), g.r, 1e-4f)
            assertEquals("$label 的浓度", num(w.getValue("a")), g.alpha, 1e-4f)
            assertEquals("$label 的周期", num(w.getValue("t")).toInt(), g.periodSeconds)
            assertEquals("$label 的横向漂移", num(w.getValue("dx")) / 100, g.dx, 1e-4f)
            assertEquals("$label 的纵向漂移", num(w.getValue("dy")) / 100, g.dy, 1e-4f)
            // 颜色：网页版写的是 var(--令牌名)，原生版取同一个令牌
            val token = w.getValue("c").removePrefix("var(--").removeSuffix(")")
            assertEquals("$label 的颜色", LightTokens.byCssName()[token], g.color(LightTokens))
        }
    }

    @Test
    fun driftGoesThereAndBackSlowly() {
        assertEquals(0f, driftPhase(0, 97), 1e-6f)
        assertEquals(1f, driftPhase(97_000, 97), 1e-4f) // 一个周期走到另一头
        assertEquals(0f, driftPhase(194_000, 97), 1e-4f) // 再一个周期回来
        assertEquals(0.5f, driftPhase(48_500, 97), 1e-4f)
        // 两端放慢：刚出发时挪得比中间慢
        assertTrue(driftPhase(5_000, 97) < 5_000f / 97_000)
    }
}
