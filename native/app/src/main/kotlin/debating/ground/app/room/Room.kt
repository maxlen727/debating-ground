package debating.ground.app.room

import android.util.Base64
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Config
import debating.ground.app.data.Entry
import debating.ground.app.data.Persona
import debating.ground.app.data.Plan
import debating.ground.app.data.PointProposal
import debating.ground.app.data.Reflection
import debating.ground.app.data.Seat
import debating.ground.app.data.Session
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Stage
import debating.ground.app.data.Turn
import debating.ground.app.flow.debateTurns
import debating.ground.app.flow.seatName
import debating.ground.app.flow.slots
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

// 多人房间：和网页版 shared/room.ts、src/lib/room.ts 同样的消息格式和规则（RoomTest 拿网页版跑出来的结果逐条比对）。
// 房间状态以后端为准：这里只把收到的状态映射成本地的 Session，发言、跳过都交给后端判断（约定 9）。

/** 公开的网页版地址：邀请链接用它，同学没装 App 也能在浏览器里打开加入。 */
const val INVITE_BASE = "https://maxlen727.github.io/debating-ground/app/"

/** 快捷短语：只是提醒，不进讨论记录，也不发给 AI。 */
val QUICK_MESSAGES = listOf("等一等", "我在想", "请继续", "快点啊")

@Serializable
data class RoomMember(
    val id: String,
    val name: String = "",
    val host: Boolean = false,
    val online: Boolean = false,
    val seat: String? = null,
    val typing: Boolean? = null,
)

/** 当前该谁说：成员或 AI 嘉宾（圆桌）。 */
@Serializable
data class Speaker(val kind: String, val id: String)

/** 房主创建房间时提交的内容（来自准备页）。 */
@Serializable
data class RoomSetup(
    val mode: String,
    val concept: String,
    val topic: String,
    val keyPoints: List<String> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val opening: String = "",
    val seats: List<Seat> = emptyList(),
    val stages: List<Stage> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val model: String? = null,
    val turnSeconds: Int? = null,
    val context: String? = null,
)

/** 后端发来的房间状态。 */
@Serializable
data class RoomView(
    val id: String,
    val status: String = "lobby",
    val setup: RoomSetup,
    val members: List<RoomMember> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val covered: List<Int> = emptyList(),
    val cursor: Int = 0,
    val speaker: Speaker? = null,
    val aiBusy: String? = null,
    val error: String = "",
    val aiCalls: Int = 0,
    val expiresAt: Long = 0,
    val now: Long? = null,
    val turnStartedAt: Long? = null,
    val voiceAvailable: Boolean? = null,
    val listenAvailable: Boolean? = null,
    val aiPaused: Boolean? = null,
    val proposals: List<PointProposal>? = null,
)

/** 自己写的小结：只留在这台设备上，插在 [after] 那条发言后面显示。 */
@Serializable
data class RoomReflection(
    val id: String,
    val kind: String = "reflect",
    val text: String = "",
    val feedback: String = "",
    val draft: String = "",
    val after: String = "",
)

/**
 * 这台设备和房间的关系（存在 Session.room 里，和网页版存档的格式一样）。凭据（token、invite）只在这里，
 * 不进讨论记录、知识库和导出（约定 1、9）。
 */
@Serializable
data class RoomLink(
    val backend: String,
    val roomId: String,
    val token: String = "",
    val invite: String = "",
    val memberId: String = "",
    val host: Boolean = false,
    val status: String = "lobby",
    val members: List<RoomMember> = emptyList(),
    val speaker: Speaker? = null,
    val aiBusy: String? = null,
    val error: String = "",
    val expiresAt: Long = 0,
    val turnSeconds: Int = 0,
    val turnDeadline: Long = 0,
    val voiceAvailable: Boolean? = null,
    val listenAvailable: Boolean? = null,
    val aiPaused: Boolean? = null,
    val connected: Boolean = false,
    val closed: String = "",
    val reflections: List<RoomReflection> = emptyList(),
)

/** 这场讨论连着的房间（不是多人讨论时为 null）。 */
val Session.link: RoomLink?
    get() = room?.let { runCatching { SibianJson.decodeFromJsonElement(RoomLink.serializer(), it) }.getOrNull() }

fun Session.withLink(l: RoomLink): Session = copy(room = SibianJson.encodeToJsonElement(RoomLink.serializer(), l))

