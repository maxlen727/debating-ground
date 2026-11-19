package debating.ground.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.util.TimeZone

// 概念笔记（#140）：一个概念一篇，每次讨论完把复盘合并进来，越来越准——像 skill 的沉淀。
// 合并是确定的规则、不靠 AI。和网页版 src/lib/notes.ts 逐条对应，NotesTest 用网页版生成的标准答案比对（notes-cases.json）。

/** 各部分最多留多少条：多了就留最新的。 */
object NoteLimits {
    const val POINTS = 10
    const val EXAMPLES = 6
    const val MISCONCEPTIONS = 6
    const val BOUNDARIES = 6
    const val RELATED = 8
    const val STANCES = 4
    const val CHECKS = 6
    const val TERMS = 50
}

/** 两个概念名算不算同一个（不区分大小写、全角半角和空格）。 */
fun sameConcept(a: String, b: String) = normalizeForSearch(a).replace(" ", "") == normalizeForSearch(b).replace(" ", "")

// JavaScript 的 trim() 认的空白和 \s 一样
private fun String.jsTrim() = replace(Regex("^$SPACE_CLASS+|$SPACE_CLASS+$"), "")

// 追加并去重（按 key 比较，新的覆盖旧的、排到后面），超过上限时去掉最早的
private fun <T> mergeList(old: List<T>, add: List<T>, max: Int, key: (T) -> String): List<T> {
    val out = old.toMutableList()
    for (x in add) {
        val k = key(x)
        if (k.jsTrim().isEmpty()) continue
        val i = out.indexOfFirst { sameConcept(key(it), k) }
        if (i >= 0) out.removeAt(i)
        out += x
    }
    return out.takeLast(max)
}

/** 这次复盘是从哪次讨论来的。 */
data class MergeFrom(
    val sessionId: String,
    val at: Long,
    val mode: String,
    val concept: String,
    val topic: String,
    val source: String,
    val model: String? = null,
    val context: String? = null,
    val terms: List<TermCard>? = null,
)

fun emptyNote(id: String, concept: String, at: Long) = Note(id = id, concept = concept, created = at, updated = at)

private fun String?.orKeep(old: String) = this?.jsTrim()?.takeIf { it.isNotEmpty() } ?: old

/**
 * 把一次讨论的复盘合并进笔记：要点按名字对上就更新（新的没给原话就留着旧的），对不上就新增；
 * 例子、易错、边界、相关、立场、自检去重后追加；“一句话”和“什么时候想到它”用最新的；同一次讨论重新复盘替换它那条来源。
 */
fun mergeReview(note: Note, r: SessionReview, from: MergeFrom): Note {
    val points = note.points.toMutableList()
    for (p in r.points) {
        if (p.label.jsTrim().isEmpty()) continue
        val i = points.indexOfFirst { sameConcept(it.label, p.label) }
        val evidence = p.evidence?.jsTrim()?.takeIf { it.isNotEmpty() } ?: points.getOrNull(i)?.evidence?.takeIf { it.isNotEmpty() }
        if (i >= 0) {
            points[i] = points[i].copy(label = p.label, status = p.status, note = p.note, evidence = evidence)
        } else {
            points += Point(p.label, p.status, p.note, evidence)
        }
    }
    val source = NoteSource(from.sessionId, from.at, from.mode, from.topic, r.oneLine, from.source, from.model?.takeIf { it.isNotEmpty() })
    return note.copy(
        summary = r.summary.orKeep(note.summary),
        useWhen = r.useWhen.orKeep(note.useWhen),
        points = points.takeLast(NoteLimits.POINTS),
        examples = mergeList(note.examples, r.examples.orEmpty(), NoteLimits.EXAMPLES) { it },
        misconceptions = mergeList(note.misconceptions, r.misconceptions, NoteLimits.MISCONCEPTIONS) { it.said },
        boundaries = mergeList(note.boundaries, r.boundaries.orEmpty(), NoteLimits.BOUNDARIES) { it },
        related = mergeList(note.related, r.related.orEmpty().filter { !sameConcept(it, note.concept) }, NoteLimits.RELATED) { it },
        stances = mergeList(note.stances, r.stances.orEmpty(), NoteLimits.STANCES) { it.said },
        checks = mergeList(note.checks, r.checks.orEmpty(), NoteLimits.CHECKS) { it.q },
        terms = mergeList(note.terms, from.terms.orEmpty(), NoteLimits.TERMS) { it.term },
        sources = (note.sources.filter { it.sessionId != from.sessionId } + source).sortedBy { it.at },
        context = from.context?.jsTrim()?.takeIf { it.isNotEmpty() } ?: note.context,
        updated = maxOf(note.updated, from.at),
    )
}

/** 自检：答出来了，它检验的要点算掌握；没答出，算模糊。记下时间。 */
fun checkPoint(note: Note, check: SelfCheck, ok: Boolean, at: Long): Note {
    val i = check.point?.let { pt -> note.points.indexOfFirst { sameConcept(it.label, pt) } } ?: -1
    if (i < 0) return note.copy(updated = at)
    val status = if (ok) "mastered" else "partial"
    return note.copy(points = note.points.mapIndexed { j, p -> if (j == i) p.copy(status = status, checked = at) else p }, updated = at)
}

