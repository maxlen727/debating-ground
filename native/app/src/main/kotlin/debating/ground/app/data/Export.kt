package debating.ground.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 导出讨论记录与知识库（Markdown），和网页版 src/lib/exporter.ts 逐字一致（FixturesTest 比对）

// 网页版用 toLocaleDateString('zh-CN')：年/月/日，月和日不补零。
// 用 SimpleDateFormat 而不是 java.time：后者要安卓 8.0 才有，App 最低支持安卓 7.0
internal fun date(millis: Long, zone: TimeZone) = SimpleDateFormat("yyyy/M/d", Locale.ROOT).apply { timeZone = zone }.format(Date(millis))

/** 一次讨论的复盘的 Markdown。 */
fun reviewMarkdown(
    concept: String,
    topic: String,
    mode: String,
    created: Long,
    r: SessionReview,
    terms: List<TermCard>?,
    zone: TimeZone = TimeZone.getDefault(),
): String = buildString {
    fun list(title: String, lines: List<String>) {
        if (lines.isNotEmpty()) append("\n\n$title：\n" + lines.joinToString("\n"))
    }
    append("## $concept\n\n讨论题：$topic\n讨论方式：${modeName(mode)} · ${date(created, zone)}\n\n${r.oneLine}")
    if (!r.summary.isNullOrEmpty()) append("\n\n用你的话说：${r.summary}")
    if (!r.useWhen.isNullOrEmpty()) append("\n\n什么时候想到它：${r.useWhen}")
    append("\n\n")
    append(
        r.points.joinToString("\n") {
            "- [${STATUS_LABEL[it.status] ?: it.status}] ${it.label}：${it.note}${if (!it.evidence.isNullOrEmpty()) "（你说过：“${it.evidence}”）" else ""}"
        },
    )
    list("你举过的例子", r.examples.orEmpty().map { "- $it" })
    list("需要纠正的理解", r.misconceptions.map { "- ${it.said} → ${it.fix}" })
    list("边界", r.boundaries.orEmpty().map { "- $it" })
    list("你的立场", r.stances.orEmpty().map { "- ${it.said}${if (it.note.isNotEmpty()) "（${it.note}）" else ""}" })
    list("相关概念", if (r.related.isNullOrEmpty()) emptyList() else listOf(r.related.joinToString("、")))
    list("自检", r.checks.orEmpty().withIndex().map { (i, c) -> "${i + 1}. ${c.q}\n   参考：${c.answer}" })
    list("这次遇到的新词", terms.orEmpty().map { "- ${it.term}：${it.definition}${if (it.relation.isNotEmpty()) "（${it.relation}）" else ""}" })
    append("\n")
}

/** 整个知识库：一篇篇概念笔记连在一起；单独导出一篇时（[noteMarkdown]）带上 name / description 开头。 */
fun libraryMarkdown(notes: List<Note>, zone: TimeZone = TimeZone.getDefault()) =
    "# 思辨场知识库\n\n" + notes.joinToString("\n---\n\n") { noteMarkdown(it, false, zone) }

private fun entriesMarkdown(list: List<Entry>): String = list.mapNotNull { m ->
    when (m) {
        is SystemLine -> if (m.back != null) "> 带回：${m.text}\n" else null

        is Reflection -> if (m.text.isNotEmpty()) "### 小结\n\n${m.text}\n\n> ${m.feedback}\n" else null

        is ChatMessage -> {
            val who = when {
                m.human == true || m.memberId != null -> "真人"
                m.source == "api" -> "AI · ${m.model}"
                else -> "AI · 演示"
            }
            "### ${m.name}${if (m.action != null) " · " + m.action else ""} · $who\n\n${m.text}\n"
        }
    }
}.filter { it.isNotEmpty() }.joinToString("\n")

// 知识画布：先写主干，再按树的顺序写每个分支，标出它从哪句话展开
private fun canvasMarkdown(s: Session): String {
    fun walk(parent: String?, depth: Int): String = s.childrenOf(parent).joinToString("") { b ->
        val from = s.entries.find { it.id == b.from }
        val quote = if (from is ChatMessage) from.text.take(40) + (if (from.text.length > 40) "…" else "") else ""
        "\n${"#".repeat(minOf(depth + 2, 6))} 分支：${b.topic}\n\n${if (quote.isNotEmpty()) "> 从这句展开：$quote\n\n" else ""}" +
            entriesMarkdown(s.levelEntries(b.id)) +
            walk(b.id, depth + 1)
    }
    return entriesMarkdown(s.levelEntries(null)) + walk(null, 0)
}

/** 一次讨论的完整记录，结束了的话带上复盘。 */
fun sessionMarkdown(s: Session, zone: TimeZone = TimeZone.getDefault()): String =
    "# 思辨场讨论记录\n\n概念：${s.concept}\n讨论题：${s.topic}\n讨论方式：${modeName(s.mode)}\n要点：" +
        s.keyPoints.withIndex().joinToString(" ") { (i, k) -> (if (i in s.covered) "●" else "○") + k } +
        "\n\n" +
        (if (!s.branches.isNullOrEmpty()) canvasMarkdown(s) else entriesMarkdown(s.entries)) +
        (
            s.review?.let { r ->
                "\n# 理解复盘\n\n" +
                    reviewMarkdown(s.concept, s.topic, s.mode, s.created, r, s.glossary, zone)
            } ?: ""
            )
