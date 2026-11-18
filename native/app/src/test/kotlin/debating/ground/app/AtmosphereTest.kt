package debating.ground.app

import debating.ground.app.ui.DRIFT_SECONDS
import debating.ground.app.ui.DriftPath
import debating.ground.app.ui.OVERSCAN
import debating.ground.app.ui.driftAt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// 背景氛围：原生版和网页版用同一组图（scripts/atmosphere.py 生成），漂移的路线也一样
class AtmosphereTest {
    private val stylesFile = File(System.getProperty("web.styles")!!)
    private val css = stylesFile.readText()
    private val repo = stylesFile.parentFile!!.parentFile!!

    @Test
    fun imagesAreTheSameAsTheWebOnes() {
        for (theme in listOf("light", "dark")) {
            for (shape in listOf("portrait", "landscape")) {
                val web = File(repo, "src/assets/atmosphere/$theme-$shape.webp")
                val native = File(repo, "native/app/src/main/res/drawable-nodpi/atmosphere_${theme}_$shape.webp")
                assertArrayEquals("$theme-$shape：跑一次 python3 scripts/atmosphere.py", web.readBytes(), native.readBytes())
            }
        }
    }

    @Test
    fun overscanMatchesTheParameters() {
        val json = File(repo, "src/assets/atmosphere/atmosphere.json").readText()
        val v = Regex(""""overscan":\s*([0-9.]+)""").find(json)!!.groupValues[1].toFloat()
        assertEquals(v, OVERSCAN, 1e-6f)
        // 网页版的 inset 是同一个比例
        assertEquals("-${(v * 100).toInt()}%", Regex("""\.atmosphere::before \{[^}]*inset: ([^;]+);""").find(css)!!.groupValues[1])
    }

    @Test
    fun driftFollowsTheWebKeyframes() {
        val block = Regex("""@keyframes drift \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(css)!!.groupValues[1]
        val web = listOf(0f to 0f) +
            Regex("""translate\((-?[0-9.]+)%, (-?[0-9.]+)%\)""").findAll(block).map { it.groupValues[1].toFloat() / 100 to it.groupValues[2].toFloat() / 100 }
        assertEquals(web, DriftPath)
        assertEquals("${DRIFT_SECONDS}s", Regex("""animation: drift (\d+s)""").find(css)!!.groupValues[1])
    }

    @Test
    fun driftGoesOutAndBack() {
        assertEquals(0f to 0f, driftAt(0))
        val end = driftAt(DRIFT_SECONDS * 1000L)
        assertEquals(DriftPath.last().first, end.first, 1e-5f)
        assertEquals(DriftPath.last().second, end.second, 1e-5f)
        val back = driftAt(DRIFT_SECONDS * 2000L)
        assertEquals(0f, back.first, 1e-5f)
        assertEquals(0f, back.second, 1e-5f)
        // 走到第一段的一半
        val mid = driftAt(DRIFT_SECONDS * 1000L / 6)
        assertEquals(DriftPath[1].first / 2, mid.first, 1e-4f)
    }
}
