package debating.ground.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.Session
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Live
import debating.ground.app.flow.seatName
import debating.ground.app.flow.slots
import debating.ground.app.room.INVITE_BASE
import debating.ground.app.room.QUICK_MESSAGES
import debating.ground.app.room.Speaker
import debating.ground.app.room.encodeQr
import debating.ground.app.room.inviteLink
import debating.ground.app.room.isLanBackend
import debating.ground.app.room.link
import debating.ground.app.room.mySeat
import debating.ground.app.room.mySpeakingTurn
import debating.ground.app.room.seatOwner
import debating.ground.app.room.speakerLabel
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.Sheet
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.glass
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.scrollRow
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors
import kotlinx.coroutines.delay
import java.net.URI

// 多人讨论的界面：成员条、邀请、选席位、点名、房间里的输入区、加入页。和网页版 src/components/RoomPanel.tsx、
// src/screens/Join.tsx 一致。房间状态以后端为准，这里只显示、只发意图。

/* ---------- 成员条 ---------- */

@Composable
fun RoomBar(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val room = s.link ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().scrollRow().semantics { contentDescription = "参与讨论的人" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (m in room.members) {
                val me = m.id == room.memberId
                Row(
                    Modifier.glass(RoundedCornerShape(16.dp), 2.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier.size(7.dp).background(if (m.online) t.good.color() else t.line.color(), CircleShape).semantics {
                            contentDescription =
                                if (m.online) "在线" else "离开"
                        },
                    )
                    Text(
                        if (me) "你" else m.name,
                        style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = if (me) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (m.online) t.ink.color() else t.muted.color(),
                    )
                    val small = TextStyles.small.copy(fontSize = 11.sp)
                    if (m.host) Text("房主", style = small, color = t.muted.color())
                    if (s.mode == "debate") Text(s.seats.find { it.id == m.seat }?.let { seatName(it) } ?: "旁听", style = small, color = t.muted.color())
                    if (m.typing == true && !me) Text("正在输入…", style = small, color = t.accent.color())
                }
            }
            if (room.host && !s.finished) Tool("邀请", IconPaths.PLUS, enabled = true) { model.learning.rooms.openInvite(true) }
        }
        if (room.closed.isNotEmpty() || !room.connected) {
            Text(
                room.closed.ifEmpty {
                    "正在连接房间…"
                },
                style = TextStyles.small.copy(fontSize = 12.sp),
                color = t.muted.color(),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/* ---------- 面板：邀请、选席位、点名 ---------- */

private fun copy(context: Context, text: String): Boolean = runCatching {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("邀请链接", text))
}.isSuccess

// 邀请链接的二维码：同学用手机相机扫一下，浏览器打开就能加入（#151）。深浅模式都是浅底深格，四周留 4 格静区
@Composable
private fun InviteQr(url: String) {
    val q = remember(url) { encodeQr(url) } ?: return
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(240.dp)
                .background(t.qrPaper.color(), RoundedCornerShape(14.dp))
                .semantics { contentDescription = "邀请链接的二维码" },
        ) {
            val cell = size.width / (q.size + 8)
            val ink = t.qrInk.color()
            for (y in 0 until q.size) {
                var x = 0
                while (x < q.size) {
                    if (!q.modules[y][x]) {
                        x++
                        continue
                    }
                    var w = 1
                    while (x + w < q.size && q.modules[y][x + w]) w++
                    // 稍微多画一点，避免相邻的格子之间露出发丝缝
                    drawRect(ink, Offset((x + 4) * cell, (y + 4) * cell), Size(w * cell + 0.5f, cell + 0.5f))
                    x += w
                }
            }
        }
    }
}