/** 还没弄清的地方：模糊和没讲到的要点、说错过的地方（从薄弱点再讨论时用）。 */
fun Note.weak(): List<String> = points.filter { it.status != "mastered" }.map { it.label } + misconceptions.map { it.said }

data class Tally(val mastered: Int, val partial: Int, val missing: Int)

/** 几个要点掌握、模糊、没讲到。 */
fun Note.tally() = Tally(points.count { it.status == "mastered" }, points.count { it.status == "partial" }, points.count { it.status == "missing" })

/* ---------- 从旧的知识卡片（sibian-v3）迁移 ---------- */

@Serializable
data class OldQuiz(val q: String = "", val options: List<String> = emptyList(), val answer: Double = 0.0, val why: String = "")

/** 旧卡片：一次讨论一张，带四选一的检验题和复习时间（复习时间不再用，迁移时丢掉）。 */
@Serializable
data class OldCard(
    val id: String,
    val sessionId: String = "",
    val concept: String = "",
    val topic: String = "",
    val mode: String = "socratic",
    val created: Long = 0,
    val source: String = "demo",
    val model: String? = null,
    val oneLine: String = "",
    val points: List<Point> = emptyList(),
    val misconceptions: List<Misconception> = emptyList(),
    val stances: List<Stance>? = null,
    val quiz: List<OldQuiz>? = null,
    val terms: List<TermCard>? = null,
    val context: String? = null,
)

/** 旧卡片按概念合并成笔记（按时间从早到晚合并）。四选一的题变成自检：参考答案是正确选项加解析。 */
fun notesFromCards(cards: List<OldCard>): List<Note> {
    val notes = mutableListOf<Note>()
    for (c in cards.sortedBy { it.created }) {
        var i = notes.indexOfFirst { sameConcept(it.concept, c.concept) }
        if (i < 0) {
            notes += emptyNote(c.id, c.concept, c.created)
            i = notes.lastIndex
        }
        val checks = c.quiz.orEmpty().map { q ->
            val right = q.answer.takeIf { it == Math.floor(it) && it >= 0 }?.toInt()?.let { q.options.getOrNull(it) }
            SelfCheck(q.q, listOfNotNull(right, q.why).filter { it.isNotEmpty() }.joinToString("。"))
        }
        val review = SessionReview(oneLine = c.oneLine, points = c.points, misconceptions = c.misconceptions, stances = c.stances, checks = checks)
        notes[i] = mergeReview(notes[i], review, MergeFrom(c.sessionId, c.created, c.mode, c.concept, c.topic, c.source, c.model, c.context, c.terms))
    }
    // 最近更新的排前面
    return notes.sortedByDescending { it.updated }
}

/* ---------- 导出：一篇笔记一个 Markdown 文件，开头是 name / description ---------- */

private val NOTE_STATUS = mapOf("mastered" to "掌握", "partial" to "模糊", "missing" to "没讲到")

private fun yaml(s: String) = JsonPrimitive(s.replace(Regex("$SPACE_CLASS+"), " ").jsTrim()).toString()

fun noteMarkdown(n: Note, frontmatter: Boolean = true, zone: TimeZone = TimeZone.getDefault()): String {
    val out = mutableListOf<String>()
    if (frontmatter) {
        out += listOf("---", "name: ${yaml(n.concept)}")
        if (n.useWhen.isNotEmpty()) out += "description: ${yaml(n.useWhen)}"
        out += listOf("---", "")
    }
    out += listOf("# ${n.concept}", "")
    if (n.summary.isNotEmpty()) out += listOf(n.summary, "")
    if (n.useWhen.isNotEmpty()) out += listOf("**什么时候想到它**：${n.useWhen}", "")
    fun section(title: String, lines: List<String>) {
        if (lines.isNotEmpty()) out += listOf("## $title", "") + lines + ""
    }
    section(
        "要点",
        n.points.map { p ->
            "- [${NOTE_STATUS[p.status] ?: p.status}] ${p.label}" +
                (if (p.note.isNotEmpty()) "：${p.note}" else "") +
                (if (!p.evidence.isNullOrEmpty()) "（我说过：“${p.evidence}”）" else "")
        },
    )
    section("例子", n.examples.map { "- $it" })
    section("易错", n.misconceptions.map { "- ~~${it.said}~~ → ${it.fix}" })
    section("边界", n.boundaries.map { "- $it" })
    section("我的立场", n.stances.map { "- ${it.said}${if (it.note.isNotEmpty()) "（${it.note}）" else ""}" })
    section("相关概念", if (n.related.isNotEmpty()) listOf(n.related.joinToString("、")) else emptyList())
    section("术语", n.terms.map { "- **${it.term}**：${it.definition}${if (it.relation.isNotEmpty()) "（${it.relation}）" else ""}" })
    section("自检", n.checks.withIndex().flatMap { (i, c) -> listOf("${i + 1}. ${c.q}", "   - 参考：${c.answer}") })
    section("来源", n.sources.map { s -> "- ${date(s.at, zone)} ${modeName(s.mode)}：${s.topic}${if (s.oneLine.isNotEmpty()) " —— ${s.oneLine}" else ""}" })
    return out.joinToString("\n").trimEnd() + "\n"
}
