package debating.ground.app

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import debating.ground.app.data.ChatMessage
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// 交互细节：点空白处收起键盘（输入框失焦），追问面板里可以复制这句话
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class InteractionTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()
    private val model by lazy { AppModel(tmp.newFolder()) }

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalMotion provides false) {
            SibianTheme(dark = false) { Box(Modifier.requiredSize(390.dp, 844.dp)) { AppScaffold(model) } }
        }
    }

    @Test
    fun tappingOutsideAnInputDismissesTheKeyboard() {
        show()
        val input = compose.onNodeWithContentDescription("想弄懂的概念")
        input.performClick()
        input.assertIsFocused()
        compose.onNodeWithText("今天想弄懂\n什么？").performClick()
        input.assertIsNotFocused()
    }

    @Test
    fun aSentenceCanBeCopiedFromTheQuickSheet() {
        show()
        compose.onNodeWithText("开始讨论").performClick()
        val until = System.currentTimeMillis() + 5000
        while (model.state.value.session == null || model.state.value.ui.busy != null) {
            check(System.currentTimeMillis() < until)
            Thread.sleep(30)
            compose.waitForIdle()
        }
        val m = model.state.value.session!!.entries.last { it is ChatMessage && it.human != true } as ChatMessage
        compose.runOnIdle { model.learning.openSheet(m.id) }
        compose.onNodeWithText("复制这句").performClick()
        val clip = ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(m.text, clip.getItemAt(0).text.toString())
        assertEquals("已复制这句话。", model.state.value.ui.toast)
    }
}