/** 把房间状态映射成本地的讨论。[now] 是这台设备的时钟。 */
fun toSession(view: RoomView, prev: Session?, base: RoomLink, now: Long): Session {
    val me = base.memberId
    val same = if (prev?.link?.roomId == view.id) prev else null
    // 只有自己的发言算“我”的；同学的发言保留 memberId，界面上显示为同学
    val entries = view.entries.map { e -> if (e is ChatMessage) e.copy(human = e.memberId == me && e.skipped != true) else e }.toMutableList<Entry>()
    for (r in base.reflections) {
        val at = entries.indexOfFirst { it.id == r.after }
        entries.add(if (at < 0) entries.size else at + 1, Reflection(r.id, r.text, r.feedback, r.draft))
    }
    val setup = view.setup
    return Session(
        id = "room-" + view.id,
        created = same?.created ?: now,
        mode = setup.mode,
        concept = setup.concept,
        topic = setup.topic,
        keyPoints = setup.keyPoints,
        covered = view.covered,
        entries = entries,
        learnerTurns = view.entries.count { it is ChatMessage && it.memberId == me && it.skipped != true },
        finished = view.status == "finished",
        draft = same?.draft ?: "",
        personas = setup.personas,
        seats = setup.seats,
        stages = setup.stages,
        turns = setup.turns,
        cursor = view.cursor,
        review = same?.review,
        glossary = same?.glossary,
        proposedPoints = view.proposals ?: emptyList(),
        context = setup.context,
    ).withLink(
        base.copy(
            status = view.status,
            members = view.members,
            speaker = view.speaker,
            aiBusy = view.aiBusy,
            error = view.error,
            expiresAt = view.expiresAt,
            turnSeconds = setup.turnSeconds ?: 0,
            turnDeadline = deadlineOf(view, now),
            voiceAvailable = view.voiceAvailable == true,
            listenAvailable = view.listenAvailable == true,
            aiPaused = view.aiPaused == true,
        ),
    )
}

/** 倒计时的截止时间换算成这台设备的时钟：后端时间和手机时间可能差好几秒甚至几分钟。0 表示不计时。 */
fun deadlineOf(view: RoomView, localNow: Long): Long {
    val seconds = view.setup.turnSeconds ?: 0
    if (seconds == 0 || view.turnStartedAt == null || view.turnStartedAt == 0L || view.status != "live" || view.now == null) return 0
    return localNow + (view.turnStartedAt + seconds * 1000L - view.now)
}

/** 现在能不能由我发言。 */
fun mySpeakingTurn(s: Session?): Boolean {
    val room = s?.link ?: return false
    if (room.status != "live") return false
    if (s.mode == "roundtable") return true // 圆桌是群聊，随时可以说，AI 说着话也能插嘴
    if (room.aiBusy != null || room.error.isNotEmpty()) return false
    val t = s.turns.getOrNull(s.cursor) ?: return false
    val mine = room.members.find { it.id == room.memberId }?.seat ?: return false
    return t.role.id == mine
}

/** 辩论席位现在由谁坐：没人坐的席位由 AI 担任。 */
fun seatOwner(s: Session, seat: String) = s.link?.members?.find { it.seat == seat }

fun mySeat(s: Session) = s.link?.let { r -> r.members.find { it.id == r.memberId }?.seat }

fun speakerLabel(s: Session): String {
    val room = s.link ?: return ""
    if (room.aiBusy != null) return room.aiBusy
    if (s.mode == "debate") {
        val t = s.turns.getOrNull(s.cursor) ?: return ""
        val owner = room.members.find { it.seat == t.role.id }
        val who = when {
            owner != null -> if (owner.id == room.memberId) "你" else owner.name
            t.role.type == "ai" -> "AI"
            else -> "等同学入座"
        }
        return "${seatName(t.role)}（$who）· ${t.action}"
    }
    val sp = room.speaker ?: return ""
    if (sp.kind == "ai") return s.personas.find { it.id == sp.id }?.name ?: "AI 嘉宾"
    return if (sp.id == room.memberId) "你" else room.members.find { it.id == sp.id }?.name ?: "同学"
}

/* ---------- 邀请链接 ---------- */

/** 加入一个房间要的东西：房主的后端、房间号和邀请码。 */
data class JoinTarget(val backend: String, val roomId: String, val invite: String)

