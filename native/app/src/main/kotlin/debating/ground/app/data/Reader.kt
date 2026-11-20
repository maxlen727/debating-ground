package debating.ground.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 读材料（#144、#154、#166）：把材料切成节，讨论时按话题挑出最相关的几节交给 AI。
// 和网页版 src/lib/reader.ts 同一套规则，ReaderTest 拿网页版生成的标准答案（reader-cases.json）逐字比对。
// 学习者贴一篇材料就直接围绕它讨论：短材料整篇给 AI；长材料每轮给目录，加上和眼下话题最相关的几节（按词的重合度挑，
// 固定规则，不额外调用模型），总长有上限。

object ReaderLimits {
    const val TEXT = 300_000 // 整篇材料最多多少字
    const val SECTIONS = 80 // 最多切成几节
    const val SECTION = 3000 // 一节太长就按段落再切
    const val EXCERPT = 10_000 // 每次带给 AI 的材料（目录加原文）最多多少字
    const val OUTLINE = 2000 // 目录最多多少字
}

// JavaScript 正则的 \s、\S、.（不认换行）
private val SPACE_INNER = SPACE_CLASS.substring(1, SPACE_CLASS.length - 1)
private const val ANY = "[^\\n\\r\\u2028\\u2029]"
private val NOT_SPACE = "[^$SPACE_INNER]"
private val EDGE_SPACES = Regex("^$SPACE_CLASS+|$SPACE_CLASS+$")

/** 和 JavaScript 的 trim() 一样去掉两头的空白（含全角空格）。 */
internal fun String.jsTrimmed() = replace(EDGE_SPACES, "")

/* ---------- 切节 ---------- */

// 像标题的一行：Markdown 标题、带编号的短行（1 引言、2.1 方法）、论文里常见的节名
private val MD_HEADING = Regex("^#{1,4}$SPACE_CLASS+($ANY+)$")
private val NUMBERED = Regex("^(\\d{1,2}(?:\\.\\d{1,2}){0,2})\\.?$SPACE_CLASS+($NOT_SPACE$ANY{0,58})$")
private val NAMED = Regex(
    "^(摘要|引言|前言|背景|相关工作|方法|实验|结果|讨论|结论|总结|参考文献|致谢|附录|abstract|introduction|background|related work|methods?|experiments?|results?|discussion|conclusions?|references|acknowledg(?:e)?ments?|appendix)$SPACE_CLASS*$",
    RegexOption.IGNORE_CASE,
)
private val ENDS_LIKE_SENTENCE = Regex("[。！？；，、.!?;,:：]$")
private val PARAGRAPHS = Regex("\\n$SPACE_CLASS*\\n")

private fun headingOf(line: String): String? {
    val t = line.jsTrimmed()
    if (t.isEmpty() || t.length > 60) return null
    MD_HEADING.find(t)?.let { return it.groupValues[1].jsTrimmed() }
    if (ENDS_LIKE_SENTENCE.containsMatchIn(t)) return null
    NUMBERED.find(t)?.let { return "${it.groupValues[1]} ${it.groupValues[2].jsTrimmed()}" }
    if (NAMED.containsMatchIn(t)) return t
    return null
}

