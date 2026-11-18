package debating.ground.app.flow

import debating.ground.app.ai.Ai
import debating.ground.app.ai.AiOutcome
import debating.ground.app.data.Branch
import debating.ground.app.data.Card
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Config
import debating.ground.app.data.DAY
import debating.ground.app.data.Entry
import debating.ground.app.data.Misconception
import debating.ground.app.data.Persona
import debating.ground.app.data.Plan
import debating.ground.app.data.Point
import debating.ground.app.data.PointProposal
import debating.ground.app.data.Previous
import debating.ground.app.data.QuizItem
import debating.ground.app.data.Reflection
import debating.ground.app.data.ReviewRecord
import debating.ground.app.data.SavedState
import debating.ground.app.data.SeatConfig
import debating.ground.app.data.Session
import debating.ground.app.data.SessionReview
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Stance
import debating.ground.app.data.SystemLine
import debating.ground.app.data.TermCard
import debating.ground.app.data.schedule
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
)

data class Explain(val kind: String, val label: String, val quote: String, val text: String)

data class TermView(val term: String, val definition: String, val relation: String, val messageId: String)

/** 准备时 AI 请学习者交代背景（具体事件类的话题）。 */
data class ContextAsk(val ask: String, val text: String)

data class AppState(
    val config: Config = Config(),
    val plan: Plan? = null,
    val session: Session? = null,
    val library: List<Card> = emptyList(),
    val ui: Ui = Ui(),
) {
    fun saved() = SavedState(config, plan, session, library, ui.screen)
}

/** 正在生成的文字（边生成边显示）。不放进状态：它变得很快，半截文字也不该被保存。 */
data class Live(val id: String, val kind: String, val label: String, val text: String, val who: String? = null)

const val MAX_POINTS = 6
const val TEACHING = "讲给伙伴听"
const val STUDENT = "当学生"
private const val BRANCH_TOPIC_MAX = 40
private val TONES = listOf("c", "a", "b")

private val PARENT = mapOf(
    "prepare" to "learn",
    "session" to "learn",
    "review" to "session",
    "card" to "library",
    "library" to "learn",
    "settings" to "learn",
)

data class Quick(val kind: String, val label: String)

val QUICK = listOf(Quick("unclear", "没听懂"), Quick("example", "举个例子"), Quick("disagree", "我不同意"), Quick("fact", "这是事实还是观点？"))

