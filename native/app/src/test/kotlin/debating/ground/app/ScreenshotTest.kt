package debating.ground.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// 没有模拟器时检查界面的办法：在 JVM 里把页面画出来、存成图片，人工看一遍（AGENTS.md：手机宽度、浅色和深色）。
// 同时确认页面能渲染、关键文字在。图片在 app/build/screenshots/，CI 会上传。
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    // 每个测试一份新的状态（存档写在临时目录里）
    private val model by lazy { debating.ground.app.AppModel(tmp.newFolder()) }

    private fun shoot(name: String, dark: Boolean, width: Int = 390, height: Int = 844) {
        compose.setContent {
            Frame(dark, width, height)
        }
        compose.onNodeWithText("今天想弄懂\n什么？").assertIsDisplayed()
        save(name)
    }

    // 截图时背景不漂：画面固定，测试也不会因为一直有动画而等不到空闲
    @Composable
    private fun Frame(dark: Boolean, width: Int, height: Int) {
        CompositionLocalProvider(LocalMotion provides false) {
            SibianTheme(dark = dark) { Box(Modifier.requiredSize(width.dp, height.dp)) { AppScaffold(model) } }
        }
    }

    // 等流程走完：AI 调用在后台线程，结果投递回主线程。Robolectric 下 waitUntil 不处理这些投递，
    // 所以每次先让真实时间过去一点，再让界面空闲一次
    private fun awaitState(timeoutMs: Long = 5_000, done: (debating.ground.app.flow.AppState) -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done(model.state.value)) {
            check(System.currentTimeMillis() < until) { "等了 ${timeoutMs}ms，状态还是 ${model.state.value.ui}" }
            Thread.sleep(30)
            compose.waitForIdle()
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val dir = File(System.getProperty("screenshots.dir")!!).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun phoneLight() = shoot("phone-light", dark = false)

    @Test fun phoneDark() = shoot("phone-dark", dark = true)

    @Config(qualifiers = "w320dp-h568dp-xxhdpi")
    @Test
    fun narrowestPhone() = shoot("phone-320", dark = false, width = 320, height = 568)

    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun tabletUsesTheRail() = shoot("tablet-light", dark = false, width = 1280, height = 800)

    // 横着拿的手机：左侧导航，内容铺满右边
    @Config(qualifiers = "w844dp-h390dp-xxhdpi")
    @Test
    fun landscapePhone() = shoot("phone-landscape", dark = false, width = 844, height = 390)

    @Test
    fun tabsSwitchPages() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("今天想弄懂\n什么？").assertIsDisplayed()
        compose.onNodeWithText("知识库").performClick()
        compose.onNodeWithText("讨论结束后的复盘会存成知识卡片，按间隔提醒你复习。").assertIsDisplayed()
        save("phone-library")
    }

    // 演示模式下准备一场讨论，停在准备页
    @Test
    fun preparePage() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("先看看题目和角色").performClick()
        awaitState { it.ui.screen == "prepare" }
        compose.onNodeWithText("要弄懂的要点").assertIsDisplayed()
        save("phone-prepare")
    }

    @Test
    fun preparePageDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        model.learning.setMode("roundtable")
        compose.onNodeWithText("先看看题目和角色").performClick()
        awaitState { it.ui.screen == "prepare" }
        compose.onNodeWithText("圆桌嘉宾").assertExists()
        save("phone-prepare-roundtable-dark")
    }

    // 演示模式下开始一场讨论，说几句话
    private fun startAndTalk(lines: List<String>) {
        compose.onNodeWithText("开始讨论").performClick()
        awaitState { it.session != null && it.ui.busy == null && it.ui.screen == "session" }
        for (line in lines) {
            val before = model.state.value.session!!.entries.size
            model.run { send(line) }
            awaitState { it.ui.busy == null && it.session!!.entries.size >= before + 2 }
        }
    }

    @Test
    fun discussPage() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        startAndTalk(listOf("机会成本就是做一件事放弃的另一件事最好的价值。", "比如我周末去打工，就放弃了在家复习的收获。"))
        compose.onNodeWithText("说说你的想法……").assertIsDisplayed()
        save("phone-discuss")
    }

    // 第三次发言后出现小结卡
    @Test
    fun discussReflectionDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        startAndTalk(listOf("机会成本是放弃的东西。", "是放弃的选项里最好的那个。", "不只是钱，时间也算。"))
        compose.onNodeWithText("停一下，用自己的话说说").assertExists()
        model.learning.setReflectionDraft(model.state.value.session!!.entries.last().id, "机会成本是选了一样以后，放弃的那些选项里最值钱的一个。")
        compose.waitForIdle()
        save("phone-discuss-reflect-dark")
    }

    // 圆桌：点一条发言，打开追问面板
    @Test
    fun discussQuickSheet() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        model.learning.setMode("roundtable")
        startAndTalk(listOf("我觉得机会成本只在花钱的时候才有。"))
        val last = model.state.value.session!!.entries.last { it is debating.ground.app.data.ChatMessage && it.human != true }
        model.learning.openSheet(last.id)
        compose.waitForIdle()
        compose.onNodeWithText("追问这句").assertIsDisplayed()
        save("phone-discuss-quick")
    }

    // 平板：讨论页铺满导航右边（讨论页没有导航）
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun discussTablet() {
        compose.setContent { Frame(dark = false, width = 1280, height = 800) }
        startAndTalk(listOf("机会成本就是做一件事放弃的另一件事最好的价值。"))
        save("tablet-discuss")
    }
}
