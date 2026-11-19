package debating.ground.app.data

import java.text.Normalizer

// 知识库搜索：和网页版 src/lib/search.ts 同一套规则（SearchTest 拿网页版的结果逐条比对）。
// 不区分大小写、全角半角；空格分开的几个词都要命中；概念名命中最重要，其次是结论和术语，其余最轻。

/** 卡片少于这么多时不显示搜索框。 */
const val SEARCH_MIN_CARDS = 4

// JavaScript 正则里 \s 认的空白（Java 的 \s 只认 ASCII）
private val SPACES = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

fun normalizeForSearch(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().replace(SPACES, " ").trim(' ')

private fun fields(c: Card): List<Pair<Int, String>> = listOf(
    3 to c.concept,
    2 to c.oneLine,
    2 to c.terms.orEmpty().joinToString(" ") { it.term },
    1 to c.topic,
    1 to c.points.joinToString(" ") { it.label },
    1 to c.misconceptions.joinToString(" ") { it.said + " " + it.fix },
    1 to c.terms.orEmpty().joinToString(" ") { it.definition },
)

/** 返回命中的卡片（按相关度排好，分数一样时保持原来的顺序）。查询为空时原样返回。 */
fun searchCards(cards: List<Card>, query: String): List<Card> {
    val words = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return cards
    return cards.mapIndexedNotNull { i, c ->
        val fs = fields(c).map { (w, text) -> w to normalizeForSearch(text) }
        var score = 0
        for (word in words) {
            val best = fs.filter { it.second.contains(word) }.maxOfOrNull { it.first } ?: return@mapIndexedNotNull null
            score += best
        }
        Triple(c, score, i)
    }.sortedWith(compareByDescending<Triple<Card, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }
}
