package debating.ground.app

import debating.ground.app.data.MergeFrom
import debating.ground.app.data.Note
import debating.ground.app.data.SelfCheck
import debating.ground.app.data.SessionReview
import debating.ground.app.data.SibianJson
import debating.ground.app.data.TermCard
import debating.ground.app.data.checkPoint
import debating.ground.app.data.emptyNote
import debating.ground.app.data.mergeReview
import debating.ground.app.data.noteMarkdown
import debating.ground.app.data.sameConcept
import debating.ground.app.data.tally
import debating.ground.app.data.weak
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.TimeZone

// 概念笔记的合并、自检和导出：同样的输入走同样的步骤，每一步都要和网页版 src/lib/notes.ts 的结果一样（notes-cases.json）
class NotesTest {
    private fun fixture(name: String) =
        SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI()).readText()).jsonObject

    private val cases = fixture("notes-cases.json")
    private val zone = TimeZone.getTimeZone(fixture("meta.json").getValue("timeZone").jsonPrimitive.content)

    private fun review(name: String) = SibianJson.decodeFromJsonElement(SessionReview.serializer(), cases.getValue("inputs").jsonObject.getValue(name))

    // 键的顺序无关；省略的可选字段和 null 算一样；数字统一写法
    private fun norm(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.filter { it.value !is JsonNull }.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${norm(it.value)}" }
        is JsonArray -> e.joinToString(",", "[", "]") { norm(it) }
        else -> e.jsonPrimitive.let { p -> if (p.isString) p.toString() else p.content.toDoubleOrNull()?.toString() ?: p.content }
    }

    @Test
    fun mergingCheckingAndExportingMatchTheWeb() {
        val now = cases.getValue("inputs").jsonObject.getValue("now").jsonPrimitive.long
        fun from(id: String, at: Long, mode: String = "socratic", context: String? = null, terms: List<TermCard>? = null) =
            MergeFrom(id, at, mode, "机会成本", "题目 $id", "api", "m", context, terms)
        val steps = mutableListOf<Pair<String, Note>>()
        var n = emptyNote("n1", "机会成本", now)
        n = mergeReview(n, review("cost"), from("s1", now + 1000, context = "周末打工", terms = listOf(TermCard("稀缺性", "有限", ""))))
        steps += "第一次讨论" to n
        n = mergeReview(n, review("second"), from("s2", now + 2000, "debate"))
        steps += "第二次讨论" to n
        n = mergeReview(n, review("second"), from("s2", now + 3000, "debate"))
        steps += "同一次讨论重新复盘" to n
        n = checkPoint(n, n.checks[0], false, now + 4000)
        steps += "自检没答出" to n
        n = checkPoint(n, n.checks.first { it.point == "区别于沉没成本" }, true, now + 5000)
        steps += "自检答出来了" to n
        n = checkPoint(n, SelfCheck("没挂要点", "x"), true, now + 6000)
        steps += "自检题没挂要点" to n
        n = mergeReview(n, review("many"), from("s3", now + 7000))
        steps += "超过上限" to n

        val web = cases.getValue("steps").jsonArray.map { it.jsonObject }
        assertEquals(web.map { it.getValue("label").jsonPrimitive.content }, steps.map { it.first })
        for ((w, step) in web.zip(steps)) {
            val (label, note) = step
            assertEquals("$label：笔记", norm(w.getValue("note")), norm(SibianJson.encodeToJsonElement(Note.serializer(), note)))
            assertEquals("$label：Markdown", w.getValue("markdown").jsonPrimitive.content, noteMarkdown(note, zone = zone))
            assertEquals("$label：还没弄清的地方", w.getValue("weak").jsonArray.map { it.jsonPrimitive.content }, note.weak())
            val t = w.getValue("tally").jsonObject
            val c = note.tally()
            assertEquals(
                "$label：统计",
                listOf("mastered", "partial", "missing").map {
                    t.getValue(it).jsonPrimitive.int
                },
                listOf(c.mastered, c.partial, c.missing),
            )
        }
    }

    @Test
    fun sameConceptMatchesTheWeb() {
        for (c in cases.getValue("same").jsonArray.map { it.jsonObject }) {
            val a = c.getValue("a").jsonPrimitive.content
            val b = c.getValue("b").jsonPrimitive.content
            assertEquals("「$a」和「$b」", c.getValue("same").jsonPrimitive.boolean, sameConcept(a, b))
        }
    }
}
