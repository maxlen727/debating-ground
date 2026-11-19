package debating.ground.app

import debating.ground.app.ai.Ai
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Graph
import debating.ground.app.data.Reflection
import debating.ground.app.data.SibianJson
import debating.ground.app.data.graph
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import debating.ground.app.flow.snapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// 学习流程按剧本和网页版逐步比对：scripts/native-fixtures.ts 在网页版用演示模式跑几段剧本，
// 每一步之后记下规整后的状态和发给 AI 的快照（canonical：id 换成按出现顺序的占位符，时间戳换成 <t>）。
// 这里按同样的剧本操作，每一步的状态、快照、复盘用的完整快照都要一致。
class FlowTest {
    private val scripts = SibianJson.parseToJsonElement(
        File(javaClass.classLoader!!.getResource("fixtures/flow-scripts.json")!!.toURI()).readText(),
    ).jsonArray

    @Test
    fun everyScriptMatchesTheWebAppStepByStep() = runBlocking {
        for (script in scripts.map { it.jsonObject }) {
            val name = script.getValue("name").jsonPrimitive.content
            var clock = 1_000_000L
            var n = 0
            val flow = Learning(AppState(), Ai(demoTypingMs = 0), now = {
                clock += 1000
                clock
            }, uid = { "id${n++}" })
            val ops = script.getValue("ops").jsonArray.map { it.jsonObject }
            val steps = script.getValue("steps").jsonArray.map { it.jsonObject }
            for ((i, op) in ops.withIndex()) {
                run(flow, op)
                val label = "剧本 $name 第 $i 步（${op.getValue("op").jsonPrimitive.content}）"
                val expected = steps[i]
                assertEquals("$label 之后的状态", text(expected.getValue("state")), text(canonical(stateJson(flow.state))))
                val s = flow.state.session
                assertEquals("$label 发给 AI 的快照", text(expected.getValue("snapshot")), text(s?.let { canonical(snapshot(it)) } ?: JsonNull))
                assertEquals("$label 复盘用的完整快照", text(expected.getValue("whole")), text(s?.let { canonical(snapshot(it, true)) } ?: JsonNull))
                val graph = expected["graph"]
                if (graph != null && graph !is JsonNull) {
                    assertEquals("$label 之后的分支图", text(graph), text(canonical(SibianJson.encodeToJsonElement(Graph.serializer(), s!!.graph()))))
                }
                // 提示只算这一步的
                flow.toast("")
            }
        }
    }

    // 和网页版剧本执行器一样的操作和目标规则
    private suspend fun run(flow: Learning, o: JsonObject) {
        fun str(k: String) = o.getValue(k).jsonPrimitive.content
        val st = flow.state
        fun lastAi() = st.session!!.entries.last { it is ChatMessage && it.human != true }
        fun lastReflection() = st.session!!.entries.lastOrNull { it is Reflection } as Reflection?
        when (str("op")) {
            "config" -> {
                flow.setConcept(str("concept"))
                flow.setMode(str("mode"))
            }

            "seatsAllAi" -> flow.editConfig { c -> c.copy(seats = c.seats.mapValues { (_, v) -> v.map { it.copy(type = "ai") } }) }

            "prepare" -> flow.prepare(null, null, o["then"]?.jsonPrimitive?.content ?: "review")

            "refreshRoles" -> flow.refreshRoles()

            "start" -> flow.startSession()

            "send" -> flow.send(str("text"))

            "quick" -> flow.quick(str("kind"), lastAi().id)

            "addReflection" -> flow.addReflection()

            "dropReflection" -> flow.dropReflection(lastReflection()!!.id)

            "reflectSubmit" -> {
                val r = lastReflection()
                if (r == null || r.text.isNotEmpty()) flow.addReflection()
                val target = flow.state.session!!.entries.last { it is Reflection }
                flow.setReflectionDraft(target.id, str("draft"))
                flow.submitReflection(target.id)
            }

            "hint" -> flow.hint()

            "proposePoint" -> flow.proposePoint(str("text"))

            "suggestPoint" -> flow.suggestPoint()

            "decidePoint" -> flow.decidePoint(st.session!!.proposedPoints!!.first().id, o.getValue("accept").jsonPrimitive.boolean)

            "term" -> {
                val term = str("term")
                val m = st.session!!.entries.last { it is ChatMessage && it.terms?.contains(term) == true }
                flow.openTerm(term, m.id)
            }

            "teaching" -> flow.toggleTeaching()

            "nextSpeaker" -> flow.nextSpeaker()

            "openBranch" -> flow.openBranch(lastAi().id, str("topic"))

            "goToBranch" -> flow.goToBranch(if (str("to") == "trunk") null else st.session!!.branches!!.first().id)

            "bringBack" -> flow.bringBack(str("gist"))

            "generateSeat" -> flow.generateSeat()

            "skipSeat" -> flow.skipSeat()

            "untilEnd" -> {
                // 把剩下的赛程走完：轮到“我”就发一句，轮到 AI 就生成
                var guard = 0
                while (guard++ < 40) {
                    val t = flow.currentTurn() ?: break
                    if (t.role.type == "human") flow.send("${t.action}：我方坚持机会成本才是最大的代价。") else flow.generateSeat()
                }
            }

            "finish" -> flow.finish()

            "check" -> flow.selfCheck(flow.state.library.first().id, o.getValue("index").jsonPrimitive.int, o.getValue("ok").jsonPrimitive.boolean)

            "discussAgain" -> flow.discussAgain(flow.state.library.first())

            else -> error("未知操作 ${str("op")}")
        }
    }

