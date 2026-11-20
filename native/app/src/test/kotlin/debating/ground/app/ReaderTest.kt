package debating.ground.app

import debating.ground.app.ai.SAMPLE_MATERIAL_TEXT
import debating.ground.app.ai.SAMPLE_MATERIAL_TITLE
import debating.ground.app.data.FileMaterials
import debating.ground.app.data.Material
import debating.ground.app.data.MaterialSection
import debating.ground.app.data.SibianJson
import debating.ground.app.data.contextQuery
import debating.ground.app.data.materialContext
import debating.ground.app.data.outlineText
import debating.ground.app.data.readSections
import debating.ground.app.data.splitMaterial
import debating.ground.app.data.startContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

// 读材料（#144、#154、#166）：和网页版 src/lib/reader.ts 生成的标准答案（reader-cases.json）逐字比对：
// 切节、读几节、目录，以及讨论时按话题挑出来带给 AI 的材料
class ReaderTest {
    private val fixture = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/reader-cases.json")!!.toURI()).readText()).jsonObject

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.ints(k: String) = getValue(k).jsonArray.map { it.jsonPrimitive.int }

    @Test
    fun splitsReadsAndPicksTheRelevantSectionsLikeTheWebApp() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(8, cases.size)
        for (c in cases) {
            val name = c.s("name")
            val sections = splitMaterial(c.s("text"))
            assertEquals(name, SibianJson.decodeFromJsonElement(ListSerializer(MaterialSection.serializer()), c.getValue("sections")), sections)
            assertEquals(name, c.s("outlineText"), outlineText(sections))
            for (r in c.getValue("reads").jsonArray.map { it.jsonObject }) {
                assertEquals("$name 读 ${r["ns"]}", r.s("out"), readSections(sections, r.ints("ns"), r.getValue("max").jsonPrimitive.int))
            }
            for (q in c.getValue("contexts").jsonArray.map { it.jsonObject }) {
                assertEquals("$name 按「${q.s("q")}」挑", q.s("out"), materialContext(sections, q.s("q")))
            }
            assertEquals(name, c.s("start"), startContext(sections))
        }
    }

    @Test
    fun theQueryIsTheTopicTheBranchAndTheLastTwoMessages() {
        val snap = buildJsonObject {
            put("topic", "题目")
            put("branch", buildJsonObject { put("topic", "分支") })
            put(
                "messages",
                buildJsonArray {
                    for (t in listOf("很早的话", "倒数第二", "最后一句")) add(buildJsonObject { put("text", t) })
                },
            )
        }
        assertEquals(fixture.s("query"), contextQuery(snap))
        assertEquals("题目\n", contextQuery(buildJsonObject { put("topic", JsonPrimitive("题目")) }))
    }

    @Test
    fun sampleMaterialMatchesTheWebApp() {
        val sample = fixture.getValue("sample").jsonObject
        assertEquals(sample.s("title"), SAMPLE_MATERIAL_TITLE)
        assertEquals(sample.s("text"), SAMPLE_MATERIAL_TEXT)
    }

    @Test
    fun materialsAreSavedAsFilesAndListedNewestFirst() = runBlocking {
        val dir = Files.createTempDirectory("materials").toFile()
        val store = FileMaterials(dir)
        store.save(Material("a1", "旧的", listOf(MaterialSection(1, "开头", "x")), created = 1))
        store.save(Material("b2", "新的", listOf(MaterialSection(1, "a", "x"), MaterialSection(2, "b", "y")), created = 2, source = "https://example.com/a"))
        assertEquals(listOf("新的" to 2, "旧的" to 1), store.list().map { it.title to it.sections })
        assertEquals("https://example.com/a", store.get("b2")!!.source)
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
