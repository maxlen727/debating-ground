package debating.ground.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// 触感反馈：分段切换轻点一下，讨论里新点亮要点有确认感（打开时已经亮着的不算）
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class HapticsTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()
    private val model by lazy { AppModel(tmp.newFolder()) }
    private val felt = mutableListOf<HapticFeedbackType>()

    private fun awaitState(done: (debating.ground.app.flow.AppState) -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!done(model.state.value)) {
            check(System.currentTimeMillis() < until)
            Thread.sleep(30)
            compose.waitForIdle()
        }
        compose.waitForIdle()
    }

    @Test
    fun segmentsTickAndLitPointsConfirm() {
        compose.setContent {
            CompositionLocalProvider(
                LocalMotion provides false,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        felt += hapticFeedbackType
                    }
                },
            ) {
                SibianTheme(dark = false) { Box(Modifier.requiredSize(390.dp, 844.dp)) { AppScaffold(model) } }
            }
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("我的后端").performClick()
        assertEquals(listOf(HapticFeedbackType.SegmentTick), felt)
        // 点已经选中的那一项：不震
        compose.onNodeWithText("我的后端").performClick()
        assertEquals(1, felt.size)
        compose.onNodeWithText("演示模式").performClick()
        felt.clear()
        // 演示模式讨论“机会成本”：说到点子上，点亮要点
        compose.onNodeWithText("学习").performClick()
        compose.onNodeWithText("开始讨论").performClick()
        awaitState { it.session != null && it.ui.busy == null && it.ui.screen == "session" }
        assertTrue("刚打开讨论不震", felt.none { it == HapticFeedbackType.Confirm })
        for (line in listOf("机会成本就是做一件事放弃的另一件事最好的价值。", "比如我周末去打工，就放弃了在家复习的收获。")) {
            val before = model.state.value.session!!.entries.size
            model.run { send(line) }
            awaitState { it.ui.busy == null && it.session!!.entries.size >= before + 2 }
        }
        assertTrue("应该点亮了要点", model.state.value.session!!.covered.isNotEmpty())
        assertTrue("点亮要点时有确认感", felt.contains(HapticFeedbackType.Confirm))
    }
}
