package debating.ground.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(dir: File) = AppModel(dir, CoroutineScope(Dispatchers.Unconfined))

    // 打字时状态一个字一个字地变：存档只写最新的一份，重开 App 读到的是最后打的字
    @Test fun savesTheLatestStateAndReadsItBack() {
        val dir = tmp.newFolder()
        val a = model(dir)
        val text = "边际效用递减"
        for (i in 1..text.length) a.learning.setConcept(text.take(i))
        a.learning.setMode("roundtable")
        val until = System.currentTimeMillis() + 5_000
        var b = model(dir)
        while (b.state.value.config.mode != "roundtable" && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            b = model(dir)
        }
        assertEquals(text, b.state.value.config.concept)
        assertEquals("roundtable", b.state.value.config.mode)
    }
}