    private fun stateJson(s: AppState): JsonElement {
        val saved = SibianJson.encodeToJsonElement(debating.ground.app.data.SavedState.serializer(), s.saved()).jsonObject
        val u = s.ui
        return JsonObject(
            saved.filterKeys { it != "screen" } + mapOf(
                "ui" to buildJsonObject {
                    put("screen", u.screen)
                    put("busy", u.busy)
                    put("error", u.error)
                    put("toast", u.toast)
                    put("hint", u.hint)
                    put(
                        "explain",
                        u.explain?.let { e ->
                            buildJsonObject {
                                put("kind", e.kind)
                                put("label", e.label)
                                put("quote", e.quote)
                                put("text", e.text)
                            }
                        }
                            ?: JsonNull,
                    )
                    put("sheet", u.sheet)
                    put("openCard", u.openCard)
                    put(
                        "term",
                        u.term?.let { t ->
                            buildJsonObject {
                                put("term", t.term)
                                put("definition", t.definition)
                                put("relation", t.relation)
                                put("messageId", t.messageId)
                            }
                        }
                            ?: JsonNull,
                    )
                    put(
                        "context",
                        u.context?.let { c ->
                            buildJsonObject {
                                put("ask", c.ask)
                                put("text", c.text)
                            }
                        } ?: JsonNull,
                    )
                },
            ),
        )
    }

    // 和 scripts/native-fixtures.ts 的 canonical() 一样：键按字母顺序遍历，id 换成按出现顺序编号的占位符，时间戳换成 <t>
    private val idKeys = setOf("id", "sessionId", "from", "parent", "branch", "back", "messageId", "openCard", "sheet", "at")
    private val timeKeys = setOf("created", "due", "at", "updated", "checked")

    private fun canonical(v: JsonElement, ids: MutableMap<String, String> = mutableMapOf(), key: String = ""): JsonElement = when (v) {
        is JsonArray -> JsonArray(v.map { canonical(it, ids, key) })

        is JsonObject -> JsonObject(v.keys.sorted().associateWith { canonical(v.getValue(it), ids, it) })

        is JsonPrimitive -> when {
            !v.isString && v !is JsonNull && key in timeKeys -> JsonPrimitive("<t>")
            v.isString && key in idKeys -> JsonPrimitive(ids.getOrPut(v.content) { "#${ids.size + 1}" })
            else -> v
        }
    }

    // 比较时：null 和缺省等价（见 FixturesTest 的说明），数字写法统一，键排序
    private fun text(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.filter { it.value !is JsonNull }.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${text(it.value)}" }

        is JsonArray -> e.joinToString(",", "[", "]") { text(it) }

        is JsonPrimitive -> if (e.isString) {
            e.toString()
        } else {
            e.content.toDoubleOrNull()?.let { d ->
                if (d ==
                    Math.floor(d)
                ) {
                    d.toLong().toString()
                } else {
                    d.toString()
                }
            }
                ?: e.content
        }
    }
}
