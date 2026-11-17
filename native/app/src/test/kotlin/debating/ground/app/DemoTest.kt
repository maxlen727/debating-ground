package debating.ground.app

import debating.ground.app.ai.DemoException
import debating.ground.app.ai.demoRun
import debating.ground.app.data.Session
import debating.ground.app.data.SibianJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 演示模式和网页版逐条比对：scripts/native-fixtures.ts 在几种典型的讨论状态下，每种用途各调用一次网页版的 demoRun，
// 记下调用前的讨论、payload、结果和调用后的 demoStep。这里用同样的输入，四样都要一致。
class DemoTest {
    private val cases = SibianJson.parseToJsonElement(
        File(javaClass.classLoader!!.getResource("fixtures/demo-cases.json")!!.toURI()).readText(),
    ).jsonArray

    @Test
    fun everyPurposeIsCovered() {
        val purposes = cases.map { it.jsonObject.getValue("purpose").jsonPrimitive.content }.toSet()
        assertEquals(setOf("prepare", "discuss", "debate", "point", "term", "hint", "explain", "reflect", "review"), purposes)
        assertTrue(cases.size >= 100)
    }

    @Test
    fun demoMatchesTheWebAppCallByCall() {
        for ((i, c) in cases.withIndex()) {
            val o = c.jsonObject
            val purpose = o.getValue("purpose").jsonPrimitive.content
            val sessionJson = o.getValue("session")
            val session = if (sessionJson is JsonNull) null else SibianJson.decodeFromJsonElement(Session.serializer(), sessionJson)
            val label = "第 $i 条（$purpose，${session?.mode} · ${session?.concept}）"
            val (result, step) = try {
                demoRun(purpose, o.getValue("payload").jsonObject, session).let { it.result to it.demoStep }
            } catch (e: DemoException) {
                JsonObject(mapOf("error" to JsonPrimitive(e.message))) to session?.demoStep
            }
            assertEquals("$label 的结果", normalize(o.getValue("result")), normalize(result))
            val expected = o.getValue("demoStep").let { if (it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals("$label 之后的 demoStep", expected, step)
        }
    }

    private fun normalize(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${normalize(it.value)}" }
        is JsonArray -> e.joinToString(",", "[", "]") { normalize(it) }
        else -> e.toString()
    }
}
