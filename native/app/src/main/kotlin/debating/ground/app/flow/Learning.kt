package debating.ground.app.flow

import debating.ground.app.ai.Ai
import debating.ground.app.ai.AiFailure
import debating.ground.app.ai.AiOutcome
import debating.ground.app.ai.DirectSettings
import debating.ground.app.ai.LinkFetcher
import debating.ground.app.ai.LinkPage
import debating.ground.app.ai.PdfImporter
import debating.ground.app.ai.PdfPage
import debating.ground.app.ai.checkRemoteUrl
import debating.ground.app.ai.linkPageOf
import debating.ground.app.ai.linkReasonOf
import debating.ground.app.ai.pdfPageOf
import debating.ground.app.data.Branch
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Config
import debating.ground.app.data.Entry
import debating.ground.app.data.MODE_ORDER
import debating.ground.app.data.Material
import debating.ground.app.data.MaterialMeta
import debating.ground.app.data.MaterialStore
import debating.ground.app.data.MemoryMaterials
import debating.ground.app.data.MergeFrom
import debating.ground.app.data.Misconception
import debating.ground.app.data.Note
import debating.ground.app.data.Persona
import debating.ground.app.data.Plan
import debating.ground.app.data.Point
import debating.ground.app.data.PointProposal
import debating.ground.app.data.Previous
import debating.ground.app.data.ReaderLimits
import debating.ground.app.data.Reflection
import debating.ground.app.data.Remote
import debating.ground.app.data.SavedState
import debating.ground.app.data.SeatConfig
import debating.ground.app.data.Session
import debating.ground.app.data.SessionMaterial
import debating.ground.app.data.SessionReview
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Stage
import debating.ground.app.data.Stance
import debating.ground.app.data.SystemLine
import debating.ground.app.data.TermCard
import debating.ground.app.data.branchById
import debating.ground.app.data.canBranch
import debating.ground.app.data.checkPoint
import debating.ground.app.data.contextQuery
import debating.ground.app.data.emptyNote
import debating.ground.app.data.isMaterialNote
import debating.ground.app.data.isNoteFor
import debating.ground.app.data.jsTrimmed
import debating.ground.app.data.materialContext
import debating.ground.app.data.mergeIntoMaterial
import debating.ground.app.data.mergeReview
import debating.ground.app.data.splitMaterial
import debating.ground.app.data.startContext
import debating.ground.app.data.weak
import debating.ground.app.lan.LAN_PORT
import debating.ground.app.lan.LanRooms
import debating.ground.app.lan.randomRoomId
import debating.ground.app.room.JoinTarget
import debating.ground.app.room.RoomDialer
import debating.ground.app.room.RoomLink
import debating.ground.app.room.RoomView
import debating.ground.app.room.link
import debating.ground.app.room.mySpeakingTurn
import debating.ground.app.room.setupFrom
import debating.ground.app.room.toSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// 学习流程的所有操作：准备 → 讨论 → 小结 → 复盘 → 知识库。和网页版 src/actions.ts 的单人部分一一对应
// （FlowTest 按网页版跑出来的剧本逐步比对状态）。界面只负责展示和调用这里的操作。

/** 界面上的状态：在哪一页、开着什么、正在做什么。和网页版 store.ts 的 Ui 对应（只取单人流程用到的部分）。 */
data class Ui(
    val screen: String = "learn",
    val busy: String? = null,
    val error: String = "",
    val toast: String = "",
    val hint: String? = null,
    val explain: Explain? = null,
    val sheet: String? = null,
    val openCard: String? = null,
    val term: TermView? = null,
    val context: ContextAsk? = null,
    // 自己部署的后端：这次打开 App 以后有没有测试通过、用的是什么模型。不存档（和网页版 ai.remoteVerified 一样）
    val remote: RemoteStatus = RemoteStatus(),
    // 直接连接：存了哪个接口和模型、这次打开 App 以后有没有测试通过。API Key 不在这里（只在加密文件和内存里）
    val direct: DirectStatus = DirectStatus(),
    // 检验题选了哪个选项：键是 "s:讨论 id" 或 "c:卡片 id"，值是题号 → 选项。不存档
    val quiz: Map<String, Map<Int, Int>> = emptyMap(),
    // 多人讨论：正要加入的房间（打开邀请链接后，写名字之前）、邀请面板开着没有。不存档
    val join: JoinTarget? = null,
    val invite: Boolean = false,
    // 读材料（#144、#154、#166）：正在看的材料、材料列表。不存档（材料本身在 MaterialStore 里）
    val material: Material? = null,
    val materials: List<MaterialMeta> = emptyList(),
)

/** 朗读能不能用：连着自己部署的后端，而且后端配好了讯飞（演示模式和直接连接都没有）。 */
fun AppState.voiceReady() = config.aiMode == "remote" && ui.remote.tts

/** 语音输入能不能用：同上，看后端的语音听写。 */
fun AppState.listenReady() = config.aiMode == "remote" && ui.remote.stt

/** 自己部署的后端：测试过没有、用的模型、讯飞朗读和语音输入配好了没有（GET /api/config，不产生费用）。 */
data class RemoteStatus(val verified: Boolean = false, val model: String = "", val tts: Boolean = false, val stt: Boolean = false)

data class DirectStatus(val saved: Boolean = false, val baseURL: String = "", val model: String = "", val verified: Boolean = false) {
    companion object {
        fun of(s: DirectSettings?) = if (s == null) DirectStatus() else DirectStatus(true, s.baseURL, s.model)
    }
}

data class Explain(val kind: String, val label: String, val quote: String, val text: String)

data class TermView(val term: String, val definition: String, val relation: String, val messageId: String)

/** 准备时 AI 请学习者交代背景（具体事件类的话题）。 */
data class ContextAsk(val ask: String, val text: String)

data class AppState(
    val config: Config = Config(),
    val plan: Plan? = null,
    val session: Session? = null,
    val library: List<Note> = emptyList(),
    val ui: Ui = Ui(),
) {
    fun saved() = SavedState(config, plan, session, library, ui.screen)
}

/** 正在生成的文字（边生成边显示）。不放进状态：它变得很快，半截文字也不该被保存。 */
data class Live(val id: String, val kind: String, val label: String, val text: String, val who: String? = null)

const val MAX_POINTS = 6
private const val CONNECT_TIMEOUT_MS = 100_000L

private const val BRANCH_TOPIC_MAX = 40
private val TONES = listOf("c", "a", "b")

private val PARENT = mapOf(
    "prepare" to "learn",
    "session" to "learn",
    "review" to "session",
    "card" to "library",
    "library" to "learn",
    "material" to "learn",
    "settings" to "learn",
    "join" to "learn",
)

data class Quick(val kind: String, val label: String)

val QUICK = listOf(Quick("unclear", "没听懂"), Quick("example", "举个例子"), Quick("disagree", "我不同意"), Quick("fact", "这是事实还是观点？"))

/** 讲给 AI 听里的学生（和网页版 actions.ts 的 TEACH_STUDENT 一样）。 */
val TEACH_STUDENT = Persona(id = "r0", name = "小满", title = "学生", persona = "高二学生，聪明但从没接触过这个概念，爱较真，听不懂就直说", tone = "c")