// 邀请码放在 # 后面：浏览器不会把它发给托管界面的网站
private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

fun inviteLink(backend: String, roomId: String, invite: String, base: String = INVITE_BASE): String {
    val json = buildJsonObject {
        put("b", backend)
        put("r", roomId)
        put("c", invite)
    }.toString()
    return base + "#join=" + Base64.encodeToString(json.toByteArray(Charsets.UTF_8), B64)
}

/**
 * 从一段文字里找出邀请链接（聊天软件分享过来的常常前后带着别的话，例如“小明邀请你一起讨论：https://…#join=… 点开就能加入”）。
 * 没有时返回 null。
 */
fun inviteIn(text: String): String? = INVITE_IN_TEXT.find(text)?.value

// 链接从 http(s):// 开始，只取网址里能出现的字符（不会把前面紧挨着的“讨论：”算进来）；没有网址时也认单独的 #join=…
private val INVITE_IN_TEXT = Regex("(https?://[A-Za-z0-9._~:/?@!$&'()*+,;=%-]*)?#join=[A-Za-z0-9_-]+")

/** 读邀请：可以是完整的邀请链接，也可以只是 # 后面那一段。不完整或地址不安全时返回 null。 */
fun parseInvite(raw: String): JoinTarget? {
    val hash = raw.trim().let {
        val i = it.indexOf("#join=")
        if (i < 0) it else it.substring(i)
    }
    val m = Regex("^#join=([A-Za-z0-9_-]+)$").find(hash) ?: return null
    return runCatching {
        val v = SibianJson.parseToJsonElement(String(Base64.decode(m.groupValues[1], B64), Charsets.UTF_8)) as JsonObject
        fun str(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val u = URI(str("b") ?: return null)
        val host = u.host ?: return null
        val local = host in setOf("localhost", "127.0.0.1", "[::1]")
        // 局域网房间（#148）：房主手机或电脑在同一个 Wi-Fi 里，地址是私有 IPv4 上的 http
        if (u.scheme != "https" && !(u.scheme == "http" && (local || isPrivateIPv4(host)))) return null
        val roomId = str("r") ?: return null
        val invite = str("c") ?: return null
        val port = if (u.port == -1) "" else ":${u.port}"
        JoinTarget("${u.scheme}://$host$port${(u.rawPath ?: "").trimEnd('/')}", roomId, invite)
    }.getOrNull()
}

/** 局域网（私有）IPv4 地址：10/8、172.16/12、192.168/16。和 shared/room.ts 的 isPrivateIPv4 一样。 */
fun isPrivateIPv4(ip: String): Boolean {
    val p = ip.split('.')
    if (p.size != 4 || p.any { !Regex("^\\d{1,3}$").matches(it) || it.toInt() > 255 }) return false
    val a = p[0].toInt()
    val b = p[1].toInt()
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

/** 局域网房间的后端地址（http 加私有地址）。 */
fun isLanBackend(backend: String): Boolean = runCatching {
    val u = URI(backend)
    u.scheme == "http" && isPrivateIPv4(u.host.orEmpty())
}.getOrDefault(false)

/* ---------- 房主创建房间 ---------- */

private val TURN_SECONDS = setOf(0, 30, 60, 90, 120)

fun setupFrom(plan: Plan, config: Config): RoomSetup {
    val keyPoints = plan.keyPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(6)
    val turnSeconds = if (config.turnSeconds in TURN_SECONDS) config.turnSeconds else 0
    val context = plan.context?.trim()?.ifEmpty { null }
    if (plan.mode == "debate") {
        return RoomSetup(
            mode = "debate",
            concept = plan.concept,
            topic = plan.topic.trim(),
            keyPoints = keyPoints,
            seats = slots(config).map { it.copy(name = seatName(it)) },
            stages = config.stages,
            turns = debateTurns(config),
            model = plan.model,
            turnSeconds = turnSeconds,
            context = context,
        )
    }
    return RoomSetup(
        mode = "roundtable",
        concept = plan.concept,
        topic = plan.topic.trim(),
        keyPoints = keyPoints,
        personas = plan.personas,
        opening = plan.opening,
        model = plan.model,
        turnSeconds = turnSeconds,
        context = context,
    )
}

/** 房间创建成功后后端返回的。 */
@Serializable
data class CreatedRoom(val roomId: String, val invite: String, val hostToken: String, val expiresAt: Long = 0)
