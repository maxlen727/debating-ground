package debating.ground.app.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// 数据结构：一一对应网页版的 shared/types.ts 和 src/store.ts，存档格式完全一致（sibian-v4）。
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

/** 一个要点：掌握到什么程度（mastered / partial / missing）、为什么这么判断、学习者的原话（依据），最后一次自检的时间。 */
@Serializable
data class Point(val label: String = "", val status: String = "partial", val note: String = "", val evidence: String? = null, val checked: Long? = null)

/** 自检题：没有选项，先自己答，再看参考答案；[point] 是它检验的要点。 */
@Serializable
data class SelfCheck(val q: String = "", val answer: String = "", val point: String? = null)

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
    val summary: String? = null,
    val useWhen: String? = null,
    val points: List<Point> = emptyList(),
    val examples: List<String>? = null,
    val misconceptions: List<Misconception> = emptyList(),
    val boundaries: List<String>? = null,
    val related: List<String>? = null,
    val stances: List<Stance>? = null,
    val checks: List<SelfCheck>? = null,
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

/** 笔记是从哪次讨论长出来的（像版本记录）。 */
@Serializable
data class NoteSource(
    val sessionId: String = "",
    val at: Long = 0,
    val mode: String = "socratic",
    val topic: String = "",
    val oneLine: String = "",
    val source: String = "demo",
    val model: String? = null,
)

/** 概念笔记：一个概念一篇，每次讨论完把复盘合并进来（#140，网页版 src/lib/notes.ts 的 Note）。 */
@Serializable
data class Note(
    val id: String,
    val concept: String = "",
    val summary: String = "",
    val useWhen: String = "",
    val points: List<Point> = emptyList(),
    val examples: List<String> = emptyList(),
    val misconceptions: List<Misconception> = emptyList(),
    val boundaries: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val stances: List<Stance> = emptyList(),
    val checks: List<SelfCheck> = emptyList(),
    val terms: List<TermCard> = emptyList(),
    val sources: List<NoteSource> = emptyList(),
    val context: String? = null,
    val created: Long = 0,
    val updated: Long = 0,
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
    val library: List<Note> = emptyList(),
    val screen: String = "learn",
)

fun defaultConfigJson(json: Json = SibianJson): JsonObject = json.encodeToJsonElement(Config.serializer(), Config()).jsonObject

/** 读回存档并转成类型化的数据。结构检查见 [restoreSaved]；检查后仍有个别笔记解析不了，就跳过那一篇，不让整个知识库读不出来。 */
fun loadState(saved: String?, cards: String?, oldSaved: String?, now: Long, json: Json = SibianJson): SavedState {
    val r = restoreSaved(saved, cards, oldSaved, now, json)
    fun <T> part(name: String, ser: kotlinx.serialization.KSerializer<T>): T? = runCatching { json.decodeFromJsonElement(ser, r.getValue(name)) }.getOrNull()
    val library = (r["library"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
        runCatching { json.decodeFromJsonElement(Note.serializer(), it) }.getOrNull()
    }
    return SavedState(
        config = part("config", Config.serializer()) ?: Config(),
        plan = part("plan", Plan.serializer()),
        session = part("session", Session.serializer()),
        library = library,
        screen = (r["screen"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "learn",
    )
}