@Composable
fun InviteSheet(s: Session, model: AppModel) {
    val room = s.link ?: return
    val context = LocalContext.current
    val l = model.learning
    // 局域网房间的链接直接指向房主那台设备，同学的浏览器从那里打开网页版（#151）
    val url = if (room.invite.isNotEmpty()) {
        inviteLink(
            room.backend,
            room.roomId,
            room.invite,
            if (isLanBackend(room.backend)) room.backend + "/" else INVITE_BASE,
        )
    } else {
        ""
    }
    Sheet("邀请同学", { l.rooms.openInvite(false) }) {
        if (url.isEmpty()) {
            Text("这台设备上没有保存邀请链接。请在创建房间的设备上分享。", style = TextStyles.small, color = LocalTokens.current.ink.color())
            return@Sheet
        }
        val t = LocalTokens.current
        Text("同学扫码或打开链接，写个名字就能加入。", style = TextStyles.small, color = t.ink.color())
        InviteQr(url)
        Text(
            url,
            style = TextStyles.small.copy(fontSize = 12.sp),
            color = t.muted.color(),
            // 一行，超出省略：链接靠复制和分享发出去，不用整条铺开（#172）
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(10.dp)).padding(10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("复制链接", { l.toast(if (copy(context, url)) "邀请链接已复制。" else "没能复制，请长按链接手动复制。") }, Modifier.weight(1f))
            SecondaryButton("分享", {
                val send = Intent(
                    Intent.ACTION_SEND,
                ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, "一起讨论「${s.concept}」")
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            })
        }
        val left = maxOf(0L, (room.expiresAt - System.currentTimeMillis() + 30_000) / 60_000)
        FinePrint("只发给一起讨论的同学。房间约 ${if (left >= 60) "${(left + 30) / 60} 小时" else "$left 分钟"}后到期，内容随之删除。")
    }
}

@Composable
fun SeatSheet(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val room = s.link ?: return
    val rooms = model.learning.rooms
    val mine = mySeat(s)
    Sheet("选择席位", { model.learning.openSheet(null) }) {
        for (team in listOf("A", "B")) {
            val (bg, fg) = toneColors(if (team == "A") "a" else "b")
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (team == "A") "正方 · 支持" else "反方 · 反对", style = TextStyles.small.copy(fontWeight = FontWeight.Bold), color = fg)
                for (seat in s.seats.filter { it.team == team }) {
                    val owner = seatOwner(s, seat.id)
                    val me = owner?.id == room.memberId
                    val free = owner == null || me
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .background(if (me) bg else t.raised.color(), RoundedCornerShape(12.dp))
                            .border(if (me) 2.dp else 1.dp, if (me) fg else t.line.color(), RoundedCornerShape(12.dp))
                            .clickable(enabled = free && !me, role = Role.Button) { rooms.pickSeat(seat.id) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            seatName(seat),
                            style = TextStyles.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = if (free) t.ink.color() else t.muted.color(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                me -> "你"
                                owner != null -> owner.name
                                seat.type == "ai" -> "AI 担任 · 也可以坐"
                                else -> "留给同学 · 空着"
                            },
                            style = TextStyles.small.copy(fontSize = 12.sp),
                            color = if (me) fg else t.muted.color(),
                        )
                    }
                }
            }
        }
        if (mine != null) GhostButton("离开席位，改为旁听", { rooms.pickSeat(null) }, Modifier.fillMaxWidth())
        FinePrint("没人坐的席位由 AI 担任，开赛后也能换。")
    }
}

@Composable
fun CallOnSheet(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val room = s.link ?: return
    val rooms = model.learning.rooms
    Sheet("请谁说说", { model.learning.openSheet(null) }) {
        @Composable
        fun action(name: String, note: String?, target: Speaker) = Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { rooms.callOn(target) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = TextStyles.body, color = t.ink.color())
            if (note != null) Text(" · $note", style = TextStyles.small, color = t.muted.color())
            Spacer(Modifier.weight(1f))
            Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(18.dp), tint = t.muted.color())
        }
        for (m in room.members) action(if (m.id == room.memberId) "我" else m.name, if (m.online) null else "不在线", Speaker("member", m.id))
        for (p in s.personas) action(p.name, "AI 嘉宾", Speaker("ai", p.id))
    }
}

/* ---------- 房间里的输入区 ---------- */

