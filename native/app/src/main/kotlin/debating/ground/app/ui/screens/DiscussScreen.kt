package debating.ground.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.MODES
import debating.ground.app.data.Session
import debating.ground.app.data.canBranch
import debating.ground.app.flow.AppState
import debating.ground.app.room.link
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.fadeEdges
import debating.ground.app.ui.fadeUnder
import debating.ground.app.ui.glass
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Serif
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 讨论页：和网页版 src/screens/Session.tsx 一致。顶上是标题和要点进度（辩论有台面、画布有路径），中间是讨论记录，底下是输入栏。
// 讨论记录在 DiscussThread.kt，输入栏和语音按钮在 DiscussComposer.kt，各种面板在 DiscussSheets.kt。

@Composable
fun DiscussScreen(st: AppState, model: AppModel) {
    val l = model.learning
    val s = st.session
    if (s == null) {
        EmptyDiscuss { l.go("learn") }
        return
    }
    val live by model.live.collectAsState()
    val ui = st.ui
    val debate = s.mode == "debate"
    val busy = ui.busy != null
    // 辩论赛程没走完就结束，会跳过剩下的环节（包括总结陈词），先确认一下
    val onFinish = { if (debate && s.cursor < s.turns.size) l.openSheet("finish") else model.run { finish() } }
    Box(Modifier.fillMaxSize()) {
        // 顶栏和输入栏都悬浮在讨论记录上：记录可以滚到它们下面，滚到附近时淡出，不垫整条底色
        val density = LocalDensity.current
        var top by remember { mutableStateOf(0.dp) }
        var composer by remember { mutableStateOf(0.dp) }
        Box(Modifier.fillMaxSize()) {
            Thread(s, st, live, model, Modifier.fillMaxSize().fadeUnder(top, composer), top, composer)
            Box(Modifier.align(Alignment.TopCenter).onSizeChanged { top = with(density) { it.height.toDp() } }) {
                SessionTop(s, busy, onFinish, model)
            }
            Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { composer = with(density) { it.height.toDp() } }) {
                ComposerArea(s, st, live, model)
            }
        }
        Sheets(s, st, live, model)
    }
}

@Composable
private fun EmptyDiscuss(onStart: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize()) {
        TopBar("讨论")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("还没有进行中的讨论", style = TextStyles.body.copy(fontWeight = FontWeight.Bold, fontSize = TextSize.lg), color = t.ink.color())
            Text("写下一个概念，选一种讨论方式，就可以开始。", style = TextStyles.small, color = t.muted.color(), textAlign = TextAlign.Center)
            Spacer(Modifier.size(4.dp))
            PrimaryButton("开始学习", onStart)
        }
    }
}

/* ---------- 顶部：标题和要点进度 ---------- */

@Composable
private fun SessionTop(s: Session, busy: Boolean, onFinish: () -> Unit, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBar(s.concept, "${MODES[s.mode]?.name ?: ""} · ${s.topic}", onBack = { l.go("learn") }) {
            if (s.finished) {
                SecondaryButton("看复盘", { l.go("review") }, small = true)
            } else if (s.awaitingReview != true) {
                // 多人讨论由房主结束；还在等人时不能结束
                val room = s.link
                if (room == null || (room.host && room.status != "lobby")) {
                    SecondaryButton(if (room != null) "结束讨论" else "结束复盘", onFinish, enabled = !busy && room?.aiBusy == null, small = true)
                }
            }
        }
        PointsProgress(s, model)
        if (s.link != null) RoomBar(s, model)
        if (s.mode == "debate") DebateBoard(s, model)
        // 路径条：旧的知识画布一直显示；追问和圆桌展开过分支才显示（#142）
        if (s.canBranch() && (s.mode == "canvas" || !s.branches.isNullOrEmpty())) CanvasPath(s, busy, model)
    }
}

@Composable
private fun PointsProgress(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val row = rememberLazyListState()
    // 新点亮的要点滚到看得见的地方
    val lit = s.covered.lastOrNull()
    // 新点亮一个要点：手里也有一下确认感（打开讨论时已经亮着的不算）
    val haptic = LocalHapticFeedback.current
    val seen = remember(s.id) { intArrayOf(s.covered.size) }
    LaunchedEffect(s.covered.size) {
        if (s.covered.size > seen[0]) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        seen[0] = s.covered.size
        if (lit != null) row.animateScrollToItem(lit)
    }
    // 要点进度是一条悬浮的磨砂胶囊
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), 6.dp).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("${s.covered.size}")
                pushStyle(SpanStyle(fontSize = 12.sp, color = t.muted.color(), fontWeight = FontWeight.Medium))
                append("/${s.keyPoints.size}")
                pop()
            },
            style = TextStyles.body.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp),
            color = t.brand.color(),
            modifier = Modifier.semantics { contentDescription = "要点进度 ${s.covered.size}/${s.keyPoints.size}" },
        )
        LazyRow(
            Modifier.weight(1f).fadeEdges({
                row.canScrollBackward
            }, { row.canScrollForward }),
            state = row,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(s.keyPoints) { i, k -> PointChip(k, i in s.covered) }
        }
        if (!s.finished) {
            val pending = s.proposedPoints?.size ?: 0
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { model.learning.openSheet("points") }
                    .semantics { contentDescription = if (pending > 0) "补充要点，$pending 条待确认" else "补充要点" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(lineIcon("plus", IconPaths.PLUS), null, Modifier.size(18.dp), tint = t.ink.color())
                if (pending > 0) {
                    Text(
                        "$pending",
                        color = t.brandInk.color(),
                        style = TextStyles.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(
                            Alignment.TopEnd,
                        ).offset((-2).dp, 4.dp).background(t.accent.color(), CircleShape).sizeIn(minWidth = 16.dp).padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

// 要点小标签（.pt）：没讲到时是虚线框，讲到了点亮
@Composable
private fun PointChip(text: String, lit: Boolean) {
    val t = LocalTokens.current
    val bg by animateColorAsState(if (lit) t.lit.color() else Color.Transparent, tween(500), label = "pt-bg")
    val fg by animateColorAsState(if (lit) t.litInk.color() else t.muted.color(), tween(500), label = "pt-fg")
    val line = t.line.color()
    Text(
        text,
        color = fg,
        style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal),
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .drawBehind {
                if (!lit) {
                    val r = 20.dp.toPx().coerceAtMost(size.height / 2)
                    drawRoundRect(
                        line,
                        cornerRadius = CornerRadius(r),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