class Learning(
    initial: AppState,
    private val ai: Ai,
    private val now: () -> Long = System::currentTimeMillis,
    private val uid: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(13) },
    /** 每次状态变化后调用：保存存档、刷新界面。 */
    private val onChange: (AppState) -> Unit = {},
    /** 边生成边显示的文字。 */
    private val onLive: (Live?) -> Unit = {},
    /** 多人讨论的连接（测试里没有）。 */
    roomDialer: RoomDialer? = null,
    /** 在后台跑一件要等的事（房间结束后生成复盘、重连的延时）。 */
    launch: (suspend () -> Unit) -> Unit = {},
    /** 手机当局域网房主（#148；测试里没有，或者换成假的）。 */
    private val lan: LanRooms? = null,
    /** 读材料：完整的材料存在哪里（App 里是私有文件，测试里在内存）。 */
    private val materialStore: MaterialStore = MemoryMaterials(),
    /** 读材料的链接（#157）：手机自己取，规则用共用的 JS。测试里换成假的；没有时（设备不支持 JS 沙箱）读不了链接。 */
    private val links: LinkFetcher? = null,
    /** 读材料的 PDF（#164）：在隐藏的 WebView 里用网页版的代码读。测试里换成假的。 */
    private val pdf: PdfImporter? = null,
) {
    var state: AppState = initial
        private set

    /** 多人讨论。 */
    val rooms = Rooms(this, roomDialer, now, uid, launch)

    internal fun live(x: Live?) = onLive(x)

    internal fun update(fn: (AppState) -> AppState) {
        state = fn(state)
        onChange(state)
    }

    internal fun updateSession(fn: (Session) -> Session) = update { st -> st.session?.let { st.copy(session = fn(it)) } ?: st }

    internal fun ui(fn: (Ui) -> Ui) = update { it.copy(ui = fn(it.ui)) }

    fun toast(text: String) = ui { it.copy(toast = text) }

    private val session get() = state.session

    fun go(screen: String) = ui { it.copy(screen = screen, sheet = null, explain = null) }

    // 调用 AI：界面上显示“正在……”，失败时显示原因，讨论进度不变（约定 2）。
    // 演示模式推进的 demoStep 写回发起请求的那场讨论
    private suspend fun runAI(purpose: String, payload: JsonObject, label: String, live: Live? = null): AiOutcome.Success? {
        if (state.ui.busy != null || ai.busy) return null
        val sessionAtStart = session
        ui { it.copy(busy = label, error = "") }
        // 只有围绕材料的讨论才要先读材料（#166）
        val payload = if (sessionAtStart?.material != null) withMaterial(payload) else payload
        if (live != null) onLive(live)
        val out = try {
            ai.run(purpose, payload, state.config, sessionAtStart, if (live != null) { t -> onLive(live.copy(text = t)) } else null)
        } finally {
            ui { it.copy(busy = null) }
        }
        return when (out) {
            is AiOutcome.Success -> {
                if (out.demoStep != null && sessionAtStart != null) {
                    updateSession { if (it.id == sessionAtStart.id) it.copy(demoStep = out.demoStep) else it }
                }
                out
            }

            is AiOutcome.Failed -> {
                if (live != null) onLive(null)
                ui { it.copy(error = out.message) }
                null
            }

            AiOutcome.Busy -> {
                if (live != null) onLive(null)
                null
            }
        }
    }

    fun cancelAI() {
        ai.cancel()
        rooms.cancelAsk()
        connecting?.cancel()
    }

    /* ---------- 准备 ---------- */

    fun setConcept(concept: String) = update { it.copy(config = it.config.copy(concept = concept), ui = it.ui.copy(context = null)) }

    fun setMode(mode: String) = update { it.copy(config = it.config.copy(mode = mode)) }

    fun editConfig(fn: (Config) -> Config) = update { it.copy(config = fn(it.config)) }

    /** 辩论一方的人数：新席位用这次按论题生成的背景，没有（演示或旧后端）才用通用的一句。和网页版 Seats 的 setCount 一致。 */
    fun setTeamCount(team: String, n: Int) = update { st ->
        val c = st.config
        val generated = st.plan?.debaters?.get(team).orEmpty()
        val list = c.seats[team].orEmpty().toMutableList()
        while (list.size < n) list += SeatConfig("ai", generated.getOrNull(list.size) ?: "从实际案例出发，关注论证的适用条件。")
        for (i in 0 until n) if (list[i].bg.isBlank()) generated.getOrNull(i)?.let { list[i] = list[i].copy(bg = it) }
        st.copy(config = c.copy(counts = c.counts + (team to n), seats = c.seats + (team to list)))
    }

    /** 辩论的比赛流程：最多 6 个环节，至少 1 个。 */
    fun editStages(fn: (List<Stage>) -> List<Stage>) = editConfig { c -> fn(c.stages).takeIf { it.size in 1..6 }?.let { c.copy(stages = it) } ?: c }

    fun setContextDraft(text: String) = ui { u -> u.context?.let { u.copy(context = it.copy(text = text)) } ?: u }

    /** [then]："start" 是“一句话开始”，准备好直接进讨论；"review" 先停在准备页看看、改改题目和角色。 */
    suspend fun prepare(previous: Previous? = null, knownContext: String? = null, then: String = "review", material: SessionMaterial? = null) {
        val (config, plan, _, _, ui) = state
        val concept = config.concept.trim()
        if (concept.isEmpty()) return toast("先写下你想弄懂的概念。")
        // AI 问过背景就带上学习者写的；在准备页点“重新生成”时沿用已经交代的背景
        val raw = ui.context?.text ?: knownContext ?: (if (plan?.concept == concept) plan.context else "")
        val context = raw?.trim()?.take(600)?.takeIf { it.isNotEmpty() }
        if (ui.context != null && context == null) return toast("写一两句经过，AI 才知道你们在讨论什么。")
        val payload = buildJsonObject {
            put("concept", concept)
            put("mode", config.mode)
            put("previous", previous?.let { SibianJson.encodeToJsonElement(Previous.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
            if (context != null) put("context", context)
            if (material != null) put("material", material.forAi())
        }
        val res = runAI("prepare", payload, if (context != null) "正在按你说的经过准备讨论" else "正在拆解要点、准备讨论") ?: return
        val d = res.result.getValue("data") as JsonObject
        // 一件具体的事，AI 不了解经过：先请学习者交代，不凭空出题
        d.str("needsContext")?.takeIf { it.isNotEmpty() }?.let { ask ->
            ui { it.copy(context = ContextAsk(ask, it.context?.text ?: "")) }
            return
        }
        val topic = d.str("topic").orEmpty()
        val debaters = d["debaters"]?.let {
            runCatching { SibianJson.decodeFromJsonElement(MapSerializer(String.serializer(), ListSerializer(String.serializer())), it) }.getOrNull()
        }
        update { st ->
            st.copy(
                plan = Plan(
                    mode = config.mode,
                    concept = d.str("concept")?.takeIf { it.isNotEmpty() } ?: concept,
                    topic = topic,
                    keyPoints = d.strings("keyPoints"),
                    opening = d.str("opening").orEmpty(),
                    personas = personasOf(d["roles"]),
                    source = res.source,
                    model = res.result.str("model"),
                    previous = previous,
                    context = context,
                    debaters = debaters,
                    rolesFor = topic,
                    material = material,
                ),
                config = applyDebaters(st.config, debaters),
                ui = st.ui.copy(context = null, screen = if (then == "review") "prepare" else st.ui.screen),
            )
        }
        if (then != "start") return
        // 准备出来的东西有问题（比如辩论里没有“我”的席位）：停在准备页，说清楚哪里要改
        val problem = planProblem()
        if (problem.isNotEmpty()) {
            toast(problem)
            return go("prepare")
        }
        startSession()
    }

    private fun personasOf(roles: JsonElement?) = (roles as? JsonArray).orEmpty().mapIndexed { i, r ->
        val o = r as JsonObject
        Persona(id = "r$i", tone = TONES[i % 3], name = o.str("name").orEmpty(), title = o.str("title").orEmpty(), persona = o.str("persona").orEmpty())
    }

    // 辩手背景跟着这次的论题走：每次准备都覆盖，之前手动改过的不会带到下一场
    private fun applyDebaters(config: Config, debaters: Map<String, List<String>>?): Config {
        if (debaters == null) return config
        val seats = config.seats.toMutableMap()
        for (t in listOf("A", "B")) {
            val list = seats[t].orEmpty().toMutableList()
            debaters[t].orEmpty().forEachIndexed { i, bg ->
                val seat = SeatConfig(list.getOrNull(i)?.type ?: "ai", bg)
                if (i < list.size) list[i] = seat else list.add(seat)
            }
            seats[t] = list
        }
        return config.copy(seats = seats)
    }

    /** 准备页改了讨论题：题目和要点不动，只让角色和开场白按新题目重新设定。 */
    suspend fun refreshRoles() {
        val plan = state.plan ?: return
        if (state.ui.busy != null) return
        val topic = plan.topic.trim()
        if (topic.isEmpty()) return toast("先写好讨论题。")
        val payload = buildJsonObject {
            put("concept", plan.concept)
            put("mode", plan.mode)
            put("previous", plan.previous?.let { SibianJson.encodeToJsonElement(Previous.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
            if (plan.context != null) put("context", plan.context)
            put("topic", topic)
        }
        val res = runAI("prepare", payload, "正在按新题目设定角色") ?: return
        val d = res.result.getValue("data") as JsonObject
        val roles = personasOf(d["roles"])
        val debaters = d["debaters"]?.let {
            runCatching { SibianJson.decodeFromJsonElement(MapSerializer(String.serializer(), ListSerializer(String.serializer())), it) }.getOrNull()
        }
        update { st ->
            var p = st.plan ?: return@update st
            if (p.mode != "debate" && roles.isNotEmpty()) p = p.copy(personas = roles, opening = d.str("opening")?.takeIf { it.isNotEmpty() } ?: p.opening)
            var config = st.config
            if (debaters != null) {
                p = p.copy(debaters = debaters)
                config = applyDebaters(config, debaters)
            }
            st.copy(plan = p.copy(rolesFor = topic), config = config)
        }
    }

    fun editPlan(fn: (Plan) -> Plan) = update { st -> st.plan?.let { st.copy(plan = fn(it)) } ?: st }

    private fun planProblem(multiplayer: Boolean = false): String {
        val plan = state.plan ?: return "还没有准备好讨论。"
        if (plan.topic.trim().isEmpty()) return "请填写讨论题。"
        if (plan.keyPoints.map { it.trim() }.count { it.isNotEmpty() } < 2) return "至少保留两个要点。"
        if (plan.mode == "debate") {
            if (state.config.stages.any { it.name.trim().isEmpty() }) return "请为每个环节填写名称。"
            // 多人辩论里大家在房间里自己选席位，不需要事先安排
            if (!multiplayer && slots(state.config).none { it.type == "human" }) return "至少安排一个“我”的席位，你才能参与交锋。"
        }
        return ""
    }

    /* ---------- 多人讨论：房主开房间 ---------- */

    /**
     * 邀请同学：用准备好的题目和角色在房主的后端开一个房间（只支持圆桌和辩论），进入讨论页并打开邀请面板。
     * 同学凭邀请链接加入，拿不到访问口令（约定 9）。
     */
    suspend fun inviteFriends(rawName: String) {
        val plan = state.plan ?: return
        if (state.ui.busy != null) return
        if (plan.mode != "roundtable" && plan.mode != "debate") return toast("多人讨论支持圆桌讨论和正反辩论。")
        val c = state.config
        // 用“直接连接”时，房间开在这台手机上（局域网房间，#148）；用自己部署的后端时开在后端上
        val lanAddress = if (c.aiMode == "api") lan?.address() else null
        if (c.aiMode == "api" && lan != null && lanAddress == null) return toast("连上 Wi-Fi 才能在这台手机上开局域网房间。同学要和你连同一个 Wi-Fi。")
        if (lanAddress == null && (c.aiMode != "remote" || c.remote.url.isEmpty())) return toast("多人讨论需要先在“设置”里连接你的后端，或者用直接连接、在同一个 Wi-Fi 里开局域网房间。")
        if (!rooms.available) return toast("这个版本不能开多人讨论。")
        val problem = planProblem(true)
        if (problem.isNotEmpty()) return toast(problem)
        val name = rawName.trim()
        if (name.isEmpty()) return toast("写下你在讨论里显示的名字。")
        update { it.copy(config = it.config.copy(name = name)) }
        endOld()
        val setup = setupFrom(plan, state.config)
        ui { it.copy(busy = "正在创建房间", error = "") }
        connecting = currentCoroutineContext()[Job]
        val backend = if (lanAddress != null) "http://$lanAddress:$LAN_PORT" else state.config.remote.url
        val created = try {
            if (lanAddress != null) lan!!.create(randomRoomId(), setup, name) else ai.remote(state.config).createRoom(setup, name)
        } catch (e: CancellationException) {
            ui { it.copy(busy = null) }
            throw e
        } catch (e: AiFailure) {
            // v0.8 之前的后端不认识“同学”席位
            val msg = e.message.orEmpty()
            ui { it.copy(busy = null, error = if (msg.contains("辩论席位无效")) "你的后端版本较旧，不支持“同学”席位。请按文档“怎么更新后端”更新后再试。" else msg) }
            return
        } finally {
            connecting = null
        }
        rooms.leave()
        // 先放一个占位的讨论，连上房间后用后端的状态替换
        val view = RoomView(id = created.roomId, setup = setup, expiresAt = created.expiresAt)
        val base = RoomLink(backend = backend, roomId = created.roomId, token = created.hostToken, invite = created.invite, host = true)
        update { st ->
            st.copy(
                session = toSession(view, null, base, now()),
                ui = st.ui.copy(screen = "session", invite = true, hint = null, explain = null, sheet = null, error = "", busy = null),
            )
        }
        rooms.connect()
    }

    /** 离开房间：这场讨论在这台设备上结束，回学习页（房间里的其他人不受影响）。 */
    fun leaveRoom() {
        rooms.leave()
        update { it.copy(session = null, ui = it.ui.copy(screen = "learn", invite = false, sheet = null)) }
    }

    internal fun endOld() {
        val old = session
        if (old?.room != null) rooms.leave()
        if (old != null && !old.finished && old.entries.any { it is ChatMessage && it.human == true }) toast("上一次讨论没有复盘，已结束。")
    }

    fun startSession() {
        val plan = state.plan ?: return
        val problem = planProblem()
        if (problem.isNotEmpty()) return toast(problem)
        val topic = plan.topic.trim()
        val keyPoints = plan.keyPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        endOld()
        val config = state.config
        var base = Session(
            id = uid(),
            created = now(),
            mode = plan.mode,
            concept = plan.concept,
            topic = topic,
            keyPoints = keyPoints,
            previous = plan.previous,
            context = plan.context,
            material = plan.material,
        )
        base = if (plan.mode == "debate") {
            base.copy(
                seats = slots(config).map { it.copy(name = seatName(it)) },
                stages = config.stages,
                turns = debateTurns(config),
                entries = listOf(SystemLine(uid(), "辩题：$topic")),
            )
        } else {
            val first = plan.personas.first()
            base.copy(
                personas = plan.personas,
                entries = listOf(
                    SystemLine(uid(), "讨论题：$topic"),
                    ChatMessage(
                        id = uid(),
                        who = first.id,
                        name = first.name,
                        text = plan.opening.ifEmpty { "我们开始吧。你怎么理解这个概念？" },
                        source = plan.source,
                        model = plan.model,
                    ),
                ),
                nextRole = if (plan.personas.size > 1) 1 else 0,
            )
        }
        update { it.copy(session = base, ui = it.ui.copy(screen = "session", hint = null, explain = null, sheet = null, error = "")) }
    }

    /* ---------- 讨论 ---------- */

    fun setDraft(text: String) {
        updateSession { it.copy(draft = text) }
        rooms.noteTyping()
    }

    private fun markCovered(s: Session, list: List<Int>): Session {
        val covered = s.covered.toMutableList()
        for (i in list) if (i >= 0 && i < s.keyPoints.size && i !in covered) covered.add(i)
        return s.copy(covered = covered)
    }

    fun currentTurn(s: Session? = session) = if (s != null && s.mode == "debate" && !s.finished) s.turns.getOrNull(s.cursor) else null

    private fun pushLearner(text: String) = update { st ->
        val s = st.session!!
        st.copy(
            session = s.copy(
                entries = s.entries + ChatMessage(
                    id = uid(),
                    who = "learner",
                    name = "学习者",
                    human = true,
                    text = text,
                    source = "human",
                    branch = s.at,
                ),
                learnerTurns = s.learnerTurns + 1,
                draft = "",
            ),
            ui = st.ui.copy(hint = null),
        )
    }

    suspend fun send(raw: String) {
        val s = session
        val text = raw.trim()
        if (s == null || s.finished || state.ui.busy != null) return
        if (text.isEmpty()) return toast("先写下你的想法，再发送。")
        if (s.link != null) {
            if (!mySpeakingTurn(s)) return toast("还没轮到你发言。")
            if (rooms.say(text)) {
                update { it.copy(session = it.session!!.copy(draft = ""), ui = it.ui.copy(hint = null)) }
                rooms.stopTyping()
            }
            return
        }
        if (s.mode == "debate") return submitSeat(text)
        pushLearner(text)
        // 分支里由展开它的那位接着单独聊，不轮流
        if (s.at != null) {
            speak(branchSpeaker(s).id)
            return maybeReflect()
        }
        val role = s.personas[s.nextRole % s.personas.size]
        if (s.personas.size > 1) updateSession { it.copy(nextRole = (s.nextRole + 1) % s.personas.size) }
        speak(role.id)
        maybeReflect()
    }

    suspend fun speak(roleId: String, task: String? = null) {
        val s = session ?: return
        val role = s.personas.first { it.id == roleId }
        val id = uid()
        val at = s.at // 生成期间换了分支，回复仍然放回提问的那个分支
        val payload = buildJsonObject {
            put("session", snapshot(s))
            put("roleId", roleId)
            if (task != null) put("task", task)
        }
        val res = runAI("discuss", payload, "${role.name} 正在思考", Live(id, "msg", "${role.name} 正在思考", "", role.id))
        if (res == null || session?.id != s.id) return onLive(null)
        val terms = res.result.strings("terms")
        updateSession { ss ->
            markCovered(
                ss.copy(
                    entries = ss.entries + ChatMessage(
                        id = id,
                        who = role.id,
                        name = role.name,
                        text = res.result.str("text").orEmpty(),
                        source = res.source,
                        model = res.result.str("model"),
                        terms = terms.ifEmpty { null },
                        branch = at,
                    ),
                ),
                res.result.ints("covered"),
            )
        }
        onLive(null)
    }

    /** 圆桌：请下一位嘉宾接着上一位的话说。 */
    suspend fun nextSpeaker() {
        val s = session ?: return
        // 分支里由展开它的那位单独聊，不请下一位
        if (s.at != null || state.ui.busy != null) return
        val role = s.personas[s.nextRole % s.personas.size]
        updateSession { it.copy(nextRole = (s.nextRole + 1) % s.personas.size) }
        // 请下一位嘉宾：接上一位的话说，不再向学习者提问（否则问题会一个接一个叠起来）
        speak(role.id, "主持人请你接着上一位嘉宾的话说：回应、补充或反驳他。")
    }

    fun submitSeat(text: String, skipped: Boolean = false) {
        val s = session ?: return
        val t = currentTurn(s) ?: return
        if (s.link != null) {
            if (skipped) rooms.pass() else rooms.say(text)
            return
        }
        val human = t.role.type == "human"
        update { st ->
            val ss = st.session!!
            st.copy(
                session = ss.copy(
                    entries = ss.entries + ChatMessage(
                        id = uid(),
                        who = t.role.id,
                        name = seatName(t.role) + if (human) "（学习者）" else "",
                        team = t.role.team,
                        human = human && !skipped,
                        text = text,
                        stage = t.stage,
                        action = t.action,
                        target = t.target,
                        skipped = skipped,
                        source = if (human) "human" else "demo",
                    ),
                    cursor = ss.cursor + 1,
                    draft = "",
                    learnerTurns = ss.learnerTurns + if (human && !skipped) 1 else 0,
                ),
                ui = st.ui.copy(hint = null),
            )
        }
        if (session!!.cursor >= s.turns.size) return updateSession { it.copy(awaitingReview = true) }
        maybeReflect()
    }

    fun skipSeat() = submitSeat("主席宣布：本席放弃本次发言。", true)

    /** 轮到 AI 的席位：生成这一席的发言。 */
    suspend fun generateSeat() {
        val s = session ?: return
        val t = currentTurn(s) ?: return
        if (state.ui.busy != null) return
        if (s.link != null) {
            rooms.aiTakeover()
            return
        }
        val id = uid()
        val label = "${seatName(t.role)}正在组织发言"
        val res = runAI("debate", buildJsonObject { put("session", snapshot(s)) }, label, Live(id, "msg", label, "", t.role.id))
        val nowS = session
        if (res == null || nowS?.id != s.id || nowS.cursor != s.cursor || nowS.finished) return onLive(null)
        val terms = res.result.strings("terms")
        updateSession { ss ->
            markCovered(
                ss.copy(
                    entries = ss.entries + ChatMessage(
                        id = id,
                        who = t.role.id,
                        name = seatName(t.role),
                        team = t.role.team,
                        text = res.result.str("text").orEmpty(),
                        stage = t.stage,
                        action = t.action,
                        target = t.target,
                        source = res.source,
                        model = res.result.str("model"),
                        terms = terms.ifEmpty { null },
                    ),
                ),
                res.result.ints("covered"),
            ).let { it.copy(cursor = it.cursor + 1) }
        }
        onLive(null)
        if (session!!.cursor >= s.turns.size) updateSession { it.copy(awaitingReview = true) }
    }

    /* ---------- 快捷追问、提示、小结 ---------- */

    fun openSheet(id: String?) = ui { it.copy(sheet = id) }

    suspend fun quick(kind: String, messageId: String) {
        val s = session ?: return
        if (state.ui.busy != null) return
        val m = s.entries.find { it.id == messageId } as? ChatMessage ?: return
        ui { it.copy(sheet = null) }
        val quote = if (m.text.length > 26) m.text.take(26) + "…" else m.text
        if (kind == "disagree") {
            if (s.link != null && !mySpeakingTurn(s)) return toast("轮到你发言时，再在发言里回应这一点。")
            if (s.link == null && s.mode == "debate" && currentTurn(s)?.role?.type != "human") return toast("轮到你的席位时，再在发言里反驳这一点。")
            updateSession { it.copy(draft = "我不同意「$quote」，因为") }
            return
        }
        if (s.link != null) {
            // 多人讨论不打断别人的轮次：解释只给自己看
            val label = QUICK.first { it.kind == kind }.label
            ui { it.copy(explain = Explain(kind, label, quote, "")) }
            val body = buildJsonObject {
                put("purpose", "explain")
                put("entryId", m.id)
                put("kind", kind)
            }
            val res = rooms.ask(body, "讲解助手正在看这句话")
            ui { u ->
                if (res != null &&
                    u.explain != null
                ) {
                    u.copy(explain = u.explain.copy(text = res.result.str("text").orEmpty()))
                } else {
                    u.copy(explain = null)
                }
            }
            return
        }
        if (s.mode != "debate") {
            val text = when (kind) {
                "unclear" -> "「$quote」这句我没听懂，能换个说法吗？"
                "example" -> "能针对「$quote」举一个具体例子吗？"
                else -> "「$quote」这是事实还是观点？依据是什么？"
            }
            pushLearner(text)
            val role = s.personas.find { it.id == m.who } ?: s.personas.first()
            speak(role.id)
            maybeReflect()
            return
        }
        // 辩论模式不打断赛程：解释放在一旁，不进入比赛记录（约定 5）
        val label = QUICK.first { it.kind == kind }.label
        ui { it.copy(explain = Explain(kind, label, quote, "")) }
        val payload = buildJsonObject {
            put("session", snapshot(s))
            put("quote", m.text.take(4000))
            put("kind", kind)
        }
        val res = runAI("explain", payload, "讲解助手正在看这句话", Live("explain", "explain", "讲解助手正在看这句话", ""))
        ui { u -> if (res != null && u.explain != null) u.copy(explain = u.explain.copy(text = res.result.str("text").orEmpty())) else u.copy(explain = null) }
        onLive(null)
    }

    fun closeExplain() = ui { it.copy(explain = null) }

    fun closeHint() = ui { it.copy(hint = null) }

    fun proposePoint(raw: String, source: String = "human"): Boolean {
        val s = session
        val text = raw.trim().take(40)
        if (s == null || text.isEmpty()) return false
        if (s.link != null) return rooms.propose(text, source)
        val pending = s.proposedPoints.orEmpty()
        if (s.keyPoints.size + pending.size >= MAX_POINTS) {
            toast("讨论要点最多 6 个。")
            return false
        }
        if ((s.keyPoints + pending.map { it.text }).any { it.lowercase() == text.lowercase() }) {
            toast("这条要点已经有了。")
            return false
        }
        // 自己写的直接加入；AI 提议的先放进待确认
        updateSession {
            if (source == "human") {
                it.copy(keyPoints = it.keyPoints + text)
            } else {
                it.copy(proposedPoints = pending + PointProposal(uid(), text, source, "AI", ""))
            }
        }
        return true
    }

    fun decidePoint(id: String, accept: Boolean) {
        val r = session?.link
        if (r != null) {
            if (r.host) rooms.decidePoint(id, accept)
            return
        }
        decideLocally(id, accept)
    }

    private fun decideLocally(id: String, accept: Boolean) = updateSession { ss ->
        val p = ss.proposedPoints?.find { it.id == id } ?: return@updateSession ss
        val rest = ss.proposedPoints.filter { it.id != id }
        ss.copy(proposedPoints = rest, keyPoints = if (accept && ss.keyPoints.size < MAX_POINTS) ss.keyPoints + p.text else ss.keyPoints)
    }

    suspend fun suggestPoint() {
        val s = session ?: return
        if (state.ui.busy != null) return
        if (s.keyPoints.size >= MAX_POINTS) return toast("讨论要点最多 6 个。")
        val label = if (s.mode == "debate") "AI 正在找一个值得交锋的争点" else "AI 正在找一个还没讲到的要点"
        val res =
            (
                if (s.link !=
                    null
                ) {
                    rooms.ask(buildJsonObject { put("purpose", "point") }, label)
                } else {
                    runAI("point", buildJsonObject { put("session", snapshot(s)) }, label)
                }
                )
                ?: return
        (res.result["data"] as? JsonObject)?.str("point")?.takeIf { it.isNotEmpty() }?.let { proposePoint(it, "ai") }
    }

    suspend fun hint() {
        val s = session ?: return
        if (state.ui.busy != null) return
        if (s.link != null) {
            val res = rooms.ask(buildJsonObject { put("purpose", "hint") }, "正在想一条提示")
            if (res != null) ui { it.copy(hint = res.result.str("text")) }
            return
        }
        val t = currentTurn(s)
        val payload = buildJsonObject {
            put("session", snapshot(s))
            put("covered", JsonArray(s.covered.map(::JsonPrimitive)))
            if (t != null) put("task", "${seatName(t.role)}要进行${t.action}")
        }
        val res = runAI("hint", payload, "正在想一条提示", Live("hint", "hint", "正在想一条提示", ""))
        if (res != null) ui { it.copy(hint = res.result.str("text")) }
        onLive(null)
    }

    private fun maybeReflect() {
        val s = session
        if (s != null && !s.finished && s.learnerTurns in setOf(3, 6)) addReflection(true)
    }

    fun addReflection(auto: Boolean = false) {
        val s = session ?: return
        if (s.entries.any { it is Reflection && it.text.isEmpty() && it.branch == s.at }) {
            if (!auto) toast("先完成上面那张小结卡。")
            return
        }
        val card = Reflection(uid(), "", "", "", s.at)
        updateSession { it.copy(entries = it.entries + card) }
        // 多人讨论：小结只留在自己的设备上，记在房间的 reflections 里，跟着房间状态一起显示
        if (s.link != null) rooms.addReflection(card, s.entries.lastOrNull { it !is Reflection }?.id.orEmpty())
    }

    private fun editReflection(id: String, fn: (Reflection) -> Reflection) {
        updateSession { ss -> ss.copy(entries = ss.entries.map { if (it is Reflection && it.id == id) fn(it) else it }) }
        syncRoomReflection(id)
    }

    // 讨论记录里一份，房间的 reflections 里一份：两份一起改
    private fun syncRoomReflection(id: String) {
        val s = session ?: return
        if (s.link == null) return
        val e = s.entries.find { it.id == id } as? Reflection ?: return
        rooms.editReflection(id, e.text, e.feedback, e.draft)
    }

    fun setReflectionDraft(id: String, draft: String) = editReflection(id) { it.copy(draft = draft) }

    fun dropReflection(id: String) {
        updateSession { ss -> ss.copy(entries = ss.entries.filter { it.id != id }) }
        if (session?.link != null) rooms.dropReflection(id)
    }

    suspend fun submitReflection(id: String) {
        val s = session ?: return
        if (state.ui.busy != null) return
        val m = s.entries.find { it.id == id } as? Reflection ?: return
        val answer = m.draft.trim()
        if (answer.isEmpty()) return toast("用一两句话写下你的理解。")
        if (s.link != null) {
            val body = buildJsonObject {
                put("purpose", "reflect")
                put("answer", answer)
            }
            val res = rooms.ask(body, "正在看你的小结") ?: return
            editReflection(id) { it.copy(text = answer, feedback = res.result.str("text").orEmpty()) }
            return
        }
        val payload = buildJsonObject {
            put("session", snapshot(s))
            put("answer", answer)
        }
        val res = runAI("reflect", payload, "正在看你的小结", Live(id, "reflect", "正在看你的小结", "")) ?: return
        updateSession { ss ->
            markCovered(
                ss.copy(
                    entries = ss.entries.map {
                        if (it is Reflection &&
                            it.id == id
                        ) {
                            it.copy(text = answer, feedback = res.result.str("text").orEmpty())
                        } else {
                            it
                        }
                    },
                ),
                res.result.ints("covered"),
            )
        }
        onLive(null)
    }

    /* ---------- 知识画布：追问和圆桌里都能展开分支（#142） ---------- */

    /** 分支里谁来聊：展开它的那句话是哪位说的就是哪位（圆桌里“请这位细说”）；从学习者的话展开的，由第一位接。 */
    fun branchSpeaker(s: Session, at: String? = s.at): Persona {
        val from = s.branchById(at)?.let { b -> s.entries.find { it.id == b.from } } as? ChatMessage
        return s.personas.find { it.id == from?.who } ?: s.personas.first()
    }

    /** 从一条发言展开一个分支，AI 先把这个话题讲个大概。 */
    suspend fun openBranch(fromId: String, rawTopic: String) {
        val s = session
        val topic = rawTopic.trim()
        if (s == null || !s.canBranch() || s.finished || state.ui.busy != null) return
        if (topic.isEmpty()) return toast("写下这个分支想弄懂什么。")
        if (topic.length > BRANCH_TOPIC_MAX) return toast("分支话题写短一点，$BRANCH_TOPIC_MAX 字以内。")
        val from = s.entries.find { it.id == fromId } as? ChatMessage ?: return
        val id = uid()
        update { st ->
            val ss = st.session!!
            st.copy(
                session = ss.copy(branches = ss.branches.orEmpty() + Branch(id, from.branch, fromId, topic, null, now()), at = id, draft = ""),
                ui = st.ui.copy(sheet = null, term = null, hint = null),
            )
        }
        speak(branchSpeaker(session!!, id).id, "学习者从这句话展开了一个分支，想弄懂「$topic」。先用两三句话说说它大概是什么，最好带一个具体例子，再听听他怎么想。")
    }

    /** 跳到某个分支（null 是主干）。 */
    fun goToBranch(at: String?) {
        val s = session ?: return
        if (!s.canBranch() || state.ui.busy != null) return
        if (at != null && s.branches?.none { it.id == at } != false) return
        update { st -> st.copy(session = st.session!!.copy(at = at, draft = ""), ui = st.ui.copy(sheet = null, hint = null)) }
    }

    /** 带回上一层：学习者用一句话说这个分支弄懂了什么，挂在上一层，之后那一层的 AI 能看到。 */
    fun bringBack(rawGist: String) {
        val s = session
        val gist = rawGist.trim()
        val here = s?.branches?.find { it.id == s.at }
        if (s == null || here == null || state.ui.busy != null) return
        if (gist.isEmpty()) return toast("用一句话写下这个分支弄懂了什么，或者先不写，直接回去。")
        val kept = gist.take(300)
        update { st ->
            val ss = st.session!!
            st.copy(
                session = ss.copy(
                    branches = ss.branches!!.map { if (it.id == here.id) it.copy(gist = kept) else it },
                    entries = ss.entries + SystemLine(uid(), kept, here.parent, here.id),
                    at = here.parent,
                    draft = "",
                ),
                ui = st.ui.copy(sheet = null, hint = null),
            )
        }
    }

    /* ---------- 术语小卡片 ---------- */

    /** 讨论里 AI 标出的陌生词：查看释义。查过的词记在这次讨论里，复盘时存进这个概念的笔记。 */
    suspend fun openTerm(term: String, messageId: String) {
        val s = session ?: return
        ui { it.copy(sheet = null) }
        s.glossary?.find { it.term == term }?.let { known ->
            ui { it.copy(term = TermView(known.term, known.definition, known.relation, messageId)) }
            return
        }
        if (state.ui.busy != null) return toast("稍等，上一个请求还没有完成。")
        val m = s.entries.find { it.id == messageId } as? ChatMessage ?: return
        if (m.terms?.contains(term) != true) return
        ui { it.copy(term = TermView(term, "", "", messageId)) }
        val payload = buildJsonObject {
            put("session", snapshot(s))
            put("term", term)
            put("quote", m.text.take(400))
        }
        val res = if (s.link != null) {
            rooms.ask(
                buildJsonObject {
                    put("purpose", "term")
                    put("entryId", messageId)
                    put("term", term)
                },
                "正在查「$term」",
            )
        } else {
            runAI("term", payload, "正在查「$term」")
        }
        update { st ->
            if (res == null) return@update st.copy(ui = st.ui.copy(term = null))
            val d = res.result["data"] as? JsonObject
            val card = TermCard(term, d?.str("definition").orEmpty(), d?.str("relation").orEmpty())
            val ui2 = if (st.ui.term?.term == term) st.ui.copy(term = TermView(term, card.definition, card.relation, messageId)) else st.ui
            val ss = st.session
            if (ss?.id != s.id) return@update st.copy(ui = ui2)
            val glossary = ss.glossary.orEmpty().filter { it.term != term } + card
            // 已经复盘过的讨论，直接补进它所在的笔记（概念笔记，和围绕材料讨论时的材料笔记）
            st.copy(
                ui = ui2,
                session = ss.copy(glossary = glossary),
                library = st.library.map { n ->
                    if (n.sources.any { it.sessionId == ss.id }) n.copy(terms = n.terms.filter { t -> t.term != term } + card) else n
                },
            )
        }
    }

    fun closeTerm() = ui { it.copy(term = null) }

    /* ---------- 复盘与知识库 ---------- */

    suspend fun finish() {
        if (state.ui.busy != null) return
        val r = session?.link
        if (r != null) {
            // 多人讨论由房主结束；房间结束后每个人各自生成复盘
            if (r.host) rooms.finish()
            return
        }
        update { st ->
            st.copy(
                session = st.session!!.copy(finished = true, awaitingReview = false, draft = ""),
                ui = st.ui.copy(hint = null, explain = null, screen = "review"),
            )
        }
        val s = session!!
        if (s.entries.any { (it is ChatMessage && it.human == true) || (it is Reflection && it.text.isNotEmpty()) }) generateReview()
    }

    fun resume() {
        if (session?.link != null) return
        update { st -> st.copy(session = st.session!!.copy(finished = false), ui = st.ui.copy(screen = "session")) }
    }

    /** 复盘只依据学习者本人的发言和小结（约定 4），快照里是整场讨论。 */
    suspend fun generateReview() {
        val s = session ?: return
        val r = s.link
        val res = if (r != null) {
            // 多人讨论：后端只依据我自己的发言和小结来复盘（约定 4、9）
            val body = buildJsonObject {
                put("purpose", "review")
                put("reflections", JsonArray(r.reflections.filter { it.text.isNotEmpty() }.map { JsonPrimitive(it.text) }))
            }
            rooms.ask(body, "正在复盘你在这场讨论里的理解")
        } else {
            runAI("review", buildJsonObject { put("session", snapshot(s, true)) }, "正在复盘整场讨论")
        } ?: return
        if (session?.id != s.id) return
        val d = res.result["data"] as JsonObject
        val review = SibianJson.decodeFromJsonElement(
            SessionReview.serializer(),
            JsonObject(
                d + mapOf("source" to JsonPrimitive(res.source)) + (
                    res.result.str("model")?.let {
                        mapOf("model" to JsonPrimitive(it))
                    } ?: emptyMap()
                    ),
            ),
        )
        // 复盘合并进这个概念的笔记（一个概念一篇，#140）：没有就新建，有就沉淀进去
        update { st ->
            val ss = st.session!!.copy(review = review)
            val at = now()
            val from = MergeFrom(ss.id, at, ss.mode, ss.concept, ss.topic, res.source, review.model, ss.context, ss.glossary, ss.material?.title)
            val i = st.library.indexOfFirst { it.isNoteFor(ss.concept) }
            val base = st.library.getOrNull(i) ?: emptyNote(uid(), ss.concept, at)
            val note = mergeReview(base, review, from)
            // 刚更新的笔记排到最前面
            var library = listOf(note) + st.library.filterIndexed { j, _ -> j != i }
            // 围绕材料的讨论（#166）：也沉淀进这篇材料的笔记，排在概念笔记后面
            ss.material?.let { mat ->
                val k = library.indexOfFirst { it.isMaterialNote() && it.materialId == mat.id }
                val old = library.getOrNull(k) ?: emptyNote(uid(), mat.title, at)
                val merged = mergeIntoMaterial(old, review, from, mat.id)
                library = library.filterIndexed { j, _ -> j != k }.toMutableList().apply { add(1, merged) }
            }
            st.copy(session = ss, library = library)
        }
    }

    /** 这次复盘沉淀进了哪篇笔记（复盘页上给个入口）。 */
    fun settledInto(s: Session): Note? = state.library.find { n -> !n.isMaterialNote() && n.sources.any { it.sessionId == s.id } }

    /** 自检：答出来了，它检验的要点算掌握；没答出，算模糊。 */
    fun selfCheck(noteId: String, index: Int, ok: Boolean) {
        update { st ->
            val i = st.library.indexOfFirst { it.id == noteId }
            val c = st.library.getOrNull(i)?.checks?.getOrNull(index) ?: return@update st
            st.copy(library = st.library.mapIndexed { j, n -> if (j == i) checkPoint(n, c, ok, now()) else n })
        }
        toast(if (ok) "记下了：这一点你能说清楚。" else "记下了：这一点还模糊，可以从薄弱点再讨论一次。")
    }

    /* ---------- 设置：自己部署的后端 ---------- */

    private var connecting: Job? = null

    /**
     * 连接并测试：先保存地址和口令，再读后端状态、让后端调用一次模型。和网页版 connectRemote 一样，
     * 测试通过才切换到“我的后端”；失败时地址和口令仍然保存，显示原因。口令留空表示沿用已保存的。
     */
    suspend fun connectRemote(rawUrl: String, password: String): Boolean {
        if (state.ui.busy != null) return false
        val pw = password.ifEmpty { state.config.remote.password }
        if (rawUrl.isBlank() || pw.isEmpty()) {
            toast("请填写后端地址和访问口令。")
            return false
        }
        val url = try {
            checkRemoteUrl(rawUrl)
        } catch (e: AiFailure) {
            ui { it.copy(error = e.message.orEmpty()) }
            return false
        }
        update { it.copy(config = it.config.copy(remote = Remote(url, pw)), ui = it.ui.copy(busy = "正在连接后端并测试", error = "", remote = RemoteStatus())) }
        connecting = currentCoroutineContext()[Job]
        val error = try {
            val backend = ai.remote(state.config)
            val status = withTimeout(CONNECT_TIMEOUT_MS) {
                backend.status().also { backend.test() }
            }
            update {
                it.copy(
                    config = it.config.copy(aiMode = "remote"),
                    ui = it.ui.copy(busy = null, remote = RemoteStatus(true, status.model, status.ttsConfigured, status.sttConfigured)),
                )
            }
            toast("连接成功，可以开始学习。")
            return true
        } catch (e: TimeoutCancellationException) {
            "后端很久没有回应，请稍后再试。"
        } catch (e: CancellationException) {
            ui { it.copy(busy = null) }
            toast("已取消。")
            throw e
        } catch (e: AiFailure) {
            e.message.orEmpty()
        } finally {
            connecting = null
        }
        ui { it.copy(busy = null, error = error) }
        return false
    }

    /**
     * 打开 App 时问一下后端的状态（和网页版 refreshStatus 一样，不调用模型、不产生费用）：
     * 讯飞配好了没有，决定发言旁有没有“朗读”、输入框旁有没有麦克风。连不上就当没配，不打扰用户。
     */
    suspend fun refreshRemote() {
        val remote = state.config.remote
        if (state.config.aiMode != "remote" || remote.url.isEmpty()) return
        val status = runCatching { ai.remote(state.config).status() }.getOrNull() ?: return
        if (state.config.remote != remote) return
        ui { it.copy(remote = it.remote.copy(model = status.model, tts = status.ttsConfigured, stt = status.sttConfigured)) }
    }

    /** 从这台设备上删除后端地址和访问口令；正在用它的话换回演示模式。 */
    fun forgetRemote() {
        update {
            it.copy(
                config = it.config.copy(remote = Remote(), aiMode = if (it.config.aiMode == "remote") "demo" else it.config.aiMode),
                ui = it.ui.copy(remote = RemoteStatus()),
            )
        }
        toast("已从这台设备上删除后端地址和访问口令。")
    }

    /* ---------- 设置：直接连接服务商 ---------- */

    /**
     * 连接并测试：检查地址、模型和 API Key，加密保存，再调用一次模型。测试通过才切换到“直接连接”；
     * 失败时设置仍然保存，显示原因。[key] 留空表示沿用已保存的。密钥只经过这里，不写进状态、存档或提示。
     */
    suspend fun connectDirect(baseURL: String, model: String, key: String): Boolean {
        val direct = ai.direct
        if (state.ui.busy != null) return false
        if (direct == null) {
            ui { it.copy(error = "这个版本不能直接连接服务商。请改用自己部署的后端，或切换到演示模式。") }
            return false
        }
        if (baseURL.isBlank() || model.isBlank() || (key.isBlank() && direct.settings() == null)) {
            toast("请填写接口地址、模型名称和 API Key。")
            return false
        }
        ui { it.copy(busy = "正在连接服务商并测试", error = "") }
        connecting = currentCoroutineContext()[Job]
        val error = try {
            val m = withTimeout(CONNECT_TIMEOUT_MS) { direct.connect(baseURL, model, key) }
            update {
                it.copy(
                    config = it.config.copy(aiMode = "api"),
                    ui = it.ui.copy(busy = null, direct = DirectStatus.of(direct.settings()).copy(verified = true, model = m)),
                )
            }
            toast("连接成功，可以开始学习。")
            return true
        } catch (e: TimeoutCancellationException) {
            "服务商很久没有回应，请稍后再试。"
        } catch (e: CancellationException) {
            ui { it.copy(busy = null, direct = DirectStatus.of(direct.settings())) }
            toast("已取消。")
            throw e
        } catch (e: AiFailure) {
            e.message.orEmpty()
        } finally {
            connecting = null
        }
        ui { it.copy(busy = null, error = error, direct = DirectStatus.of(direct.settings())) }
        return false
    }

    /** 从这台设备上删除直接连接的设置（包括 API Key）；正在用它的话换回演示模式。 */
    fun forgetDirect() {
        ai.direct?.clear()
        update {
            it.copy(
                config = it.config.copy(aiMode = if (it.config.aiMode == "api") "demo" else it.config.aiMode),
                ui = it.ui.copy(direct = DirectStatus()),
            )
        }
        toast("已从这台设备上删除接口设置和 API Key。")
    }

    /** 再讨论一次：带上上次的一句话和还没弄清的地方，用上次的讨论方式。 */
    suspend fun discussAgain(note: Note) {
        val last = note.sources.lastOrNull()
        // 学习页不再提供的方式（旧的知识画布、讲给 AI 听）换成苏格拉底追问
        val mode = last?.mode?.let { if (it in MODE_ORDER) it else "socratic" }
        update { it.copy(config = it.config.copy(concept = note.concept, mode = mode ?: it.config.mode)) }
        prepare(Previous(last?.oneLine ?: note.summary, note.weak()), note.context)
    }

    /**
     * 讲给 AI 听：AI 扮演刚接触这个概念的学生，学习者来讲。要点清单就是这篇笔记的要点（没掌握的排前面），
     * 不用 AI 准备，演示模式也能用；讲完照常复盘，按要点名合并回这篇笔记，更新它们的状态（#142）。
     */
    /* ---------- 读材料（#144、#154、#166），和网页版 actions.ts 一致 ---------- */

    suspend fun loadMaterials() {
        val list = materialStore.list()
        ui { it.copy(materials = list) }
    }

    suspend fun openMaterials() {
        ui { it.copy(material = null) }
        go("material")
        loadMaterials()
    }

    /**
     * 贴进来的材料（#166）：切成节、存在这台设备上，然后直接围绕它开始讨论。
     * 讨论开始不了（比如 AI 出错）时材料已经存好，停在这篇材料上，可以再点“围绕这篇材料讨论”。
     */
    suspend fun addMaterial(title: String, raw: String, mode: String, source: String? = null) {
        val text = raw.jsTrimmed()
        if (state.ui.busy != null) return
        if (text.length < 200) return toast("材料太短了，至少贴两三段正文。")
        if (text.length > ReaderLimits.TEXT) return toast("材料太长了，先贴最想弄懂的部分（${ReaderLimits.TEXT / 10000} 万字以内）。")
        val m = Material(
            id = uid(),
            title = title.jsTrimmed().take(80).ifEmpty { text.split("\n")[0].jsTrimmed().take(40) },
            sections = splitMaterial(text),
            created = now(),
            source = source?.take(300)?.takeIf { it.isNotEmpty() },
        )
        materialStore.save(m)
        ui { it.copy(material = m) }
        loadMaterials()
        discussMaterial(mode)
    }

    /**
     * 读取一个链接的正文（#157）：取到了交给页面填进框里，学习者核对后再开始研读。取不到或不是全文，都说明原因。
     * 和网页版 fetchLink 一样；区别是手机自己去取，不经过后端，演示模式也能用。
     */
    suspend fun fetchLink(link: String): LinkPage? {
        if (state.ui.busy != null) return null
        if (link.isBlank()) {
            toast("先贴一个链接。")
            return null
        }
        val reader = links ?: run {
            ui { it.copy(error = "这台设备不支持读取链接（需要 Android 8 以上和较新的“Android System WebView”）。可以把正文复制过来贴上。") }
            return null
        }
        ui { it.copy(busy = "正在读取链接", error = "") }
        val r = try {
            reader.read(link.trim())
        } catch (e: AiFailure) {
            ui { it.copy(error = e.message ?: "没能读取这个链接。") }
            return null
        } finally {
            ui { it.copy(busy = null) }
        }
        return linkPageOf(r) ?: run {
            ui { it.copy(error = linkReasonOf(r)) }
            null
        }
    }

    /**
     * 内置浏览器里打开的一页（#160）：学习者自己登录、过了验证以后点“用这一页”，按同样的规则提取正文。
     * 页面内容只在这台手机上处理，和贴进来的正文一样，之后研读时才把要读的几节发给 AI。
     */
    suspend fun usePage(url: String, html: String): LinkPage? {
        if (state.ui.busy != null) return null
        val reader = links ?: run {
            ui { it.copy(error = "这台设备不支持读取网页（需要 Android 8 以上和较新的“Android System WebView”）。可以把正文复制过来贴上。") }
            return null
        }
        ui { it.copy(busy = "正在读这一页", error = "") }
        val r = try {
            reader.fromPage(url, html)
        } catch (e: AiFailure) {
            ui { it.copy(error = e.message ?: "没能从这一页里读出正文。") }
            return null
        } finally {
            ui { it.copy(busy = null) }
        }
        return linkPageOf(r) ?: run {
            ui { it.copy(error = linkReasonOf(r)) }
            null
        }
    }

    /** 读学习者选的 PDF 文件（#164）：只在这台手机上解析，取出的文字交给页面填进框里，核对后再开始研读。 */
    suspend fun importPdf(uri: String): PdfPage? {
        if (state.ui.busy != null) return null
        val reader = pdf ?: run {
            ui { it.copy(error = "这台设备不支持读取 PDF。可以把正文复制过来贴上。") }
            return null
        }
        ui { it.copy(busy = "正在读取 PDF", error = "") }
        val r = try {
            reader.read(uri)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ui { it.copy(error = "这个文件打不开，可能不是 PDF 或者已经损坏。") }
            return null
        } finally {
            ui { it.copy(busy = null) }
        }
        return pdfPageOf(r) ?: run {
            ui { it.copy(error = linkReasonOf(r)) }
            null
        }
    }

    suspend fun openMaterial(id: String) {
        val m = materialStore.get(id) ?: return toast("找不到这份材料，它可能已经被删除。")
        ui { it.copy(material = m) }
        go("material")
    }

    suspend fun removeMaterial(id: String) {
        materialStore.delete(id)
        ui { if (it.material?.id == id) it.copy(material = null) else it }
        loadMaterials()
    }

    /**
     * 围绕当前的材料开始讨论（#166）：带上目录和开头几节去准备，AI 从材料里定出讨论题、核心概念和要点，准备好就开始。
     * 讨论里每次请求 AI 时，再按话题换成最相关的几节（withMaterial）。
     */
    suspend fun discussMaterial(mode: String) {
        val m = state.ui.material ?: return
        if (state.ui.busy != null) return
        val material = SessionMaterial(m.id, m.title, startContext(m.sections))
        // 学习者没写概念：先用材料标题占位，AI 准备时会定出这次讨论的核心概念
        update { it.copy(config = it.config.copy(concept = m.title, mode = mode), ui = it.ui.copy(context = null)) }
        prepare(null, null, "start", material)
    }

    /** 讨论页的“原文”：把这场讨论的材料读进来，打开面板（材料删了就只显示讨论开始时存下的那份）。 */
    suspend fun openMaterialSheet() {
        val id = session?.material?.id ?: return
        val m = state.ui.material?.takeIf { it.id == id } ?: materialStore.get(id)
        ui { it.copy(material = m ?: it.material, sheet = "material") }
    }

    // 围绕材料的讨论（#166）：每次请求 AI 前，按讨论题和最近的发言换成材料里最相关的几节。
    // 完整的材料在这台设备的文件里，读一次留在内存里；找不到（被删了）就用讨论开始时存下的那份
    private var cachedMaterial: Material? = null

    private suspend fun withMaterial(payload: JsonObject): JsonObject {
        val snap = payload["session"] as? JsonObject ?: return payload
        val shown = snap["material"] as? JsonObject ?: return payload
        val id = session?.material?.id ?: return payload
        val m = cachedMaterial?.takeIf { it.id == id } ?: materialStore.get(id) ?: return payload
        cachedMaterial = m
        val excerpt = materialContext(m.sections, contextQuery(snap))
        val material = JsonObject(shown + ("excerpt" to kotlinx.serialization.json.JsonPrimitive(excerpt)))
        return JsonObject(payload + ("session" to JsonObject(snap + ("material" to material))))
    }

    fun teachBack(note: Note) {
        if (state.ui.busy != null) return
        val rank = mapOf("missing" to 0, "partial" to 1, "mastered" to 2)
        val keyPoints = note.points.withIndex()
            .sortedWith(compareBy<IndexedValue<Point>> { rank[it.value.status] ?: 1 }.thenBy { it.index })
            .map { it.value.label.trim().take(60) }
            .filter { it.isNotEmpty() }
            .take(6)
        if (keyPoints.size < 2) return toast("这篇笔记的要点还不到两个，先再讨论一次。")
        endOld()
        val session = Session(
            id = uid(),
            created = now(),
            mode = "teach",
            concept = note.concept,
            topic = "把「${note.concept}」讲给一个刚接触它的人听",
            keyPoints = keyPoints,
            entries = listOf(SystemLine(uid(), "${TEACH_STUDENT.name}是刚接触「${note.concept}」的高中生。把它从头讲给他听，他听不懂会直说。")),
            personas = listOf(TEACH_STUDENT),
            context = note.context,
        )
        update {
            it.copy(
                config = it.config.copy(concept = note.concept),
                session = session,
                ui = it.ui.copy(screen = "session", hint = null, explain = null, sheet = null, error = ""),
            )
        }
    }

    // 安卓返回键：先关开着的面板，知识画布里先回上一层分支，再回上一页。
    // 已经在最外层（学习页）时返回 false，由调用方退出 App。和网页版 goBack() 一致
    fun goBack(): Boolean {
        val ui = state.ui
        val s = session
        if (ui.term != null || ui.explain != null || ui.sheet != null || ui.invite) {
            this.ui { it.copy(term = null, explain = null, sheet = null, invite = false) }
            return true
        }
        if (ui.screen == "session" && s?.at != null && ui.busy == null) {
            goToBranch(s.branches?.find { it.id == s.at }?.parent)
            return true
        }
        val to = PARENT[ui.screen] ?: return false
        // 讨论没开始（或已经结束）时，“复盘”的上一页没有可回的讨论，直接回学习页
        go(if (to == "session" && s == null) "learn" else to)
        return true
    }

    fun clearToast() = ui { it.copy(toast = "") }

    fun clearError() = ui { it.copy(error = "") }

    fun openCard(id: String) {
        ui { it.copy(openCard = id) }
        go("card")
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun JsonObject.ints(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
