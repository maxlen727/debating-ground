package debating.ground.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import debating.ground.app.AppModel
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Session
import debating.ground.app.data.branchById
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Live
import debating.ground.app.flow.listenReady
import debating.ground.app.flow.seatName
import debating.ground.app.room.link
import debating.ground.app.speech.Phase
import debating.ground.app.speech.Speech
import debating.ground.app.ui.Appear
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.LocalFloating
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.RevealText
import debating.ground.app.ui.glass
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.mix
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors
import kotlinx.coroutines.delay

// 讨论页底部的输入栏：提示、工具、输入框、朗读和语音输入（DiscussScreen.kt 的一部分）。

/* ---------- 底部输入栏 ---------- */

@Composable
internal fun ComposerArea(s: Session, st: AppState, live: Live?, model: AppModel) {
    val l = model.learning
    // 不垫整条底色：工具、输入框、按钮各自悬浮（磨砂底或投影）
    CompositionLocalProvider(LocalFloating provides true) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorBanner(st.ui.error) { l.clearError() }
            when {
                s.awaitingReview == true -> DoneRow("赛程结束了，读完总结陈词再复盘。") {
                    PrimaryButton("进入复盘", { model.run { finish() } }, enabled = st.ui.busy == null, small = true)
                }

                s.finished -> DoneRow("这次讨论已结束。") { PrimaryButton("看复盘", { l.go("review") }, small = true) }

                s.room != null -> RoomComposer(s, st, live, model)

                else -> Composer(s, st, live, model)
            }
        }
    }
}

