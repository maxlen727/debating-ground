package debating.ground.app.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// 数据结构：一一对应网页版的 shared/types.ts 和 src/store.ts，存档格式完全一致（sibian-v3）。
// 网页版里可选的字段（?:）这里是可空、默认 null，写回 JSON 时省略；必有的字段带默认值，总会写出。

/** 读写存档用的 JSON 设置：多余的字段忽略，类型不对的值退回默认值。 */
@OptIn(ExperimentalSerializationApi::class)
val SibianJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class Persona(val id: String, val name: String, val title: String = "", val persona: String = "", val tone: String = "c")

@Serializable
data class SeatConfig(val type: String = "ai", val bg: String = "")

/** 辩论席位（store.ts 的 DebateSeat：Seat 加上显示名）。 */
@Serializable
data class Seat(val id: String, val team: String, val index: Int, val type: String = "ai", val bg: String = "", val name: String? = null)

@Serializable
data class Stage(val name: String, val kind: String, val rounds: Int)

@Serializable
data class Turn(val role: Seat, val action: String, val target: String? = null, val stage: Int, val round: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Entry {
    val id: String
    val text: String
    val branch: String?
}

@Serializable
@SerialName("msg")
data class ChatMessage(
    override val id: String,
    val who: String = "",
    val name: String = "",
    override val text: String,
    val source: String = "demo",
    val model: String? = null,
    val human: Boolean? = null,
    val team: String? = null,
    val stage: Int? = null,
    val action: String? = null,
    val target: String? = null,
    val skipped: Boolean? = null,
    val memberId: String? = null,
    val terms: List<String>? = null,
    override val branch: String? = null,
) : Entry

@Serializable
@SerialName("sys")
data class SystemLine(override val id: String, override val text: String, override val branch: String? = null, val back: String? = null) : Entry

@Serializable
@SerialName("reflect")
data class Reflection(
    override val id: String,
    override val text: String,
    val feedback: String = "",
    val draft: String = "",
    override val branch: String? = null,
) : Entry

@Serializable
data class QuizItem(val q: String = "", val options: List<String> = emptyList(), val answer: Int = 0, val why: String = "")

@Serializable
data class Point(val label: String = "", val status: String = "partial", val note: String = "")

@Serializable
data class Misconception(val said: String = "", val fix: String = "")

@Serializable
data class Stance(val said: String = "", val note: String = "")

@Serializable
data class TermCard(val term: String = "", val definition: String = "", val relation: String = "")

@Serializable
data class Previous(val oneLine: String = "", val weak: List<String> = emptyList())

/** 讨论结束后的复盘（Review 加上来源）。 */
@Serializable
data class SessionReview(
    val oneLine: String = "",
    val points: List<Point> = emptyList(),
    val misconceptions: List<Misconception> = emptyList(),
    val stances: List<Stance>? = null,
    val quiz: List<QuizItem> = emptyList(),
    val source: String = "demo",
    val model: String? = null,
)

@Serializable
data class PointProposal(val id: String, val text: String, val source: String, val proposer: String = "", val by: String = "")

/** 知识画布的一个分支：从 [from] 这条发言展开（它在 [parent] 那一层），[gist] 是学习者带回上一层的一句话。 */
@Serializable
data class Branch(val id: String, val parent: String? = null, val from: String, val topic: String, val gist: String? = null, val created: Long = 0)

@Serializable
data class Session(
    val id: String,
    val created: Long = 0,
    val mode: String,
    val concept: String,
    val topic: String,
    val keyPoints: List<String> = emptyList(),
    val covered: List<Int> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val learnerTurns: Int = 0,
    val nextRole: Int = 0,
    val demoStep: Int = 0,
    val finished: Boolean = false,
    val draft: String = "",
    val previous: Previous? = null,
    val context: String? = null,
    val awaitingReview: Boolean? = null,
    val proposedPoints: List<PointProposal>? = null,
    val personas: List<Persona> = emptyList(),
    val seats: List<Seat> = emptyList(),
    val stages: List<Stage> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val cursor: Int = 0,
    val review: SessionReview? = null,
    // 多人房间：首版不做，原样保留
    val room: JsonElement? = null,
    val glossary: List<TermCard>? = null,
    val branches: List<Branch>? = null,
    val at: String? = null,
    val teaching: Boolean? = null,
)

@Serializable
data class ReviewRecord(val at: Long, val remembered: Boolean)

/** 知识卡片：一次讨论的复盘，按间隔复习。 */
@Serializable
data class Card(
    val id: String,
    val sessionId: String = "",
    val concept: String = "",
    val topic: String = "",
    val mode: String = "socratic",
    val created: Long = 0,
    val source: String = "demo",
    val model: String? = null,
    val level: Int = 0,
    val due: Long = 0,
    val reviews: List<ReviewRecord> = emptyList(),
    val oneLine: String = "",
    val points: List<Point> = emptyList(),
    val misconceptions: List<Misconception> = emptyList(),
    val stances: List<Stance>? = null,
    val quiz: List<QuizItem> = emptyList(),
    val terms: List<TermCard>? = null,
    val context: String? = null,
)

@Serializable
data class Plan(
    val mode: String,
    val concept: String,
    val topic: String,
    val keyPoints: List<String> = emptyList(),
    val opening: String = "",
    val personas: List<Persona> = emptyList(),
    val source: String = "demo",
    val model: String? = null,
    val previous: Previous? = null,
    val context: String? = null,
    val debaters: Map<String, List<String>>? = null,
    val rolesFor: String? = null,
)

@Serializable
data class Remote(val url: String = "", val password: String = "")

@Serializable
data class Config(
    val concept: String = "机会成本",
    val mode: String = "socratic",
    // demo：演示模板；api：本机服务（原生版里是“直接连接服务商”）；remote：自己部署的后端
    val aiMode: String = "demo",
    val remote: Remote = Remote(),
    val name: String = "",
    val counts: Map<String, Int> = mapOf("A" to 2, "B" to 2),
    val seats: Map<String, List<SeatConfig>> = mapOf(
        "A" to listOf(SeatConfig("human", ""), SeatConfig("ai", "经济学研究者，习惯用数据和模型说话。")),
        "B" to listOf(SeatConfig("ai", "中学老师，关注概念在日常生活里怎么被误用。"), SeatConfig("ai", "创业者，从真实的经营取舍出发。")),
    ),
    val stages: List<Stage> = listOf(
        Stage("开篇立论", "opening", 1),
        Stage("交叉质询", "cross", 1),
        Stage("自由辩论", "free", 2),
        Stage("总结陈词", "closing", 1),
    ),
    val turnSeconds: Int = 0,
    val voiceName: String = DEFAULT_VOICE,
)

/** 讯飞默认发音人，和 shared/speech.ts 的 DEFAULT_VOICE 一致。 */
const val DEFAULT_VOICE = "x4_xiaoyan"

/** 整份存档：和网页版写进 localStorage 的结构一样。 */
@Serializable
data class SavedState(
    val config: Config = Config(),
    val plan: Plan? = null,
    val session: Session? = null,
    val library: List<Card> = emptyList(),
    val screen: String = "learn",
)

fun defaultConfigJson(json: Json = SibianJson): JsonObject = json.encodeToJsonElement(Config.serializer(), Config()).jsonObject

/** 读回存档并转成类型化的数据。结构检查见 [restoreSaved]；检查后仍有个别卡片解析不了，就跳过那一张，不让整个知识库读不出来。 */
fun loadState(saved: String?, oldSaved: String?, now: Long, json: Json = SibianJson): SavedState {
    val r = restoreSaved(saved, oldSaved, now, json)
    fun <T> part(name: String, ser: kotlinx.serialization.KSerializer<T>): T? = runCatching { json.decodeFromJsonElement(ser, r.getValue(name)) }.getOrNull()
    val library = (r["library"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
        runCatching { json.decodeFromJsonElement(Card.serializer(), it) }.getOrNull()
    }
    return SavedState(
        config = part("config", Config.serializer()) ?: Config(),
        plan = part("plan", Plan.serializer()),
        session = part("session", Session.serializer()),
        library = library,
        screen = (r["screen"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "learn",
    )
}