/** 发言限时只是提醒：到点不打断，还可以把话说完。 */
@Composable
fun Countdown(deadline: Long) {
    val t = LocalTokens.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = maxOf(0L, (deadline - now + 999) / 1000)
    if (left > 0) {
        Text(
            "${left / 60}:${(left % 60).toString().padStart(2, '0')}",
            style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = if (left <= 10) t.accent.color() else t.muted.color(),
            modifier = Modifier.semantics { contentDescription = "这次发言还剩 $left 秒" },
        )
    } else {
        Text("时间到", style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = t.accent.color())
    }
}

@Composable
private fun LobbyNote(text: String) {
    val t = LocalTokens.current
    Text(
        text,
        style = TextStyles.small.copy(fontSize = 13.sp),
        color = t.muted.color(),
        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 2.dp).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

// 辩论开始前：提醒每个人选好自己的席位
@Composable
private fun SeatPrompt(s: Session, model: AppModel, primary: Boolean) {
    val seat = s.seats.find { it.id == mySeat(s) }
    val open = { model.learning.openSheet("seats") }
    when {
        seat != null -> GhostButton("你是${seatName(seat)} · 换席位", open, Modifier.fillMaxWidth())
        primary -> PrimaryButton("选择阵营和席位", open, Modifier.fillMaxWidth())
        else -> SecondaryButton("选择阵营和席位", open, Modifier.fillMaxWidth())
    }
}

@Composable
fun RoomComposer(s: Session, st: AppState, live: Live?, model: AppModel) {
    val t = LocalTokens.current
    val room = s.link ?: return
    val l = model.learning
    val rooms = l.rooms
    val busy = st.ui.busy != null
    if (room.status == "lobby") {
        val n = room.members.size
        if (room.host) {
            LobbyNote((if (n > 1) "已有 $n 人在房间里。" else "还没有同学加入。") + "人到齐后开始讨论，晚来的同学也可以中途加入。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("邀请同学", { rooms.openInvite(true) })
                PrimaryButton("开始讨论", { rooms.start() }, Modifier.weight(1f), enabled = room.connected)
            }
            if (s.mode == "debate") SeatPrompt(s, model, primary = false)
        } else {
            LobbyNote("等房主开始讨论……")
            if (s.mode == "debate") SeatPrompt(s, model, primary = true)
        }
        return
    }
    val mine = mySpeakingTurn(s)
    val turn = s.turns.getOrNull(s.cursor)
    // 现在轮到的是“等人”的席位（有同学坐，或留给同学但还空着）：房主可以让 AI 代答
    val humanSeat = s.mode == "debate" && turn != null && (seatOwner(s, turn.role.id) != null || turn.role.type != "ai")
    when {
        room.error.isNotEmpty() -> Row(
            Modifier.fillMaxWidth().background(t.badSoft.color(), RoundedCornerShape(12.dp)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(room.error, color = t.bad.color(), style = TextStyles.small, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (room.host) GhostButton("重试", { rooms.retry() }, small = true)
        }

        // 圆桌是群聊：没有“轮到谁”，只说清楚 AI 嘉宾什么时候接话
        s.mode == "roundtable" -> TurnBanner(t.brand.color()) {
            when {
                room.aiBusy != null -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(room.aiBusy) }
                room.aiPaused == true -> append("AI 嘉宾暂停接话，房主点名时才说")
                else -> append("想说就说，大家停下来几秒后 AI 嘉宾会接话")
            }
        }

        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = turn?.let { toneColors(if (it.role.team == "A") "a" else "b").second } ?: t.brand.color()
            TurnBanner(dot) {
                when {
                    mine -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("轮到你发言") }

                    room.aiBusy != null -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(room.aiBusy) }

                    else -> {
                        append("轮到 ")
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(speakerLabel(s)) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (room.turnDeadline > 0 && room.aiBusy == null) Countdown(room.turnDeadline)
            Text("${s.cursor + 1}/${s.turns.size}", style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color())
        }
    }
    HintCard(st.ui.hint, live) { l.closeHint() }
    Row(
        Modifier.fillMaxWidth().scrollRow(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mine) Tool("给点提示", IconPaths.BULB, enabled = !busy) { model.run { hint() } }
        Tool("写个小结", IconPaths.NOTE, enabled = !busy) { l.addReflection() }
        if (room.host && s.mode == "roundtable") Tool("点名", IconPaths.PEOPLE, enabled = room.aiBusy == null) { l.openSheet("callon") }
        if (room.host && humanSeat && !mine && room.aiBusy == null) Tool("让 AI 代答", null, enabled = true) { rooms.aiTakeover() }
        if (room.host &&
            s.mode == "roundtable"
        ) {
            Tool(
                if (room.aiPaused ==
                    true
                ) {
                    "恢复 AI 接话"
                } else {
                    "暂停 AI 接话"
                },
                null,
                enabled = true,
                on = room.aiPaused == true,
            ) { rooms.toggleAiPause() }
        }
        if (s.mode == "debate" && (mine || room.host) && room.aiBusy == null) Tool(if (mine) "这次先跳过" else "跳过当前发言", null, enabled = true) { rooms.pass() }
        if (s.mode == "debate") Tool(if (mySeat(s) != null) "换席位" else "上场辩论", IconPaths.PEOPLE, enabled = true) { l.openSheet("seats") }
        // 快捷短语：只是提醒大家，不进讨论记录
        Box(Modifier.width(1.dp).height(18.dp).background(t.line.color()))
        for (q in QUICK_MESSAGES) Tool(q, null, enabled = true) { rooms.quickMessage(q) }
    }
    if (mine) {
        val placeholder = if (s.mode == "debate") "${turn?.action ?: "发言"}：我方认为……因为……" else "说点什么，随时可以插话……"
        DraftInput(s.draft, placeholder, busy, onChange = { l.setDraft(it) }, mic = {
            val speech = model.speech
            if (speech != null && room.listenAvailable == true) MicButton(speech, s.draft, { l.toast(it) }, model.roomListen) { l.setDraft(it) }
        }) { model.run { send(s.draft) } }
    } else {
        LobbyNote(if (room.aiBusy != null) "稍等，AI 正在发言。" else "轮到你时，这里会出现输入框。")
    }
}

/* ---------- 加入 ---------- */

// 打开邀请链接后的页面：写个名字就能加入，不需要后端访问口令。没有链接时先贴链接
@Composable
fun JoinScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val context = LocalContext.current
    val join = st.ui.join
    var name by rememberSaveable { mutableStateOf(st.config.name) }
    var raw by rememberSaveable { mutableStateOf("") }
    // 剪贴板里正好是邀请链接：直接填上
    LaunchedEffect(Unit) {
        if (raw.isEmpty()) {
            val clip = runCatching { context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (clip != null && clip.contains("#join=")) raw = clip.trim()
        }
    }
    val leave = {
        l.ui { it.copy(join = null, error = "") }
        l.go("learn")
    }
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        if (join == null) {
            PrimaryButton("下一步", { l.rooms.openJoin(raw) }, Modifier.fillMaxWidth(), enabled = raw.isNotBlank())
        } else {
            PrimaryButton("加入讨论", { l.rooms.join(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
        }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar("加入讨论", onBack = leave)
            Column(Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().background(t.raised.color(), RoundedCornerShape(16.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(52.dp).background(t.lit.color(), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(lineIcon("people", IconPaths.PEOPLE), null, Modifier.size(26.dp), tint = t.litInk.color())
                    }
                    Text(if (join == null) "加入同学的讨论" else "同学邀请你一起讨论", style = TextStyles.title, color = t.ink.color())
                    Text(
                        if (join == null) "把同学发给你的邀请链接贴在下面。" else "和大家轮流发言，不需要密钥或口令。",
                        style = TextStyles.small,
                        color = t.muted.color(),
                    )
                }
                if (join == null) {
                    Input(raw, {
                        raw = it
                    }, "邀请链接", placeholder = "https://…#join=…", singleLine = false, minLines = 2, maxLength = 2000, onDone = { l.rooms.openJoin(raw) })
                } else {
                    Input(name, { name = it.take(20) }, "你的名字", placeholder = "大家会看到这个名字", maxLength = 20, onDone = { l.rooms.join(name) })
                    FinePrint("只在这场讨论里显示。")
                    val host = runCatching { URI(join.backend).host }.getOrNull() ?: join.backend
                    val ongoing = st.session?.let { !it.finished && it.link?.roomId != join.roomId } == true
                    FinePrint("内容临时存在房主那边（$host），结束后删除。你的小结和复盘只留在这台设备上。" + if (ongoing) "加入后，你正在进行的讨论会结束。" else "")
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

/* ---------- 准备页：邀请同学一起讨论 ---------- */

@Composable
fun InviteFriendsSheet(st: AppState, model: AppModel, onClose: () -> Unit) {
    val t = LocalTokens.current
    val l = model.learning
    val c = st.config
    // 用“直接连接”时，房间开在这台手机上（局域网房间，#148）
    val lan = c.aiMode == "api" && st.ui.direct.saved
    val ready = (c.aiMode == "remote" && c.remote.url.isNotEmpty()) || lan
    val debate = st.plan?.mode == "debate"
    var name by rememberSaveable { mutableStateOf(c.name) }
    val busy = st.ui.busy != null
    Sheet("邀请同学一起讨论", onClose) {
        if (!ready) {
            Text("多人讨论要有地方开房间：这台手机（同一个 Wi-Fi 里），或你部署的后端。", style = TextStyles.small, color = t.ink.color())
            FinePrint("在设置里连好“直接连接”就能开局域网房间，不用部署。")
            PrimaryButton("去设置", {
                onClose()
                l.go("settings")
            }, Modifier.fillMaxWidth())
            return@Sheet
        }
        Text("创建一个讨论房间，把链接发给同学。大家用自己的手机加入，" + if (debate) "按赛制轮流上场。" else "轮流发言，每个人说完由一位 AI 嘉宾接话。", style = TextStyles.small, color = t.ink.color())
        Input(name, { name = it.take(20) }, "你的名字", placeholder = "同学会看到这个名字", maxLength = 20)
        Text("每次发言的时限（到点只提醒，不打断）", style = TextStyles.small.copy(fontSize = 13.sp), color = t.muted.color())
        Row(Modifier.fillMaxWidth().scrollRow(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((sec, label) in listOf(0 to "不计时", 30 to "30 秒", 60 to "1 分钟", 90 to "1 分 30 秒", 120 to "2 分钟")) {
                debating.ground.app.ui.Chip(label, c.turnSeconds == sec, { l.editConfig { it.copy(turnSeconds = sec) } })
            }
        }
        if (debate) {
            FinePrint("标为“同学”的席位留给加入的同学，轮到时没人坐就等着，你可以跳过或让 AI 代答；标为“AI”的席位由 AI 担任，同学也可以坐进去。你默认坐第一个标为“我”的席位。进房间后每个人都可以自己选席位。")
            val all = slots(c)
            val peers = all.count { it.type == "peer" } + maxOf(0, all.count { it.type == "human" } - 1)
            if (peers == 0) {
                Text(
                    "现在没有给同学留席位：同学进来只能坐进 AI 的席位或旁听。可以先关掉这里，把一些席位设成“同学”。",
                    style = TextStyles.small,
                    color = t.warn.color(),
                    modifier = Modifier.fillMaxWidth().background(t.warnSoft.color(), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        }
        if (lan) {
            FinePrint(
                "房间开在这台手机上，同一个 Wi-Fi 的同学用浏览器或 App 就能加入，拿不到你的 Key。最多 8 人、150 次 AI 调用。讨论时别退出 App。局域网不加密，别在公共 Wi-Fi 上开。",
            )
        } else {
            FinePrint("AI 用你的后端和额度，同学不需要口令。最多 8 人、150 次 AI 调用；讨论内容结束 1 小时后删除。")
        }
        ErrorBanner(st.ui.error) { l.clearError() }
        PrimaryButton(st.ui.busy ?: "创建房间", { model.run { inviteFriends(name) } }, Modifier.fillMaxWidth(), enabled = !busy && name.isNotBlank())
    }
}
