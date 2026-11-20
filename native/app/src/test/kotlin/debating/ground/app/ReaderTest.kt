package debating.ground.app

import debating.ground.app.ai.demoRun
import debating.ground.app.data.FileMaterials
import debating.ground.app.data.MapPoint
import debating.ground.app.data.Material
import debating.ground.app.data.MaterialMap
import debating.ground.app.data.MaterialSection
import debating.ground.app.data.ReadAction
import debating.ground.app.data.ReadStep
import debating.ground.app.data.SibianJson
import debating.ground.app.data.excerptFor
import debating.ground.app.data.outlineOf
import debating.ground.app.data.readActionOf
import debating.ground.app.data.readSections
import debating.ground.app.data.runReader
import debating.ground.app.data.runTool
import debating.ground.app.data.searchMaterial
import debating.ground.app.data.splitMaterial
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 读材料（#144、#154）：和网页版 src/lib/reader.ts、demoRead 生成的标准答案（reader-cases.json）逐字比对
class ReaderTest {
    private val fixture = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/reader-cases.json")!!.toURI()).readText()).jsonObject

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.ints(k: String) = getValue(k).jsonArray.map { it.jsonPrimitive.int }

    @Test
    fun splitsReadsSearchesAndExcerptsLikeTheWebApp() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(7, cases.size)
        for (c in cases) {
            val name = c.s("name")
            val sections = splitMaterial(c.s("text"))
            assertEquals(name, SibianJson.decodeFromJsonElement(ListSerializer(MaterialSection.serializer()), c.getValue("sections")), sections)
            assertEquals(name, c.getValue("outline"), outlineOf(sections))
            for (r in c.getValue("reads").jsonArray.map { it.jsonObject }) {
                assertEquals("$name 读 ${r["ns"]}", r.s("out"), readSections(sections, r.ints("ns"), r.getValue("max").jsonPrimitive.int))
            }
            for (q in c.getValue("searches").jsonArray.map { it.jsonObject }) {
                assertEquals("$name 搜 ${q.s("q")}", q.s("out"), searchMaterial(sections, q.s("q")))
            }
            for (e in c.getValue("excerpts").jsonArray.map { it.jsonObject }) {
                val point = SibianJson.decodeFromJsonElement(MapPoint.serializer(), e.getValue("point"))
                val got = excerptFor("id", name, sections, point)
                val want = e.getValue("out").jsonObject
                assertEquals("$name 节选 ${point.name}", listOf(want.s("title"), want.s("point"), want.s("excerpt")), listOf(got.title, got.point, got.excerpt))
            }
            for (t in c.getValue("tools").jsonArray.map { it.jsonObject }) {
                val want = SibianJson.decodeFromJsonElement(ReadStep.serializer(), t.getValue("out"))
                assertEquals(name, want, runTool(sections, readActionOf(t.getValue("a"))!!))
            }
        }
    }

    @Test
    fun demoReadingMatchesTheWebApp() {
        for (d in fixture.getValue("demo").jsonArray.map { it.jsonObject }) {
            val out = demoRun("read", d.getValue("payload").jsonObject, null)
            assertEquals(d.getValue("out"), out.result)
        }
    }

    @Test
    fun readerStopsAtTheMapOrTheStepLimit() = runBlocking {
        val sections = splitMaterial("摘要\n" + "稀缺。".repeat(100) + "\n\n1 方法\n" + "矩阵。".repeat(100))
        val seen = mutableListOf<Int>()
        val done = runReader("标题", sections, ask = { payload, steps ->
            seen += steps.size
            when (steps.size) {
                0 -> ReadAction.Read("先读", listOf(1))
                1 -> ReadAction.Search("搜", "矩阵")
                else -> ReadAction.Finish("好了", MaterialMap("", "总结", listOf(MapPoint("方法", "核心", listOf(2)))))
            }.also { if (steps.isEmpty()) assertNull(payload["last"]) }
        })!!
        assertEquals(listOf(0, 1, 2), seen)
        // 地图没写标题就用材料的标题
        assertEquals("标题", done.first.title)
        assertEquals(listOf("读第 1 节", "搜「矩阵」", "整理成地图"), done.second.map { it.action })
        // 一直不结束：走满 8 步就停，最后一步告诉模型必须结束
        var lastFlag: Any? = null
        val never = runReader("标题", sections, ask = { payload, _ ->
            lastFlag = payload["last"]
            ReadAction.Read("再读", listOf(1))
        })
        assertNull(never)
        assertEquals("true", lastFlag.toString())
        // 模型出错：停下
        assertNull(runReader("标题", sections, ask = { _, _ -> null }))
    }

    @Test
    fun materialsAreSavedAsFilesAndListedNewestFirst() = runBlocking {
        val dir = Files.createTempDirectory("materials").toFile()
        val store = FileMaterials(dir)
        store.save(Material("a1", "旧的", listOf(MaterialSection(1, "开头", "x")), created = 1))
        store.save(Material("b2", "新的", emptyList(), map = MaterialMap("t", "s", listOf(MapPoint("p"))), created = 2))
        assertEquals(listOf("新的" to 1, "旧的" to 0), store.list().map { it.title to it.points })
        assertEquals("x", store.get("a1")!!.sections.first().text)
        // id 里的路径字符不会让它写到目录外面
        assertNull(store.get("../a1"))
        store.delete("a1")
        assertEquals(listOf("b2"), store.list().map { it.id })
        File(dir, "broken.json").writeText("{")
        assertEquals(1, store.list().size)
        dir.deleteRecursively()
        Unit
    }
}
