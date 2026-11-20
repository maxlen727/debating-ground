package debating.ground.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// 读材料（#144、#154）：把材料切成节、研读助手的工具（读某几节、搜一个词）和它的循环。
// 和网页版 src/lib/reader.ts 同一套规则，ReaderTest 拿网页版生成的标准答案（reader-cases.json）逐字比对。
// 循环在 App 里跑：材料只在这台设备上，每一步只做一次模型调用（用途 read）。工具都是只读的，原文当资料不当指令（提示词里写明了）。

object ReaderLimits {
    const val TEXT = 300_000 // 整篇材料最多多少字
    const val SECTIONS = 80 // 最多切成几节
    const val SECTION = 3000 // 一节太长就按段落再切
    const val OBSERVATION = 12_000 // 一步最多读回多少字
    const val EXCERPT = 8000 // 讨论时带上的节选
    const val STEPS = 8 // 研读助手最多走几步
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

/** 大纲：每节的编号、标题和字数（研读助手先看这个）。 */
fun outlineOf(sections: List<MaterialSection>): JsonArray = buildJsonArray {
    for (s in sections) {
        add(
            buildJsonObject {
                put("n", s.n)
                put("heading", s.heading)
                put("chars", s.text.length)
            },
        )
    }
}

/* ---------- 工具（只读） ---------- */

private fun block(s: MaterialSection) = "§${s.n} ${s.heading}\n${s.text}"

/** 读某几节：原文拼在一起，总长有上限。 */
fun readSections(sections: List<MaterialSection>, ns: List<Int>, max: Int = ReaderLimits.OBSERVATION): String {
    val picked = ns.mapNotNull { n -> sections.find { it.n == n } }
    val more = "…（这一节后面省略）"
    val share = (max - 2 * (picked.size - 1)) / maxOf(1, picked.size)
    return picked.joinToString("\n\n") { s ->
        val b = block(s)
        if (b.length > share) b.substring(0, maxOf(0, share - more.length)) + more else b
    }
}

/** 在原文里搜一个词：最多 5 处，每处前后各带一些文字，标出在第几节。 */
fun searchMaterial(sections: List<MaterialSection>, query: String): String {
    val q = normalizeForSearch(query)
    if (q.isEmpty()) return "没有要搜的词。"
    val hits = mutableListOf<String>()
    for (s in sections) {
        val t = normalizeForSearch(s.text)
        var from = 0
        while (hits.size < 5) {
            val i = t.indexOf(q, from)
            if (i < 0) break
            hits += "§${s.n} ${s.heading}：…${t.substring(maxOf(0, i - 80), minOf(t.length, i + q.length + 80))}…"
            from = i + q.length
        }
        if (hits.size >= 5) break
    }
    return if (hits.isNotEmpty()) hits.joinToString("\n") else "原文里没有找到「$query」。"
}

/** 研读助手的一个动作：读几节、搜一个词，或者整理成地图结束。 */
sealed interface ReadAction {
    val thought: String

    data class Read(override val thought: String, val sections: List<Int>) : ReadAction

    data class Search(override val thought: String, val query: String) : ReadAction

    data class Finish(override val thought: String, val map: MaterialMap) : ReadAction
}

/** 模型给的动作（已经过 finalize 校验）；认不出就是 null。 */
fun readActionOf(v: JsonElement?): ReadAction? {
    val o = v as? JsonObject ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val thought = str("thought").orEmpty()
    return when (str("action")) {
        "read" -> ReadAction.Read(thought, (o["sections"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty())

        "search" -> ReadAction.Search(thought, str("query") ?: return null)

        "finish" -> ReadAction.Finish(
            thought,
            runCatching { SibianJson.decodeFromJsonElement(MaterialMap.serializer(), o.getValue("map")) }.getOrNull() ?: return null,
        )

        else -> null
    }
}

/** 执行一个动作，得到这一步的记录（给人看的动作描述 + 给模型看的原文）。 */
fun runTool(sections: List<MaterialSection>, a: ReadAction): ReadStep = when (a) {
    is ReadAction.Read -> ReadStep(a.thought, "读第 ${a.sections.joinToString("、")} 节", readSections(sections, a.sections))
    is ReadAction.Search -> ReadStep(a.thought, "搜「${a.query}」", searchMaterial(sections, a.query))
    is ReadAction.Finish -> ReadStep(a.thought, "整理成地图", "")
}

/* ---------- 讨论时带上的节选 ---------- */

/** 一个技术点在材料里的原文节选：它所在的几节；没写在哪几节就搜它的名字。 */
fun excerptFor(id: String, title: String, sections: List<MaterialSection>, point: MapPoint): SessionMaterial {
    var ns = point.sections.filter { n -> sections.any { it.n == n } }
    if (ns.isEmpty()) {
        val q = normalizeForSearch(point.name)
        ns = sections.filter { normalizeForSearch(it.text).contains(q) }.take(2).map { it.n }
    }
    if (ns.isEmpty()) ns = listOf(sections.firstOrNull()?.n ?: 1)
    return SessionMaterial(id, title, point.name, readSections(sections, ns.take(4), ReaderLimits.EXCERPT))
}

/* ---------- 研读助手的循环 ---------- */

/** 发给模型的一步：标题、大纲、之前每一步的记录；最后一步必须结束。 */
fun readPayload(title: String, outline: JsonArray, steps: List<ReadStep>, last: Boolean): JsonObject = buildJsonObject {
    put("title", title)
    put("outline", outline)
    put("steps", SibianJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ReadStep.serializer()), steps))
    if (last) put("last", true)
}

/**
 * 跑一遍研读助手：每一步问模型要一个动作，执行后记下来，直到它给出地图。
 * [onStep] 每走一步就通知一次（界面上显示它在做什么）。模型出错或被取消时返回 null。
 */
suspend fun runReader(
    title: String,
    sections: List<MaterialSection>,
    ask: suspend (JsonObject, List<ReadStep>) -> ReadAction?,
    onStep: (List<ReadStep>) -> Unit = {},
): Pair<MaterialMap, List<ReadStep>>? {
    val outline = outlineOf(sections)
    val steps = mutableListOf<ReadStep>()
    for (i in 0 until ReaderLimits.STEPS) {
        val last = i == ReaderLimits.STEPS - 1
        val a = ask(readPayload(title, outline, steps, last), steps.toList()) ?: return null
        steps += runTool(sections, a)
        onStep(steps.toList())
        if (a is ReadAction.Finish) return a.map.copy(title = a.map.title.ifEmpty { title }) to steps.toList()
    }
    return null
}