@Composable
private fun DoneRow(text: String, action: @Composable () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = TextStyles.small, color = t.muted.color(), modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun Composer(s: Session, st: AppState, live: Live?, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val busy = st.ui.busy != null
    val turn = l.currentTurn(s)
    val debate = s.mode == "debate"
    val humanTurn = !debate || turn?.role?.type == "human"
    if (debate && turn != null) {
        TurnBanner(if (turn.role.team == "A") toneColors("a").second else toneColors("b").second) {
            append("轮到 ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seatName(turn.role)) }
            append((if (turn.role.type == "human") "（你）" else "") + " · ${turn.action}")
            turn.target?.let { id -> append(" → " + (s.seats.find { it.id == id }?.let { seatName(it) } ?: id)) }
        }
    }
    if (s.teaching == true) {
        TurnBanner(t.brand.color()) {
            append("你在讲，")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.personas.firstOrNull()?.name.orEmpty()) }
            append("当学生")
        }
    }
    HintCard(st.ui.hint, live) { l.closeHint() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (humanTurn) Tool("给点提示", IconPaths.BULB, enabled = !busy) { model.run { hint() } }
        Tool("写个小结", IconPaths.NOTE, enabled = !busy) { l.addReflection() }
        if (s.mode == "socratic") {
            Tool(if (s.teaching == true) "换回来" else "换我来讲", IconPaths.SWAP, enabled = !busy, on = s.teaching == true) { model.run { toggleTeaching() } }
        }
        if (!debate && s.personas.size > 1) Tool("请下一位", IconPaths.PEOPLE, enabled = !busy) { model.run { nextSpeaker() } }
        if (debate) Tool("跳过本席", null, enabled = !busy) { l.skipSeat() }
        if (s.mode == "canvas" && s.at != null) {
            val up = if (s.branchById(s.at)?.parent != null) "上一层" else "主干"
            Tool("带回$up", IconPaths.TURN_BACK, enabled = !busy) { l.openSheet("back") }
        }
    }
    if (humanTurn) {
        val placeholder = when {
            debate -> "${turn?.action ?: "发言"}：我方认为……因为……"
            s.mode == "teach" || s.teaching == true -> "讲给${s.personas.firstOrNull()?.name ?: "他"}听……"
            else -> "说说你的想法……"
        }
        DraftInput(s.draft, placeholder, busy, onChange = { l.setDraft(it) }, mic = {
            val speech = model.speech
            if (speech != null && st.listenReady() && s.room == null) MicButton(speech, s.draft, { l.toast(it) }) { l.setDraft(it) }
        }) { model.run { send(s.draft) } }
    } else {
        PrimaryButton("让 ${turn?.let { seatName(it.role) }.orEmpty()} 发言", { model.run { generateSeat() } }, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

@Composable
internal fun TurnBanner(dot: Color, text: AnnotatedString.Builder.() -> Unit) {
    val t = LocalTokens.current
    // 小小的磨砂胶囊，压在讨论记录上也看得清
    Row(
        Modifier.glass(RoundedCornerShape(16.dp), 3.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(buildAnnotatedString(text), style = TextStyles.small.copy(fontSize = 13.sp), color = t.ink.color())
    }
}

@Composable
internal fun Tool(text: String, icon: String?, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current
    // 看起来是小胶囊，可点区域有 44dp 高
    Box(
        Modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.then(
                if (on) {
                    Modifier.background(t.brand.color().mix(0.12f, t.bg.color()), RoundedCornerShape(18.dp))
                        .border(1.dp, t.brand.color().mix(0.45f, t.line.color()), RoundedCornerShape(18.dp))
                } else {
                    Modifier.glass(RoundedCornerShape(18.dp), 3.dp)
                },
            )
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val fg = if (on) t.brand.color() else t.ink.color()
            if (icon != null) Icon(lineIcon(icon, icon), null, Modifier.size(16.dp), tint = fg)
            Text(
                text,
                style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun HintCard(hint: String?, live: Live?, onClose: () -> Unit) {
    val t = LocalTokens.current
    val streaming = live?.takeIf { it.kind == "hint" && it.text.isNotEmpty() }
    Appear(streaming?.text ?: hint) { text -> HintRow(text, streaming != null, onClose) }
}

@Composable
private fun HintRow(text: String, streaming: Boolean, onClose: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().background(t.warnSoft.color(), RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(lineIcon("bulb", IconPaths.BULB), null, Modifier.padding(top = 3.dp).size(18.dp), tint = t.warn.color())
        Box(Modifier.weight(1f).padding(top = 1.dp)) {
            RevealText(
                "hint",
                text,
                live = streaming,
                style = TextStyles.small.copy(lineHeight = 1.7.em),
                color = t.ink.color(),
            )
        }
        Box(Modifier.size(36.dp).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(lineIcon("close", IconPaths.CLOSE), "关闭提示", Modifier.size(16.dp), tint = t.warn.color())
        }
    }
}

@Composable
internal fun DraftInput(draft: String, placeholder: String, busy: Boolean, onChange: (String) -> Unit, mic: @Composable () -> Unit = {}, onSend: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = draft,
            onValueChange = { if (it.length <= 4000) onChange(it) },
            textStyle = TextStyles.body.copy(color = t.ink.color()),
            cursorBrush = SolidColor(t.brand.color()),
            maxLines = 6,
            modifier = Modifier.weight(1f).heightIn(min = 46.dp).semantics { contentDescription = "你的发言" },
            decorationBox = { inner ->
                Box(
                    Modifier.glass(RoundedCornerShape(23.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    if (draft.isEmpty()) Text(placeholder, style = TextStyles.body, color = t.muted.color(), maxLines = 1)
                    inner()
                }
            },
        )
        mic()
        val enabled = !busy && draft.isNotBlank()
        Box(
            Modifier.size(
                46.dp,
            ).shadow(
                if (enabled) 8.dp else 0.dp,
                CircleShape,
                spotColor = t.brand.color().copy(alpha = 0.45f),
            ).alpha(if (enabled) 1f else 0.45f).background(t.brand.color(), CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "发送" },
            contentAlignment = Alignment.Center,
        ) { Icon(lineIcon("send", IconPaths.SEND), null, tint = t.brandInk.color()) }
    }
}

/* ---------- 朗读和语音输入（后端配好讯飞时才有） ---------- */

// 朗读按钮：看起来是小字，可点区域有 44dp 高；再点一下停止
@Composable
internal fun VoiceButton(speech: Speech, m: ChatMessage, s: Session, label: String, model: AppModel) {
    val t = LocalTokens.current
    val playing by speech.voice.playing.collectAsState()
    val on = playing == m.id
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button) {
                speech.listen.cancel() // 别一边录一边播
                val voiceName = model.learning.state.config.voiceName
                if (s.link == null) {
                    speech.voice.toggle(m.id, m.text, voiceName)
                } else if (playing == m.id) {
                    speech.voice.stop()
                } else {
                    // 房间里由房主的后端合成，音频推过来；同一条合成过一次后再听不重复计数
                    speech.voice.await(m.id)
                    if (!model.learning.rooms.speak(m.id, voiceName)) speech.voice.stop()
                }
            }
            .semantics { contentDescription = if (on) "停止朗读${label}的发言" else "朗读${label}的发言" }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val c = if (on) t.brand.color() else t.muted.color()
        Icon(lineIcon(if (on) "stop" else "volume", if (on) IconPaths.STOP else IconPaths.VOLUME), null, Modifier.size(14.dp), tint = c)
        Text(if (on) "停止" else "朗读", style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = c)
    }
}

// 语音输入：点一下开始，再点一下结束；识别出的文字接在已经写好的内容后面，不自动发送。
// 第一次用时先请求麦克风权限
@Composable
internal fun MicButton(
    speech: Speech,
    draft: String,
    toast: (String) -> Unit,
    via: debating.ground.app.speech.ListenConnect? = null,
    onText: (String) -> Unit,
) {
    val t = LocalTokens.current
    val context = LocalContext.current
    val phase by speech.listen.phase.collectAsState()
    val haptic = LocalHapticFeedback.current
    val begin = {
        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        speech.voice.stop() // 别把朗读的声音录进去
        val before = draft
        val sep = if (Regex("[A-Za-z0-9]$").containsMatchIn(before)) " " else ""
        speech.listen.start(text = { said -> onText(before + if (said.isEmpty()) "" else sep + said) }, end = { error ->
            if (error !=
                null
            ) {
                toast(error)
            }
        }, via = via)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else toast("没有麦克风权限，不能语音输入。可以在系统设置里给思辨场打开。")
    }
    // 正在听时每半秒更新一次已录秒数
    var secs by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        secs = 0
        while (phase == Phase.Listening) {
            secs = ((System.currentTimeMillis() - speech.listen.startedAt) / 1000).toInt()
            delay(500)
        }
    }
    val label = when (phase) {
        Phase.Listening -> "说完了，结束语音输入（已录 $secs 秒）"
        Phase.Finishing -> "正在识别最后一句"
        Phase.Starting -> "正在打开麦克风"
        null -> "语音输入"
    }
    val on = phase != null
    Box(
        Modifier
            .size(46.dp)
            .then(if (on) Modifier.background(t.brand.color(), CircleShape) else Modifier.glass(CircleShape))
            .alpha(if (phase == Phase.Starting || phase == Phase.Finishing) 0.7f else 1f)
            .clickable(enabled = phase != Phase.Finishing, role = Role.Button) {
                when {
                    on -> {
                        haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        speech.listen.stop()
                    }

                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()

                    else -> ask.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (phase == Phase.Listening) {
            Text("$secs″", style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = t.brandInk.color())
        } else {
            Icon(lineIcon("mic", IconPaths.MIC), null, Modifier.size(20.dp), tint = if (on) t.brandInk.color() else t.ink.color())
        }
    }
}
