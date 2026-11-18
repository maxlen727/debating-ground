package debating.ground.app.flow

import debating.ground.app.ai.AiOutcome
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Reflection
import debating.ground.app.room.JoinTarget
import debating.ground.app.room.RoomDialer
import debating.ground.app.room.RoomEvents
import debating.ground.app.room.RoomLink
import debating.ground.app.room.RoomReflection
import debating.ground.app.room.RoomSocket
import debating.ground.app.room.RoomView
import debating.ground.app.room.ServerMessage
import debating.ground.app.room.Speaker
import debating.ground.app.room.link
import debating.ground.app.room.parseInvite
import debating.ground.app.room.toSession
import debating.ground.app.room.withLink
import debating.ground.app.room.wsURL
import debating.ground.app.speech.Heard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 多人讨论：连接房间、同步状态、发送操作（网页版 src/lib/room.ts 和 actions.ts 的多人部分）。
// 房间状态以后端为准：这里只把收到的状态映射成本地的讨论，发言和跳过都交给后端判断（约定 9）。
// 连接的回调都在主线程上；要等结果的事（复盘、重连的延时）交给 launch。

private class AskCancelled : Exception("已取消")

class Rooms internal constructor(
    private val l: Learning,
    private val dialer: RoomDialer?,
    private val now: () -> Long,
    private val uid: () -> String,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    private var socket: RoomSocket? = null
    private var retries = 0
    private var generation = 0 // 每次连接加一：旧连接和旧的重连计时不再起作用
    private var joinName = ""
    private val pending = HashMap<String, CompletableDeferred<JsonObject>>()
    private var asking: CompletableDeferred<JsonObject>? = null
    private var liveId: String? = null

    /** 朗读的一段音频（房间里由后端一段段推过来）。 */
    var onAudio: ((ServerMessage.Audio) -> Unit)? = null

    /** 语音输入的识别结果（只发给说话的人）。 */
    var onHeard: ((Heard) -> Unit)? = null

    /** 后端拒绝了一个操作（例如朗读次数用完）：朗读按钮恢复原样。 */
    var onRefused: (() -> Unit)? = null

    /** 能不能用多人讨论：这台设备能连 WebSocket（测试里没有）。 */
    val available get() = dialer != null

    private val link get() = l.state.session?.link

    private fun setLink(fn: (RoomLink) -> RoomLink) = l.updateSession { s -> s.link?.let { s.withLink(fn(it)) } ?: s }

    /* ---------- 连接 ---------- */

    /** 连到房间：带 [target] 是第一次加入（凭邀请码），不带是重连（凭成员凭据）。 */
    fun connect(target: JoinTarget? = null, name: String = "") {
        val d = dialer ?: return
        socket?.close()
        val room = target?.let { Triple(it.backend, it.roomId, it.invite) } ?: link?.let { Triple(it.backend, it.roomId, "") } ?: return
        joinName = name
        val gen = ++generation
        socket = d.dial(
            wsURL(room.first, room.second),
            object : RoomEvents {
                override fun opened() {
                    if (gen != generation) return
                    retries = 0
                    val hello = if (target != null) {
                        buildJsonObject {
                            put("type", "hello")
                            put("invite", target.invite)
                            put("name", joinName)
                        }
                    } else {
                        buildJsonObject {
                            put("type", "resume")
                            put("token", link?.token.orEmpty())
                        }
                    }
                    socket?.send(hello.toString())
                }

                override fun message(text: String) {
                    if (gen != generation) return
                    ServerMessage.parse(text)?.let { handle(it, target) }
                }

                override fun closed() {
                    if (gen != generation) return
                    socket = null
                    failPending("和房间的连接断开了，请稍后重试。")
                    val r = link
                    if (target != null && r?.connected != true) {
                        // 还没加入成功就断开：多半是地址不对或后端没有更新
                        if (l.state.ui.screen == "join") {
                            l.ui { it.copy(error = it.error.ifEmpty { "连不上这个房间。请确认邀请链接完整，或请房主检查后端是否已更新。" }) }
                        }
                        return
                    }
                    if (r == null || r.closed.isNotEmpty()) return
                    setLink { it.copy(connected = false) }
                    val wait = minOf(10_000L, 1000L shl retries++.coerceAtMost(4))
                    launch {
                        delay(wait)
                        if (gen == generation && socket == null) connect()
                    }
                }
            },
        )
    }

    /** 打开 App 时：上次在房间里、房间还没关，就重连。 */
    fun resume() {
        val r = link ?: return
        if (r.closed.isEmpty() && dialer != null) connect()
    }

    private fun failPending(reason: String) {
        for (p in pending.values) p.completeExceptionally(IllegalStateException(reason))
        pending.clear()
    }

    private fun handle(msg: ServerMessage, target: JoinTarget?) {
        when (msg) {
            is ServerMessage.Welcome -> {
                val prev = l.state.session
                val base = prev?.link?.takeIf { it.roomId == msg.room.id }
                val nl = RoomLink(
                    backend = target?.backend ?: base?.backend.orEmpty(),
                    roomId = msg.room.id,
                    token = msg.token,
                    invite = base?.invite.orEmpty(),
                    memberId = msg.memberId,
                    host = msg.room.members.find { it.id == msg.memberId }?.host ?: false,
                    connected = true,
                    reflections = base?.reflections.orEmpty(),
                )
                l.update { st ->
                    val s = toSession(msg.room, st.session, nl, now())
                    val u = if (st.ui.screen == "join") st.ui.copy(screen = "session", join = null, error = "") else st.ui
                    st.copy(session = s, ui = u)
                }
            }

            is ServerMessage.State -> applyView(msg.room)

            is ServerMessage.HeardMsg -> onHeard?.invoke(msg.event)

            is ServerMessage.Audio -> onAudio?.invoke(msg)

            is ServerMessage.Quick -> l.toast(if (msg.memberId == link?.memberId) "已提醒大家：${msg.text}" else "${msg.name}：${msg.text}")

            is ServerMessage.Stream -> {
                val s = l.state.session ?: return
                val r = s.link ?: return
                val who = if (s.mode == "debate") s.turns.getOrNull(s.cursor)?.role?.id else r.speaker?.id
                liveId = msg.id
                l.live(Live(msg.id, "msg", r.aiBusy.orEmpty(), msg.text, who))
            }

            is ServerMessage.Answer -> {
                val p = pending.remove(msg.reqId) ?: return
                if (msg.ok) p.complete(msg.result ?: JsonObject(emptyMap())) else p.completeExceptionally(IllegalStateException(msg.error))
            }

            is ServerMessage.Error -> {
                onRefused?.invoke()
                // 旧版后端不认识新加的操作，只会回“不支持的操作”；告诉用户该怎么办
                l.toast(
                    if (msg.message == "不支持的操作。") {
                        if (link?.host == true) "你的后端版本较旧，不支持这个操作。请按文档“怎么更新后端”更新后再试。" else "房主的后端版本较旧，不支持这个操作。请房主更新后端。"
                    } else {
                        msg.message
                    },
                )
            }

            is ServerMessage.Closed -> {
                generation++ // 不再重连
                socket?.close()
                socket = null
                if (l.state.ui.screen == "join") {
                    l.ui { it.copy(error = msg.reason) }
                    return
                }
                setLink { it.copy(closed = msg.reason, connected = false) }
                l.toast(msg.reason)
            }
        }
    }

    private fun applyView(view: RoomView) {
        val before = l.state.session ?: return
        val r = before.link ?: return
        if (r.roomId != view.id) return
        val wasFinished = before.finished
        l.update { st ->
            val s = st.session!!
            var next = toSession(view, s, r.copy(connected = true), now())
            // 自己发言满 3 次、6 次时，停下来写个小结（和单人模式一样）
            val nl = next.link!!
            if (next.learnerTurns > s.learnerTurns && next.learnerTurns in setOf(3, 6) && !next.finished && nl.reflections.none { it.text.isEmpty() }) {
                val card = RoomReflection(uid(), after = view.entries.lastOrNull()?.id.orEmpty())
                next = toSession(view, s, nl.copy(reflections = nl.reflections + card), now())
            }
            val u = if (next.finished && !wasFinished && st.ui.screen == "session") st.ui.copy(screen = "review") else st.ui
            st.copy(session = next, ui = u)
        }
        // AI 发言写进记录（或失败）后，生成中的气泡交给正式发言接着显示
        if (view.aiBusy == null && liveId != null) {
            liveId = null
            l.live(null)
        }
        val now = l.state.session ?: return
        if (now.finished && !wasFinished) {
            launch {
                val s = l.state.session
                if (s != null && (s.entries.any { it is ChatMessage && it.human == true } || s.link?.reflections?.any { it.text.isNotEmpty() } == true)) {
                    l.generateReview()
                }
            }
        }
    }

    /** 发一条操作。quiet：顺带发的（正在输入），连接断了就算了，不提示。 */
    fun send(msg: JsonObject, quiet: Boolean = false): Boolean {
        val ok = socket?.send(msg.toString()) == true
        if (!ok && !quiet) l.toast("正在重新连接房间，请稍后再试。")
        return ok
    }

    private fun send(type: String, quiet: Boolean = false, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = send(
        buildJsonObject {
            put("type", type)
            fill()
        },
        quiet,
    )

    /** 个人请求（提示、讲解、小结、复盘）：结果只发给自己。和 runAI 一样显示“正在……”，失败时显示原因。 */
    internal suspend fun ask(body: JsonObject, label: String): AiOutcome.Success? {
        if (l.state.ui.busy != null) return null
        val reqId = uid()
        val d = CompletableDeferred<JsonObject>()
        pending[reqId] = d
        if (!send(JsonObject(body + mapOf("type" to JsonPrimitive("ask"), "reqId" to JsonPrimitive(reqId))), quiet = true)) {
            pending.remove(reqId)
            l.ui { it.copy(error = "和房间的连接断开了，请稍后重试。") }
            return null
        }
        asking = d
        l.ui { it.copy(busy = label, error = "") }
        return try {
            AiOutcome.Success(d.await(), "api", null)
        } catch (e: AskCancelled) {
            null
        } catch (e: IllegalStateException) {
            l.ui { it.copy(error = e.message.orEmpty()) }
            null
        } finally {
            pending.remove(reqId)
            asking = null
            l.ui { it.copy(busy = null) }
        }
    }

    /** 取消正在等的个人请求（“取消”按钮）。 */
    fun cancelAsk() {
        asking?.completeExceptionally(AskCancelled())
    }

    fun leave() {
        generation++
        socket?.close()
        socket = null
        pending.clear()
        stopTyping()
    }

    /* ---------- 加入 ---------- */

    /** 读邀请链接：完整的就停在加入页，等写名字。 */
    fun openJoin(raw: String): Boolean {
        val target = parseInvite(raw)
        if (target == null) {
            l.toast("邀请链接不完整，请让房主重新发一次。")
            return false
        }
        val s = l.state.session
        if (s?.link?.roomId == target.roomId && s.link?.closed.isNullOrEmpty()) {
            l.ui { it.copy(screen = "session") }
            connect()
            return true
        }
        l.ui { it.copy(join = target, screen = "join", error = "") }
        return true
    }

    fun join(rawName: String) {
        val name = rawName.trim()
        val target = l.state.ui.join ?: return
        if (name.isEmpty()) return l.toast("写下你在讨论里显示的名字。")
        l.update { it.copy(config = it.config.copy(name = name), ui = it.ui.copy(error = "")) }
        l.endOld()
        connect(target, name)
    }

    /* ---------- 房间里的操作 ---------- */

    fun start() = send("start")

    fun callOn(target: Speaker) {
        l.ui { it.copy(sheet = null) }
        send("callOn") {
            put(
                "target",
                buildJsonObject {
                    put("kind", target.kind)
                    put("id", target.id)
                },
            )
        }
    }

    fun pass() = send("pass")

    /** 圆桌：房主暂停或恢复 AI 嘉宾自动接话。 */
    fun toggleAiPause() {
        val r = link ?: return
        if (r.host) send("aiPause") { put("paused", r.aiPaused != true) }
    }

    fun quickMessage(text: String) = send("quickMessage") { put("text", text) }

    fun pickSeat(seat: String?) {
        l.ui { it.copy(sheet = null) }
        send("seat") { put("seat", seat?.let(::JsonPrimitive) ?: JsonNull) }
    }

    fun aiTakeover() = send("aiTakeover")

    fun retry() = send("retry")

    fun say(text: String) = send("say") { put("text", text) }

    fun propose(text: String, source: String) = send("propose") {
        put("text", text)
        put("source", source)
    }

    fun decidePoint(id: String, accept: Boolean) = send("decidePoint") {
        put("id", id)
        put("accept", accept)
    }

    fun finish() = send("finish")

    fun speak(entryId: String, voice: String) = send("speak") {
        put("entryId", entryId)
        put("voice", voice)
    }

    /** 语音输入的一帧或一个指令，经房间连接转发。 */
    fun listen(op: String, audio: String?) = send("listen", quiet = op == "audio") {
        put("op", op)
        if (audio != null) put("audio", audio)
    }

    // 正在输入：每次按键都发会刷爆房间。开始输入时发一次，持续输入时每 3 秒续一次，停 2 秒或发送后撤销
    private var typingSent = 0L
    private var typingGen = 0

    fun noteTyping() {
        if (link == null) return
        val t = now()
        if (t - typingSent > 3000 && send("typing", quiet = true) { put("active", true) }) typingSent = t
        val g = ++typingGen
        launch {
            delay(2000)
            if (g == typingGen) stopTyping()
        }
    }

    fun stopTyping() {
        typingGen++
        if (typingSent == 0L) return
        typingSent = 0
        if (link != null) send("typing", quiet = true) { put("active", false) }
    }

    fun openInvite(open: Boolean) = l.ui { it.copy(invite = open) }

    /** 小结卡：讨论记录里一份，房间的 reflections 里一份（只留在这台设备上），两份一起改。 */
    internal fun addReflection(card: Reflection, after: String) = setLink { it.copy(reflections = it.reflections + RoomReflection(card.id, after = after)) }

    internal fun editReflection(id: String, text: String, feedback: String, draft: String) = setLink { r ->
        r.copy(reflections = r.reflections.map { if (it.id == id) it.copy(text = text, feedback = feedback, draft = draft) else it })
    }

    internal fun dropReflection(id: String) = setLink { r -> r.copy(reflections = r.reflections.filter { it.id != id }) }
}
