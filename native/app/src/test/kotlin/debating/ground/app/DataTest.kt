package debating.ground.app

import debating.ground.app.data.MODE_NAMES
import debating.ground.app.data.STATUS_LABEL
import debating.ground.app.data.Storage
import debating.ground.app.data.loadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun storageWritesAndReadsBack() {
        val s = Storage(tmp.root)
        assertNull(s.read())
        s.write("""{"library":[]}""")
        assertEquals("""{"library":[]}""", s.read())
        s.write("""{"library":[1]}""")
        assertEquals("""{"library":[1]}""", s.read())
        assertEquals(listOf("sibian-v4.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun storageRecoversFromAReplaceInterruptedHalfway() {
        // 上次替换时主文件已删、临时文件还没改名：读临时文件
        File(tmp.root, "sibian-v4.json.tmp").writeText("""{"screen":"learn"}""")
        assertEquals("""{"screen":"learn"}""", Storage(tmp.root).read())
    }

    @Test
    fun anUnreadableSaveStartsFresh() {
        val state = loadState("{这不是 JSON", null, null, 0)
        assertEquals("机会成本", state.config.concept)
        assertEquals(0, state.library.size)
        assertEquals(1, loadState(null, null, """{"library":[{"id":"old"}]}""", 0).library.size)
    }

    // 概念笔记以前的存档（sibian-v3.json）：没有 v4 时读它，同一个概念的几张卡片合并成一篇笔记；原来的文件不动
    @Test
    fun anOldCardsSaveIsMergedIntoNotes() {
        val s = Storage(tmp.root)
        val v3 = """{"library":[
            {"id":"a","sessionId":"s1","concept":"机会成本","created":1,"oneLine":"第一次","points":[{"label":"放弃的最佳选择","status":"partial","note":""}],
             "quiz":[{"q":"题","options":["甲","乙"],"answer":1,"why":"因为"}],"due":5,"level":2,"reviews":[]},
            {"id":"b","sessionId":"s2","concept":"机会成本 ","created":2,"oneLine":"第二次","points":[{"label":"放弃的最佳选择","status":"mastered","note":"清楚了"}]}
        ]}"""
        File(tmp.root, "sibian-v3.json").writeText(v3)
        assertNull(s.read())
        val state = loadState(s.read(), s.read(debating.ground.app.data.CARDS_STORE_KEY), null, 0)
        val note = state.library.single()
        assertEquals("a", note.id)
        assertEquals(listOf("s1", "s2"), note.sources.map { it.sessionId })
        assertEquals("mastered", note.points.single().status)
        assertEquals("乙。因为", note.checks.single().answer)
        assertEquals(v3, File(tmp.root, "sibian-v3.json").readText())
    }

    // 讨论方式的名字和网页版 shared/modes.ts 一致
    @Test
    fun modeNamesMatchTheWebApp() {
        val web = File(System.getProperty("web.modes")!!).readText()
        val names = Regex("""^\s+(\w+): \{\s*name: '([^']+)'""", RegexOption.MULTILINE).findAll(web).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(names, MODE_NAMES)
        // 说明文字和学习页的顺序也一致
        val info = Regex("""(\w+): \{\s*name: '([^']+)',\s*short: '([^']+)',\s*desc:\s*'([^']+)'""").findAll(web)
            .associate { it.groupValues[1] to debating.ground.app.data.ModeInfo(it.groupValues[2], it.groupValues[3], it.groupValues[4]) }
        assertEquals(info, debating.ground.app.data.MODES)
        val order = Regex("""MODE_ORDER: ModeId\[\] = \[([^\]]+)\]""").find(web)!!.groupValues[1].split(",").map { it.trim().trim('\'') }
        assertEquals(order, debating.ground.app.data.MODE_ORDER)
        val status = Regex("""(\w+): '([^']+)'""").findAll(web.substringAfter("STATUS_LABEL")).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(status, STATUS_LABEL)
        val kinds = Regex("""(\w+): '([^']+)'""").findAll(web.substringAfter("STAGE_KINDS").substringBefore("};")).associate {
            it.groupValues[1] to
                it.groupValues[2]
        }
        assertEquals(kinds, debating.ground.app.data.STAGE_KINDS)
        assertEquals(kinds.keys.toList(), debating.ground.app.data.STAGE_KINDS.keys.toList())
    }
}