class Learning(
    initial: AppState,
    private val ai: Ai,
    private val now: () -> Long = System::currentTimeMillis,
    private val uid: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(13) },
    /** 每次状态变化后调用：保存存档、刷新界面。 */
    private val onChange: (AppState) -> Unit = {},
    /** 边生成边显示的文字。 */
    private val onLive: (Live?) -> Unit = {},
) {
    var state: AppState = initial
        private set

    private fun update(fn: (AppState) -> AppState) {
        state = fn(state)
        onChange(state)
    }

    private fun updateSession(fn: (Session) -> Session) = update { st -> st.session?.let { st.copy(session = fn(it)) } ?: st }

    private fun ui(fn: (Ui) -> Ui) = update { it.copy(ui = fn(it.ui)) }

    fun toast(text: String) = ui { it.copy(toast = text) }

    private val session get() = state.session

    fun go(screen: String) = ui { it.copy(screen = screen, sheet = null, explain = null) }

    // 调用 AI：界面上显示“正在……”，失败时显示原因，讨论进度不变（约定 2）。
    // 演示模式推进的 demoStep 写回发起请求的那场讨论
    private suspend fun runAI(purpose: String, payload: JsonObject, label: String, live: Live? = null): AiOutcome.Success? {
        if (state.ui.busy != null || ai.busy) return null
        val sessionAtStart = session
        ui { it.copy(busy = label, error = "") }
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

    fun cancelAI() = ai.cancel()

    /* ---------- 准备 ---------- */

    fun setConcept(concept: String) = update { it.copy(config = it.config.copy(concept = concept), ui = it.ui.copy(context = null)) }

    fun setMode(mode: String) = update { it.copy(config = it.config.copy(mode = mode)) }

    fun editConfig(fn: (Config) -> Config) = update { it.copy(config = fn(it.config)) }

    fun setContextDraft(text: String) = ui { u -> u.context?.let { u.copy(context = it.copy(text = text)) } ?: u }

    /** [then]："start" 是“一句话开始”，准备好直接进讨论；"review" 先停在准备页看看、改改题目和角色。 */
    suspend fun prepare(previous: Previous? = null, knownContext: String? = null, then: String = "review") {
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

    private fun planProblem(): String {
        val plan = state.plan ?: return "还没有准备好讨论。"
        if (plan.topic.trim().isEmpty()) return "请填写讨论题。"
        if (plan.keyPoints.map { it.trim() }.count { it.isNotEmpty() } < 2) return "至少保留两个要点。"
        if (plan.mode == "debate") {
            if (state.config.stages.any { it.name.trim().isEmpty() }) return "请为每个环节填写名称。"
            if (slots(state.config).none { it.type == "human" }) return "至少安排一个“我”的席位，你才能参与交锋。"
        }
        return ""
    }

    private fun endOld() {
        val old = session
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

    fun setDraft(text: String) = updateSession { it.copy(draft = text) }

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
                    action = if (s.teaching == true) TEACHING else null,
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
        if (s.mode == "debate") return submitSeat(text)
        pushLearner(text)
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
        val student = s.teaching == true
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
                        action = if (student) STUDENT else null,
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
        if (state.ui.busy != null) return
        val role = s.personas[s.nextRole % s.personas.size]
        updateSession { it.copy(nextRole = (s.nextRole + 1) % s.personas.size) }
        // 请下一位嘉宾：接上一位的话说，不再向学习者提问（否则问题会一个接一个叠起来）
        speak(role.id, "主持人请你接着上一位嘉宾的话说：回应、补充或反驳他。")
    }

    fun submitSeat(text: String, skipped: Boolean = false) {
        val s = session ?: return
        val t = currentTurn(s) ?: return
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
            if (s.mode == "debate" && currentTurn(s)?.role?.type != "human") return toast("轮到你的席位时，再在发言里反驳这一点。")
            updateSession { it.copy(draft = "我不同意「$quote」，因为") }
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

    fun proposePoint(raw: String, source: String = "human"): Boolean {
        val s = session
        val text = raw.trim().take(40)
        if (s == null || text.isEmpty()) return false
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

    fun decidePoint(id: String, accept: Boolean) = updateSession { ss ->
        val p = ss.proposedPoints?.find { it.id == id } ?: return@updateSession ss
        val rest = ss.proposedPoints.filter { it.id != id }
        ss.copy(proposedPoints = rest, keyPoints = if (accept && ss.keyPoints.size < MAX_POINTS) ss.keyPoints + p.text else ss.keyPoints)
    }

    suspend fun suggestPoint() {
        val s = session ?: return
        if (state.ui.busy != null) return
        if (s.keyPoints.size >= MAX_POINTS) return toast("讨论要点最多 6 个。")
        val label = if (s.mode == "debate") "AI 正在找一个值得交锋的争点" else "AI 正在找一个还没讲到的要点"
        val res = runAI("point", buildJsonObject { put("session", snapshot(s)) }, label) ?: return
        (res.result["data"] as? JsonObject)?.str("point")?.takeIf { it.isNotEmpty() }?.let { proposePoint(it, "ai") }
    }

    suspend fun hint() {
        val s = session ?: return
        if (state.ui.busy != null) return
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
        updateSession { it.copy(entries = it.entries + Reflection(uid(), "", "", "", s.at)) }
    }

    private fun editReflection(id: String, fn: (Reflection) -> Reflection) = updateSession { ss ->
        ss.copy(entries = ss.entries.map { if (it is Reflection && it.id == id) fn(it) else it })
    }

    fun setReflectionDraft(id: String, draft: String) = editReflection(id) { it.copy(draft = draft) }

    fun dropReflection(id: String) = updateSession { ss -> ss.copy(entries = ss.entries.filter { it.id != id }) }

    suspend fun submitReflection(id: String) {
        val s = session ?: return
        if (state.ui.busy != null) return
        val m = s.entries.find { it.id == id } as? Reflection ?: return
        val answer = m.draft.trim()
        if (answer.isEmpty()) return toast("用一两句话写下你的理解。")
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

    /* ---------- 换我来讲 ---------- */

    // 费曼学习法：苏格拉底追问里，学习者可以换过来当老师，伙伴暂时当听讲的学生
    suspend fun toggleTeaching() {
        val s = session ?: return
        if (s.mode != "socratic" || s.finished || state.ui.busy != null) return
        val name = s.personas.first().name
        val on = s.teaching != true
        updateSession { ss ->
            ss.copy(
                teaching = on,
                entries = ss.entries + SystemLine(
                    uid(),
                    if (on) "换你来讲：${name}现在是听讲的学生，把「${ss.concept}」讲给他听。" else "换回来了，${name}接着和你一起想。",
                ),
            )
        }
        speak(
            s.personas.first().id,
            if (on) {
                "学习者要换他来讲了。用一句话表示你准备好当学生听讲，可以先说一个你作为初学者对这个概念最大的疑惑。"
            } else {
                "学习者讲完了，你换回讨论伙伴。用两三句话说说他刚才的讲解哪里讲清楚了、哪里还可以讲得更准，然后接着讨论。"
            },
        )
    }

    /* ---------- 知识画布 ---------- */

    /** 从一条发言展开一个分支，AI 先把这个话题讲个大概。 */
    suspend fun openBranch(fromId: String, rawTopic: String) {
        val s = session
        val topic = rawTopic.trim()
        if (s == null || s.mode != "canvas" || s.finished || state.ui.busy != null) return
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
        speak(s.personas.first().id, "学习者从这句话展开了一个分支，想弄懂「$topic」。先用两三句话说说它大概是什么，最好带一个具体例子，再听听他怎么想。")
    }

    /** 跳到某个分支（null 是主干）。 */
    fun goToBranch(at: String?) {
        val s = session ?: return
        if (s.mode != "canvas" || state.ui.busy != null) return
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

    /** 讨论里 AI 标出的陌生词：查看释义。查过的词记在这次讨论里，复盘时存进知识卡片。 */
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
        val res = runAI("term", payload, "正在查「$term」")
        update { st ->
            if (res == null) return@update st.copy(ui = st.ui.copy(term = null))
            val d = res.result["data"] as? JsonObject
            val card = TermCard(term, d?.str("definition").orEmpty(), d?.str("relation").orEmpty())
            val ui2 = if (st.ui.term?.term == term) st.ui.copy(term = TermView(term, card.definition, card.relation, messageId)) else st.ui
            val ss = st.session
            if (ss?.id != s.id) return@update st.copy(ui = ui2)
            val glossary = ss.glossary.orEmpty().filter { it.term != term } + card
            // 已经复盘过的讨论，直接补进知识卡片
            st.copy(
                ui = ui2,
                session = ss.copy(glossary = glossary),
                library = st.library.map { if (it.sessionId == ss.id) it.copy(terms = glossary) else it },
            )
        }
    }

    fun closeTerm() = ui { it.copy(term = null) }

    /* ---------- 复盘与知识库 ---------- */

    suspend fun finish() {
        if (state.ui.busy != null) return
        update { st ->
            st.copy(
                session = st.session!!.copy(finished = true, awaitingReview = false, draft = ""),
                ui = st.ui.copy(hint = null, explain = null, screen = "review"),
            )
        }
        val s = session!!
        if (s.entries.any { (it is ChatMessage && it.human == true) || (it is Reflection && it.text.isNotEmpty()) }) generateReview()
    }

    fun resume() = update { st -> st.copy(session = st.session!!.copy(finished = false), ui = st.ui.copy(screen = "session")) }

    /** 复盘只依据学习者本人的发言和小结（约定 4），快照里是整场讨论。 */
    suspend fun generateReview() {
        val s = session ?: return
        val res = runAI("review", buildJsonObject { put("session", snapshot(s, true)) }, "正在复盘整场讨论") ?: return
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
        update { st ->
            val ss = st.session!!.copy(review = review)
            val existing = st.library.find { it.sessionId == ss.id }
            val card = Card(
                id = existing?.id ?: uid(),
                sessionId = ss.id,
                concept = ss.concept,
                topic = ss.topic,
                mode = ss.mode,
                created = existing?.created ?: now(),
                oneLine = review.oneLine,
                points = review.points,
                misconceptions = review.misconceptions,
                stances = review.stances,
                quiz = review.quiz,
                source = res.source,
                model = review.model,
                terms = ss.glossary?.ifEmpty { null } ?: existing?.terms,
                context = ss.context?.ifEmpty { null } ?: existing?.context,
                level = existing?.level ?: 0,
                due = existing?.due ?: (now() + DAY),
                reviews = existing?.reviews ?: emptyList(),
            )
            st.copy(
                session = ss,
                library = if (existing != null) st.library.map { if (it.id == existing.id) card else it } else listOf(card) + st.library,
            )
        }
    }

    /** 复习一张卡片：记得就拉长间隔，模糊就明天再来。 */
    fun rateCard(id: String, remembered: Boolean) {
        val c = state.library.find { it.id == id } ?: return
        val next = schedule(c.level, remembered, now())
        update { st ->
            st.copy(
                library = st.library.map {
                    if (it.id ==
                        id
                    ) {
                        it.copy(level = next.level, due = next.due, reviews = it.reviews + ReviewRecord(now(), remembered))
                    } else {
                        it
                    }
                },
                ui = st.ui.copy(openCard = null, screen = "library"),
            )
        }
        toast("下次复习：${next.days} 天后")
    }

    /** 再讨论一次：带上上次的一句话和没掌握的地方。 */
    suspend fun discussAgain(card: Card) {
        update { it.copy(config = it.config.copy(concept = card.concept, mode = card.mode)) }
        prepare(Previous(card.oneLine, weakOf(card)), card.context)
    }

    // 安卓返回键：先关开着的面板，知识画布里先回上一层分支，再回上一页。
    // 已经在最外层（学习页）时返回 false，由调用方退出 App。和网页版 goBack() 一致
    fun goBack(): Boolean {
        val ui = state.ui
        val s = session
        if (ui.term != null || ui.explain != null || ui.sheet != null) {
            this.ui { it.copy(term = null, explain = null, sheet = null) }
            return true
        }
        if (ui.screen == "session" && s?.mode == "canvas" && s.at != null && ui.busy == null) {
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

fun weakOf(c: Card) = c.points.filter { it.status != "mastered" }.map { it.label } + c.misconceptions.map { it.said }

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun JsonObject.ints(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