// 按段落把一段长文字切成不超过 size 字的几块（段落太长就硬切）
private fun chunk(text: String, size: Int): List<String> {
    val paras = text.split(PARAGRAPHS).map { it.jsTrimmed() }.filter { it.isNotEmpty() }
    val out = mutableListOf<String>()
    var cur = ""
    for (p in paras) {
        var i = 0
        while (i < p.length) {
            val piece = p.substring(i, minOf(p.length, i + size))
            if (cur.isNotEmpty() && cur.length + piece.length + 2 > size) {
                out += cur
                cur = ""
            }
            cur = if (cur.isNotEmpty()) cur + "\n\n" + piece else piece
            i += size
        }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

/** 把材料切成节：认得出标题就按标题切，认不出就按长度切；一节太长再按段落切开。 */
fun splitMaterial(raw: String): List<MaterialSection> {
    val text = raw.replace(Regex("\\r\\n?"), "\n").jsTrimmed()
    data class Part(val heading: String, val lines: MutableList<String> = mutableListOf())
    val parts = mutableListOf<Part>()
    var cur = Part("开头")
    for (line in text.split("\n")) {
        val h = headingOf(line)
        if (h != null) {
            if (cur.lines.joinToString("").jsTrimmed().isNotEmpty() || cur.heading != "开头") parts += cur
            cur = Part(h)
        } else {
            cur.lines += line
        }
    }
    parts += cur
    var blocks = parts.map { it.heading to it.lines.joinToString("\n").jsTrimmed() }.filter { it.second.isNotEmpty() }
    // 认不出结构（一个标题都没有）：整篇按长度切
    if (blocks.size <= 1 && (blocks.firstOrNull()?.first ?: "开头") == "开头") blocks = listOf("" to text)
    var out = blocks.flatMap { (heading, body) ->
        val pieces = chunk(body, ReaderLimits.SECTION)
        pieces.mapIndexed { i, t ->
            val h = when {
                heading.isEmpty() -> "第 ${i + 1} 部分"
                pieces.size > 1 -> "$heading（${i + 1}）"
                else -> heading
            }
            h to t
        }
    }
    // 节太多：整篇按更大的块重新切
    if (out.size > ReaderLimits.SECTIONS) {
        val size = (text.length + ReaderLimits.SECTIONS - 1) / ReaderLimits.SECTIONS + 200
        out = chunk(text, size).mapIndexed { i, t -> "第 ${i + 1} 部分" to t }
    }
    return out.take(ReaderLimits.SECTIONS).mapIndexed { i, (h, t) -> MaterialSection(i + 1, h, t) }
}

/* ---------- 原文 ---------- */

private fun block(s: MaterialSection) = "§${s.n} ${s.heading}\n${s.text}"

/** 读某几节：原文拼在一起，总长有上限。 */
fun readSections(sections: List<MaterialSection>, ns: List<Int>, max: Int = ReaderLimits.EXCERPT): String {
    val picked = ns.mapNotNull { n -> sections.find { it.n == n } }
    val more = "…（这一节后面省略）"
    val share = (max - 2 * (picked.size - 1)) / maxOf(1, picked.size)
    return picked.joinToString("\n\n") { s ->
        val b = block(s)
        if (b.length > share) b.substring(0, maxOf(0, share - more.length)) + more else b
    }
}

/* ---------- 讨论时带给 AI 的材料 ---------- */

/** 目录：每节的编号和标题。 */
fun outlineText(sections: List<MaterialSection>): String {
    val out = StringBuilder("目录：")
    for (s in sections) {
        val line = "\n§${s.n} ${s.heading}"
        if (out.length + line.length > ReaderLimits.OUTLINE) {
            out.append("\n……")
            break
        }
        out.append(line)
    }
    return out.toString()
}

private val WORDS = Regex("[a-z0-9]{3,}")
private val NOT_HAN = Regex("[^\u3400-\u9fff]+")

// 拿来比较的词：中文按相邻两个字，英文和数字按整个词（三个字母以上）
private fun terms(text: String): Set<String> {
    val t = normalizeForSearch(text)
    val out = LinkedHashSet<String>()
    WORDS.findAll(t).forEach { out += it.value }
    for (run in t.replace(NOT_HAN, " ").split(" ")) {
        for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
    }
    return out
}

/** 和 query 的相关程度：query 里的词在这一节里出现了几个（标题里出现算两个）。 */
fun relevance(s: MaterialSection, query: Set<String>): Int {
    val body = normalizeForSearch(s.text)
    val head = normalizeForSearch(s.heading)
    return query.sumOf { w ->
        when {
            head.contains(w) -> 2
            body.contains(w) -> 1
            else -> 0
        }.toInt()
    }
}

private fun size(s: MaterialSection) = s.heading.length + s.text.length + 8

/**
 * 讨论时带给 AI 的材料：放得下就整篇；放不下就是目录，加上和 query（讨论题、刚说的话）最相关的几节，
 * 按原文顺序排。一节也不相关时给开头几节。
 */
fun materialContext(sections: List<MaterialSection>, query: String): String {
    if (sections.isEmpty()) return ""
    if (sections.sumOf { size(it) } <= ReaderLimits.EXCERPT - 40) return readSections(sections, sections.map { it.n }, ReaderLimits.EXCERPT)
    val outline = outlineText(sections)
    val budget = ReaderLimits.EXCERPT - outline.length - 2
    val q = terms(query)
    val ranked = sections.map {
        it to relevance(it, q)
    }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<MaterialSection, Int>> { it.second }.thenBy { it.first.n })
    val order = if (ranked.isNotEmpty()) ranked.map { it.first } else sections
    val picked = mutableListOf<Int>()
    var used = 0
    for (s in order) {
        val n = size(s)
        if (picked.isNotEmpty() && used + n > budget) continue
        picked += s.n
        used += n
        if (used >= budget || picked.size >= 6) break
    }
    return outline + "\n\n" + readSections(sections, picked.sorted(), budget)
}

/** 开始讨论时（准备讨论题、要点）带给 AI 的材料：目录和开头几节。 */
fun startContext(sections: List<MaterialSection>) = materialContext(sections, "")

/** 讨论里该按什么找相关的几节：讨论题、正在聊的分支、最近两条发言（[snapshot] 是发给 AI 的讨论快照）。 */
fun contextQuery(snapshot: JsonObject): String {
    fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val messages = (snapshot["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return (listOf(str(snapshot, "topic"), str(snapshot["branch"] as? JsonObject, "topic")) + messages.takeLast(2).map { str(it, "text") }).joinToString("\n")
}
