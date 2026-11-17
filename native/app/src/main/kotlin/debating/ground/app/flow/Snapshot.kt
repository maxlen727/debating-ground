package debating.ground.app.flow

import debating.ground.app.data.Branch
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Entry
import debating.ground.app.data.Reflection
import debating.ground.app.data.Session
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Stage
import debating.ground.app.data.SystemLine
import debating.ground.app.data.Turn
import debating.ground.app.data.levelEntries
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 发给 AI 的讨论快照，和网页版 src/actions.ts 的 snapshot() 一致（FlowTest 每一步都比对）。
// 真实的 AI 只看得到这里给它的东西，所以这一步必须和网页版一模一样。

private fun Session.branchById(id: String?) = if (id == null) null else branches?.find { it.id == id }

/** 从最外层到 [at] 这一层的分支链（不含主干）。 */
fun Session.branchChain(at: String? = this.at): List<Branch> {
    val out = ArrayDeque<Branch>()
    val seen = mutableSetOf<String>()
    var b = branchById(at)
    while (b != null && seen.add(b.id)) {
        out.addFirst(b)
        b = branchById(b.parent)
    }
    return out.toList()
}

/** AI 在某个分支里能看到的内容：主干到分叉点为止，每一层分支到下一个分叉点为止，再加上这个分支自己。 */
fun Session.pathEntries(at: String? = this.at): List<Entry> {
    val chain = branchChain(at)
    val levels = listOf<String?>(null) + chain.map { it.id }
    return levels.flatMapIndexed { i, level ->
        val own = levelEntries(level)
        val fork = chain.getOrNull(i)?.from ?: return@flatMapIndexed own
        val k = own.indexOfFirst { it.id == fork }
        if (k >= 0) own.subList(0, k + 1) else own
    }
}

/** [whole]：复盘要看整场讨论；知识画布平时只给 AI 看当前分支的路径（主干到分叉点 + 这个分支）。 */
fun snapshot(s: Session, whole: Boolean = false): JsonObject {
    val canvas = s.mode == "canvas"
    val entries = if (canvas && !whole) s.pathEntries() else s.entries
    fun topicOf(id: String?) = if (id != null && canvas) s.branches?.find { it.id == id }?.topic else null
    fun label(id: String?) = if (whole) topicOf(id)?.takeIf { it.isNotEmpty() }?.let { "分支：$it" } else null
    val chain = if (canvas && !whole) s.branchChain() else emptyList()
    val here = chain.lastOrNull()
    val from = here?.let { h -> s.entries.find { it.id == h.from } }
    return buildJsonObject {
        put("mode", s.mode)
        put("concept", s.concept)
        put("topic", s.topic)
        put("keyPoints", buildJsonArray { s.keyPoints.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put(
            "roles",
            buildJsonArray {
                s.personas.forEach { r ->
                    add(
                        buildJsonObject {
                            put("id", r.id)
                            put("name", r.name)
                            put("title", r.title)
                            put("persona", r.persona)
                        },
                    )
                }
            },
        )
        put(
            "messages",
            buildJsonArray {
                for (m in entries) {
                    fun msg(kind: String, name: String, text: String, action: String?) = add(
                        buildJsonObject {
                            put("kind", kind)
                            put("name", name)
                            put("text", text)
                            if (action != null) put("action", action)
                        },
                    )
                    when {
                        m is ChatMessage && m.skipped != true -> msg("msg", m.name, m.text, m.action ?: label(m.branch))

                        m is Reflection && m.text.isNotEmpty() -> msg("reflect", "学习者", m.text, label(m.branch))

                        // 从分支带回来的那句话是学习者自己写的，算他的小结
                        m is SystemLine && m.back != null && !topicOf(m.back).isNullOrEmpty() && m.text.isNotEmpty() ->
                            msg("reflect", "学习者", "（从分支「${topicOf(m.back)}」带回）${m.text}", label(m.branch))
                    }
                }
            },
        )
        if (s.mode == "debate") {
            put("turns", SibianJson.encodeToJsonElement(ListSerializer(Turn.serializer()), s.turns))
            put("cursor", s.cursor)
            put("stages", SibianJson.encodeToJsonElement(ListSerializer(Stage.serializer()), s.stages))
        }
        put("finished", s.finished)
        if (!s.context.isNullOrEmpty()) put("context", s.context)
        if (s.mode == "socratic" && s.teaching == true && !whole) put("teaching", true)
        if (here != null) {
            put(
                "branch",
                buildJsonObject {
                    put("topic", here.topic)
                    put("from", if (from is ChatMessage) from.text.take(300) else "")
                    put("path", buildJsonArray { chain.dropLast(1).forEach { add(kotlinx.serialization.json.JsonPrimitive(it.topic)) } })
                },
            )
        }
    }
}
