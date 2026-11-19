package debating.ground.app

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// 返回预览：手指一滑页面跟着缩小（或面板跟着往下沉），松手才返回，反悔就弹回
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class BackGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()
    private val model by lazy { AppModel(tmp.newFolder()) }

    private fun save(name: String) {
        val dir = File(System.getProperty("screenshots.dir")!!).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val dispatcher get() = compose.activity.onBackPressedDispatcher
    private fun event(p: Float) = BackEventCompat(10f, 400f, p, BackEventCompat.EDGE_LEFT)

    private fun swipe(to: Float) = compose.runOnIdle {
        dispatcher.dispatchOnBackStarted(event(0f))
        dispatcher.dispatchOnBackProgressed(event(to))
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    @Test
    fun pagesFollowTheFingerAndSheetsSink() {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides true) {
                SibianTheme(dark = false) { Box(Modifier.requiredSize(390.dp, 844.dp)) { AppScaffold(model) } }
            }
        }
        compose.mainClock.autoAdvance = false
        // 页面：设置页往回滑一半，页面缩小；松手回到学习页
        compose.runOnIdle { model.learning.go("settings") }
        settle()
        swipe(0.6f)
        compose.mainClock.advanceTimeBy(100)
        save("phone-back-page")
        compose.runOnIdle { dispatcher.onBackPressed() }
        settle()
        assertEquals("learn", model.state.value.ui.screen)
        // 面板：反悔时弹回、面板还开着；松手时关上
        compose.runOnIdle { model.learning.go("settings") }
        settle()
        compose.runOnIdle { model.learning.openSheet("points") }
        settle()
        swipe(0.5f)
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        settle()
        assertEquals("points", model.state.value.ui.sheet)
        swipe(0.9f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        settle()
        assertNull(model.state.value.ui.sheet)
        assertEquals("settings", model.state.value.ui.screen)
    }
}
