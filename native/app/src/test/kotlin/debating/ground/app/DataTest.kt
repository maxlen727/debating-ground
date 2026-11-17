package debating.ground.app

import debating.ground.app.data.DAY
import debating.ground.app.data.INTERVALS
import debating.ground.app.data.MODE_NAMES
import debating.ground.app.data.STATUS_LABEL
import debating.ground.app.data.Storage
import debating.ground.app.data.dueLabel
import debating.ground.app.data.loadState
import debating.ground.app.data.schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataTest {
    @get:Rule val tmp = TemporaryFolder()

    // 和网页版 tests/flow.test.ts 里间隔复习的断言一致
    @Test
    fun spacedRepetitionStretchesWhenRememberedAndResetsWhenNot() {
        assertEquals(listOf(1, 3, 7, 14, 30, 60), INTERVALS)
        val now = 1_000_000L
        assertEquals(1, schedule(0, true, now).level)
        assertEquals(now + 3 * DAY, schedule(0, true, now).due)
        assertEquals(INTERVALS.size - 1, schedule(INTERVALS.size - 1, true, now).level)
        assertEquals(0, schedule(4, false, now).level)
        assertEquals(now + DAY, schedule(4, false, now).due)
        assertEquals("今天", dueLabel(now, now))
        assertEquals("明天", dueLabel(now + DAY, now))
        assertEquals("3 天后", dueLabel(now + 3 * DAY - 1, now))
    }

    @Test
    fun storageWritesAndReadsBack() {
        val s = Storage(tmp.root)
        assertNull(s.read())
        s.write("""{"library":[]}""")
        assertEquals("""{"library":[]}""", s.read())
        s.write("""{"library":[1]}""")
        assertEquals("""{"library":[1]}""", s.read())
        assertEquals(listOf("sibian-v3.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun storageRecoversFromAReplaceInterruptedHalfway() {
        // 上次替换时主文件已删、临时文件还没改名：读临时文件
        File(tmp.root, "sibian-v3.json.tmp").writeText("""{"screen":"learn"}""")
        assertEquals("""{"screen":"learn"}""", Storage(tmp.root).read())
    }

    @Test
    fun anUnreadableSaveStartsFresh() {
        val state = loadState("{这不是 JSON", null, 0)
        assertEquals("机会成本", state.config.concept)
        assertEquals(0, state.library.size)
        assertEquals(1, loadState(null, """{"library":[{"id":"old"}]}""", 0).library.size)
    }

    // 讨论方式的名字和网页版 shared/modes.ts 一致
    @Test
    fun modeNamesMatchTheWebApp() {
        val web = File(System.getProperty("web.modes")!!).readText()
        val names = Regex("""^\s+(\w+): \{\s*name: '([^']+)'""", RegexOption.MULTILINE).findAll(web).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(names, MODE_NAMES)
        val status = Regex("""(\w+): '([^']+)'""").findAll(web.substringAfter("STATUS_LABEL")).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(status, STATUS_LABEL)
    }
}
