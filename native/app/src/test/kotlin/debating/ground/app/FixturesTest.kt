package debating.ground.app

import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.libraryMarkdown
import debating.ground.app.data.loadState
import debating.ground.app.data.restoreSaved
import debating.ground.app.data.sessionMarkdown
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

// 跨实现的标准答案：scripts/native-fixtures.ts 用网页版的真实代码生成存档，以及网页版读回之后的结果和导出的 Markdown。
// 原生版读同样的存档，结果必须逐字一致。网页版的存档检查或导出改了，先运行 npm run fixtures:native 更新标准答案。
class FixturesTest {
    private val dir = File(javaClass.classLoader!!.getResource("fixtures")!!.toURI())
    private val meta = SibianJson.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
    private val now = meta.getValue("now").jsonPrimitive.long
    private val zone = TimeZone.getTimeZone(meta.getValue("timeZone").jsonPrimitive.content)
    private val scenarios = dir.list()!!.filter { it.endsWith(".saved.json") }.map { it.removeSuffix(".saved.json") }.sorted()

    private fun read(name: String) = File(dir, name).readText()

    private fun web(name: String) = SibianJson.parseToJsonElement(read("$name.restored.json"))

    @Test
    fun thereAreScenarios() {
        assertTrue("没有找到标准答案，先运行 npm run fixtures:native", scenarios.size >= 5)
    }

    @Test
    fun restoringASaveGivesExactlyWhatTheWebAppGives() {
        for (name in scenarios) {
            val native = restoreSaved(read("$name.saved.json"), null, now)
            assertEquals("场景 $name 读回存档的结果", normalize(web(name)), normalize(native))
        }
    }

    // 类型化以后再写回 JSON，也要和网页版一样：说明数据结构的字段、可选与否都和网页版对得上。
    // 只有一处放宽：网页版把“必有但为空”的字段写成 null，原生版省略这个键。两者等价——网页版读到缺这个键的存档，
    // 检查时会补回 null（restoreSession、restorePlan 都这样做）。
    @Test
    fun typedModelsRoundTripLikeTheWebApp() {
        for (name in scenarios) {
            val state = loadState(read("$name.saved.json"), null, now)
            val typed = SibianJson.encodeToJsonElement(SavedState.serializer(), state)
            assertEquals("场景 $name 类型化后写回的 JSON", normalize(web(name), dropNulls = true), normalize(typed, dropNulls = true))
        }
    }

    @Test
    fun markdownExportMatchesTheWebApp() {
        for (name in scenarios) {
            val state = loadState(read("$name.saved.json"), null, now)
            assertEquals("场景 $name 导出的知识库", read("$name.library.md"), libraryMarkdown(state.library, zone))
            val session = File(dir, "$name.session.md")
            if (session.exists()) {
                assertEquals("场景 $name 导出的讨论记录", session.readText(), sessionMarkdown(state.session!!, zone))
            }
        }
    }

    // JSON 对象比较与键的顺序无关；数字统一成同一种写法（网页版写 1，类型化后可能写 1.0 或反之）
    private fun normalize(e: JsonElement, dropNulls: Boolean = false): String = when (e) {
        is JsonObject ->
            e.entries.filter { !dropNulls || it.value !is JsonNull }.sortedBy { it.key }
                .joinToString(",", "{", "}") { "\"${it.key}\":${normalize(it.value, dropNulls)}" }

        is kotlinx.serialization.json.JsonArray -> e.joinToString(",", "[", "]") { normalize(it, dropNulls) }

        else -> e.jsonPrimitive.let { p ->
            if (p.isString) {
                p.toString()
            } else {
                p.content.toDoubleOrNull()?.let { d ->
                    if (d == Math.floor(d) &&
                        !d.isInfinite()
                    ) {
                        d.toLong().toString()
                    } else {
                        d.toString()
                    }
                }
                    ?: p.content
            }
        }
    }
}
