package debating.ground.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Entry
import debating.ground.app.data.MODES
import debating.ground.app.data.Reflection
import debating.ground.app.data.Session
import debating.ground.app.data.SystemLine
import debating.ground.app.data.branchById
import debating.ground.app.data.branchesFrom
import debating.ground.app.data.levelEntries
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Live
import debating.ground.app.flow.QUICK
import debating.ground.app.flow.STUDENT
import debating.ground.app.flow.TEACHING
import debating.ground.app.flow.seatName
import debating.ground.app.ui.Appear
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.LocalFloating
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.Quote
import debating.ground.app.ui.RevealText
import debating.ground.app.ui.RiseIn
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Sheet
import debating.ground.app.ui.SheetHost
import debating.ground.app.ui.SheetSpec
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.fadeUnder
import debating.ground.app.ui.glass
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.mix
import debating.ground.app.ui.theme.LINE_HEIGHT_SERIF
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Serif
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors

// 讨论页：和网页版 src/screens/Session.tsx 一致。顶上是要点进度，中间是讨论记录，底下是输入栏。
// 知识画布的分支、辩论台面和赛程条在 M4c 加入；这里先让追问、圆桌、讲给 AI 听和辩论的基本发言走通。

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
                SecondaryButton("结束复盘", onFinish, enabled = !busy, small = true)
            }
        }
        PointsProgress(s, model)
        if (s.mode == "debate") DebateBoard(s, model)
        if (s.mode == "canvas") CanvasPath(s, busy, model)
    }
}

@Composable
private fun PointsProgress(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val row = rememberLazyListState()
    // 新点亮的要点滚到看得见的地方
    val lit = s.covered.lastOrNull()
    LaunchedEffect(s.covered.size) { if (lit != null) row.animateScrollToItem(lit) }
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
        LazyRow(Modifier.weight(1f), state = row, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

/* ---------- 讨论记录 ---------- */

@Composable
private fun Thread(s: Session, st: AppState, live: Live?, model: AppModel, modifier: Modifier, top: Dp, bottom: Dp) {
    val l = model.learning
    val ui = st.ui
    val debate = s.mode == "debate"
    // 知识画布只显示正在看的这一层（主干或某个分支）
    val shown = if (s.mode == "canvas") s.levelEntries(s.at) else s.entries
    val lastAi = s.entries.lastOrNull { it is ChatMessage && it.human != true }?.id
    val liveMsg = live?.takeIf { it.kind == "msg" && s.entries.none { e -> e.id == it.id } }
    val list = rememberLazyListState()
    // 打开讨论时已经在的条目不做入场动画；之后新来的淡入、轻轻上浮（翻回去看旧的不重播）
    val seen = remember(s.id) { HashSet<String>().apply { shown.forEach { add(it.id) } } }

    // 倒着排：最新的在最下面，文字变长时自动贴着底部；有新东西时滚回底部
    LaunchedEffect(shown.size, ui.busy, ui.hint, liveMsg?.id, s.at) { list.animateScrollToItem(0) }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp + top, bottom = 12.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
    ) {
        when {
            // 生成中的气泡和完成后的正式发言用同一个 key：衔接时不会再淡入一次
            liveMsg != null -> item(key = liveMsg.id) { RiseIn(liveMsg.id, seen) { LiveMessage(s, liveMsg) { l.cancelAI() } } }

            ui.busy != null -> item(key = "busy") { RiseIn("busy-${s.entries.size}", seen) { Typing(ui.busy) { l.cancelAI() } } }

            debate -> item(key = "lectern") { RiseIn("lectern-${s.cursor}", seen) { NextLectern(s) } }
        }
        val rev = shown.asReversed()
        for ((k, m) in rev.withIndex()) {
            // 知识画布：从这句话展开过的分支挂在它下面（倒着排，所以先放）
            if (s.mode == "canvas" && m is ChatMessage && s.branchesFrom(m.id).isNotEmpty()) {
                item(key = "chips-${m.id}") { BranchChips(s, m.id, m.human == true, ui.busy != null, model) }
            }
            item(key = m.id) {
                RiseIn(m.id, seen) {
                    EntryView(m, s, showTip = m.id == lastAi && s.learnerTurns == 0 && !s.finished, live = live, busy = ui.busy != null, model = model)
                }
            }
            // 辩论：环节变了就画一条分隔线（倒着排，所以放在这条后面）
            if (debate && m is ChatMessage && m.stage != null) {
                val prev = rev.drop(k + 1).firstNotNullOfOrNull { (it as? ChatMessage)?.stage }
                if (m.stage != prev) item(key = "stage-${m.id}") { StageDivider(s.stages.getOrNull(m.stage)?.name.orEmpty()) }
            }
        }
        if (s.mode == "canvas" && s.at != null) item(key = "fork-head-${s.at}") { ForkHeader(s) }
    }
}

@Composable
private fun EntryView(m: Entry, s: Session, showTip: Boolean, live: Live?, busy: Boolean, model: AppModel) {
    when (m) {
        is SystemLine -> if (m.back != null) {
            BackLine(m, s, busy, model)
        } else {
            val topic = Regex("^(讨论题|辩题)：([\\s\\S]+)$").find(m.text)
            if (topic != null) TopicLine(topic.groupValues[1], topic.groupValues[2]) else SysLine(m.text)
        }

        is Reflection -> ReflectCard(m, s, live, busy, model)

        is ChatMessage -> MessageView(m, s, showTip, model)
    }
}

@Composable
private fun SysLine(text: String) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = TextStyles.small.copy(fontSize = 12.5.sp, lineHeight = 1.6.em),
            color = t.muted.color(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp).background(t.bg.color(), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

// 讨论题像写在黑板上的题目：居中、衬线，上面一行小字说明这是什么
@Composable
private fun TopicLine(label: String, text: String) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = TextStyles.small.copy(fontSize = TextSize.xs, letterSpacing = 0.08.em), color = t.muted.color())
        Text(
            text,
            style = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, fontSize = TextSize.lg, lineHeight = 1.6.em),
            color = t.ink.color(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StageDivider(name: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).size(height = 1.dp, width = 0.dp).background(t.line.color()))
        Text(
            name,
            style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = t.muted.color(),
            modifier = Modifier.background(t.bg.color(), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Box(Modifier.weight(1f).size(height = 1.dp, width = 0.dp).background(t.line.color()))
    }
}

/** 发言里 AI 标出的术语加上下划线，点一下查词。 */
private fun withTerms(text: String, terms: List<String>?, onTerm: (String) -> Unit): AnnotatedString {
    if (terms.isNullOrEmpty()) return AnnotatedString(text)
    val list = terms.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    if (list.isEmpty()) return AnnotatedString(text)
    val re = Regex(list.joinToString("|") { Regex.escape(it) })
    val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (hit in re.findAll(text)) {
            append(text.substring(at, hit.range.first))
            val term = hit.value
            withLink(LinkAnnotation.Clickable(term, style) { onTerm(term) }) { append(term) }
            at = hit.range.last + 1
        }
        append(text.substring(at))
    }
}

private data class BubbleLook(val bg: Color, val fg: Color, val border: Color, val borderWidth: Float, val right: Boolean)

@Composable
private fun bubbleLook(m: ChatMessage, debate: Boolean): BubbleLook {
    val t = LocalTokens.current
    val me = m.human == true
    if (debate) {
        // 辩论按阵营分左右（正方左、反方右），不按“是不是我”
        val a = m.team != "B"
        val (bg, fg) = toneColors(if (a) "a" else "b")
        return BubbleLook(bg.mix(0.45f, t.raised.color()), t.ink.color(), if (me) fg else fg.mix(0.22f, t.line.color()), if (me) 1.5f else 1f, !a)
    }
    return if (me) {
        BubbleLook(
            t.brand.color(),
            t.brandInk.color(),
            t.brand.color(),
            1f,
            true,
        )
    } else {
        BubbleLook(t.raised.color(), t.ink.color(), t.line.color(), 1f, false)
    }
}

@Composable
private fun Avatar(text: String, bg: Color, fg: Color, serif: Boolean = true) {
    Box(Modifier.size(34.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = fg,
            style = TextStyles.body.copy(
                fontFamily = if (serif) Serif else TextStyles.body.fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (serif) 14.sp else 13.sp,
                lineHeight = 14.sp,
            ),
        )
    }
}

@Composable
private fun MessageRow(right: Boolean, avatar: @Composable () -> Unit, body: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, if (right) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        if (!right) avatar()
        // 发言气泡限宽（网页版 --read-w）：宽屏上一行太长读起来累
        Box(Modifier.weight(1f, fill = false).widthIn(max = 620.dp), contentAlignment = if (right) Alignment.TopEnd else Alignment.TopStart) { body() }
        if (right) avatar()
    }
}

@Composable
private fun MessageView(m: ChatMessage, s: Session, showTip: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val me = m.human == true
    val debate = s.mode == "debate"
    val persona = s.personas.find { it.id == m.who }
    val look = bubbleLook(m, debate)
    val seat = if (debate) m.name.replace(Regex("（.*）$"), "") else ""
    val target = if (debate && m.target != null) s.seats.find { it.id == m.target } else null
    val label = when {
        me -> if (debate) "$seat · 你" else "你"
        debate -> m.name
        else -> m.name + (persona?.title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
    }
    val meta = listOf(
        if (debate) {
            (m.action ?: "") + (target?.let { " → ${seatName(it)}" } ?: "")
        } else {
            when (m.action) {
                TEACHING -> "讲解"
                STUDENT -> "当学生"
                else -> ""
            }
        },
        if (m.source == "demo" && !me) {
            "演示"
        } else if (m.source == "api" && !me) {
            m.model.orEmpty()
        } else {
            ""
        },
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    val (avBg, avFg) = when {
        debate -> toneColors(if (m.team == "B") "b" else "a")
        me -> t.brand.color() to t.brandInk.color()
        else -> toneColors(persona?.tone ?: "a")
    }
    val tappable = !me && m.skipped != true && !s.finished
    MessageRow(look.right, {
        Avatar(
            if (debate) {
                m.who.drop(1)
            } else if (me) {
                "我"
            } else {
                m.name.take(1)
            },
            avBg,
            avFg,
            serif = !me || debate,
        )
    }) {
        Column(horizontalAlignment = if (look.right) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetaLine(label, meta, if (m.skipped == true) "已跳过" else null)
            val shape = if (look.right) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
            Box(
                Modifier
                    .background(look.bg, shape)
                    .border(look.borderWidth.dp, look.border, shape)
                    .then(if (tappable) Modifier.clickable(role = Role.Button) { l.openSheet(m.id) } else Modifier)
                    .alpha(if (m.skipped == true) 0.6f else 1f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                // 点在下划线词上就查词，否则（点在别处）打开追问面板
                RevealText(
                    m.id,
                    m.text,
                    live = false,
                    style = BubbleText,
                    color = look.fg,
                    final = withTerms(m.text, m.terms) { term ->
                        model.run { openTerm(term, m.id) }
                    },
                )
            }
            if (showTip) {
                Text(
                    (if (s.mode == "canvas") "轻点发言可以追问，或从这句展开一个分支单独聊。" else "轻点发言可以追问：没听懂、举例、反驳、分辨事实。") +
                        if (!m.terms.isNullOrEmpty()) "带下划线的词，点一下看解释。" else "",
                    style = TextStyles.small.copy(fontSize = 12.sp),
                    color = t.accent.color(),
                )
            }
        }
    }
}

private val BubbleText = TextStyles.body.copy(fontSize = 15.5.sp, lineHeight = 1.75.em)

@Composable
private fun MetaLine(label: String, meta: String, extra: String? = null, trailing: @Composable () -> Unit = {}) {
    val t = LocalTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = t.ink.color())
        if (meta.isNotEmpty()) Text(meta, style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color())
        if (extra != null) Text(extra, style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color())
        trailing()
    }
}

// 生成中的 AI 发言：和正式发言长得一样，文字逐字出现
@Composable
private fun LiveMessage(s: Session, live: Live, onStop: () -> Unit) {
    val t = LocalTokens.current
    if (live.text.isEmpty()) return Typing(live.label, onStop)
    val debate = s.mode == "debate"
    val persona = s.personas.find { it.id == live.who }
    val seat = if (debate) s.seats.find { it.id == live.who } else null
    val (avBg, avFg) = toneColors(if (debate) (if (seat?.team == "B") "b" else "a") else persona?.tone ?: "a")
    val right = debate && seat?.team == "B"
    val bubbleBg = if (debate) avBg.mix(0.45f, t.raised.color()) else t.raised.color()
    val border = if (debate) avFg.mix(0.22f, t.line.color()) else t.line.color()
    MessageRow(right, { Avatar(if (debate) (live.who ?: "").drop(1) else persona?.name?.take(1) ?: "…", avBg, avFg) }) {
        Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = if (debate) {
                seat?.let { seatName(it) } ?: live.label
            } else {
                (persona?.name ?: "") + (persona?.title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            }
            MetaLine(name, "正在说") { StopLink(onStop) }
            val shape = if (right) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
            Box(Modifier.background(bubbleBg, shape).border(1.dp, border, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
                RevealText(live.id, live.text, live = true, style = BubbleText, color = t.ink.color())
            }
        }
    }
}

@Composable
private fun StopLink(onStop: () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onStop).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text("停止", style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = t.accent.color())
    }
}

// “正在思考”：三个点轻轻跳
@Composable
private fun Typing(label: String, onStop: () -> Unit) {
    val t = LocalTokens.current
    MessageRow(false, { Avatar("…", t.bg.color(), t.muted.color()) }) {
        Row(
            Modifier.background(
                t.raised.color(),
                RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            ).border(1.dp, t.line.color(), RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Dots()
            Text(label, style = TextStyles.small, color = t.muted.color())
            StopLink(onStop)
        }
    }
}

@Composable
private fun Dots() {
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val phase = if (motion) {
        rememberInfiniteTransition(label = "dots").animateFloat(0f, 1f, infiniteRepeatable(tween(1200)), label = "dots-phase").value
    } else {
        0.4f
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            // 和网页版的 bounce 一样：每个点错开 0.15 秒，跳到最高时最亮
            val p = ((phase - i * 0.125f) % 1f + 1f) % 1f
            val k = if (p < 0.4f) {
                p / 0.4f
            } else if (p < 0.8f) {
                (0.8f - p) / 0.4f
            } else {
                0f
            }
            Box(
                Modifier.size(6.dp).graphicsLayer {
                    translationY = -3.dp.toPx() * k
                    alpha = 0.3f + 0.7f * k
                }.background(t.muted.color(), CircleShape),
            )
        }
    }
}

/* ---------- 小结卡 ---------- */

@Composable
private fun ReflectCard(m: Reflection, s: Session, live: Live?, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val streaming = live?.takeIf { it.kind == "reflect" && it.id == m.id && it.text.isNotEmpty() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.lit.color().mix(0.6f, t.raised.color()), RoundedCornerShape(14.dp))
            .border(1.dp, t.litInk.color().copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("停一下，用自己的话说说", style = TextStyles.knowledge.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp), color = t.litInk.color())
        if (m.text.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = t.muted.color(), fontFamily = TextStyles.small.fontFamily, fontSize = TextSize.sm)) { append("我的小结：") }
                    append(m.text)
                },
                style = TextStyles.knowledge,
                color = t.ink.color(),
            )
            Feedback { RevealText(m.id, m.feedback, live = false, style = FeedbackText, color = t.ink.color()) }
        } else {
            Text("你现在怎么理解「${s.concept}」？一两句话就好。", style = TextStyles.small, color = t.muted.color())
            Input(
                m.draft,
                { l.setReflectionDraft(m.id, it) },
                "我的小结",
                singleLine = false,
                minLines = 2,
                maxLength = 1000,
                style = TextStyles.knowledge,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("跳过", { l.dropReflection(m.id) }, small = true)
                PrimaryButton("提交小结", {
                    model.run { submitReflection(m.id) }
                }, enabled = !busy && m.draft.isNotBlank(), small = true)
            }
            if (streaming != null) Feedback { RevealText(m.id, streaming.text, live = true, style = FeedbackText, color = t.ink.color()) }
        }
    }
}

private val FeedbackText = TextStyles.small.copy(lineHeight = 1.75.em)

@Composable
private fun Feedback(content: @Composable () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth().background(t.raised.color(), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}

/* ---------- 底部输入栏 ---------- */

@Composable
private fun ComposerArea(s: Session, st: AppState, live: Live?, model: AppModel) {
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
        DraftInput(s.draft, placeholder, busy, onChange = { l.setDraft(it) }) { model.run { send(s.draft) } }
    } else {
        PrimaryButton("让 ${turn?.let { seatName(it.role) }.orEmpty()} 发言", { model.run { generateSeat() } }, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

@Composable
private fun TurnBanner(dot: Color, text: AnnotatedString.Builder.() -> Unit) {
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
private fun Tool(text: String, icon: String?, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
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
private fun HintCard(hint: String?, live: Live?, onClose: () -> Unit) {
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
private fun DraftInput(draft: String, placeholder: String, busy: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
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

/* ---------- 面板：追问、讲解、查词、要点、提前结束 ---------- */

@Composable
private fun Sheets(s: Session, st: AppState, live: Live?, model: AppModel) {
    val l = model.learning
    val ui = st.ui
    val sheetMsg = s.entries.find { it.id == ui.sheet } as? ChatMessage
    // 每个面板捕获打开时的数据：关上时托管它的 SheetHost 让它带着原来的内容沉下去
    val spec = when {
        ui.explain != null -> SheetSpec("explain") {
            ExplainSheet(ui.explain.label, ui.explain.quote, ui.explain.text, live, s.room != null) { l.closeExplain() }
        }

        ui.term != null -> SheetSpec("term") { TermSheet(s, st, model) }

        sheetMsg != null -> SheetSpec("quick-${sheetMsg.id}") { QuickSheet(s, sheetMsg, ui.busy != null, model) }

        ui.sheet == "points" -> SheetSpec("points") { PointsSheet(s, st, model) }

        ui.sheet == "finish" -> SheetSpec("finish") { FinishSheet(s, model) }

        ui.sheet == "roster" && s.mode == "debate" -> SheetSpec("roster") { RosterSheet(s, model) }

        ui.sheet == "tree" && s.mode == "canvas" -> SheetSpec("tree") { GraphSheet(s, ui.busy != null, model) }

        ui.sheet == "back" && s.mode == "canvas" -> SheetSpec("back") { BackSheet(s, model) }

        else -> null
    }
    SheetHost(spec)
}

@Composable
private fun ExplainSheet(label: String, quote: String, text: String, live: Live?, room: Boolean, onClose: () -> Unit) {
    val t = LocalTokens.current
    Sheet("$label · 讲解助手", onClose) {
        Quote(quote)
        val streaming = live?.takeIf { it.kind == "explain" && it.text.isNotEmpty() }
        when {
            streaming != null -> RevealText("explain", streaming.text, live = true, style = SheetText, color = t.ink.color())
            text.isNotEmpty() -> RevealText("explain", text, live = false, style = SheetText, color = t.ink.color())
            else -> Text("正在看这句话…", style = SheetText, color = t.muted.color())
        }
        FinePrint(if (room) "讲解只有你能看到，不占用发言轮次。" else "讲解不进入比赛记录，也不占用发言轮次。")
    }
}

private val SheetText = TextStyles.body.copy(fontSize = 15.5.sp, lineHeight = 1.8.em)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSheet(s: Session, m: ChatMessage, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val debate = s.mode == "debate"
    Sheet(if (s.mode == "canvas" && !s.finished) "追问或展开" else "追问这句", { l.openSheet(null) }) {
        Quote(if (m.text.length > 80) m.text.take(80) + "…" else m.text)
        Column(Modifier.fillMaxWidth().background(t.raised.color(), RoundedCornerShape(14.dp)).border(1.dp, t.line.color(), RoundedCornerShape(14.dp))) {
            QUICK.forEachIndexed { i, q ->
                if (i > 0) Box(Modifier.fillMaxWidth().size(height = 1.dp, width = 0.dp).background(t.line.color()))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) {
                        model.run { quick(q.kind, m.id) }
                    }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(q.label, style = TextStyles.body, color = t.ink.color(), modifier = Modifier.weight(1f))
                    Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(18.dp), tint = t.muted.color())
                }
            }
        }
        if (!m.terms.isNullOrEmpty()) {
            SectionLabel("这句里的新词")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.terms.forEach { term -> debating.ground.app.ui.Chip(term, on = false, onClick = { model.run { openTerm(term, m.id) } }) }
            }
        }
        if (s.mode == "canvas" && !s.finished) ForkForm(m, busy, model)
        if (debate) FinePrint("辩论中，解释由讲解助手单独给出，不打断赛程。")
    }
}

@Composable
private fun TermSheet(s: Session, st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val term = st.ui.term ?: return
    Sheet(term.term, {
        model.learning.closeTerm()
    }, titleStyle = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, fontSize = TextSize.xl, lineHeight = 1.3.em, letterSpacing = 0.03.em)) {
        if (term.definition.isNotEmpty()) {
            Text(term.definition, style = TextStyles.knowledge, color = t.ink.color())
            if (term.relation.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("和「${s.concept}」的关系", style = TextStyles.small.copy(fontWeight = FontWeight.SemiBold), color = t.ink.color())
                    Text(term.relation, style = TextStyles.small.copy(lineHeight = 1.75.em), color = t.ink.color())
                }
            }
            FinePrint((if (s.review != null) "已存进这次的知识卡片。" else "已记下，复盘后会和这次的知识卡片存在一起。") + "查词不占用发言轮次。")
            if (s.mode == "canvas" && !s.finished) {
                SecondaryButton(
                    "展开一个分支，聊聊「${term.term}」",
                    { model.run { openBranch(term.messageId, term.term) } },
                    Modifier.fillMaxWidth(),
                    enabled =
                    st.ui.busy == null,
                )
            }
        } else {
            Text("正在查这个词……", style = TextStyles.knowledge, color = t.muted.color())
        }
    }
}

// 补充讨论要点：AI 提议的先待确认，采纳后才点亮追踪、进入复盘
@Composable
private fun PointsSheet(s: Session, st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val debate = s.mode == "debate"
    val full = s.keyPoints.size >= 6
    var text by rememberSaveable { mutableStateOf("") }
    Sheet(if (debate) "核心争点" else "要弄懂的要点", { l.openSheet(null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.keyPoints.forEachIndexed { i, k ->
                val lit = i in s.covered
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}.", style = TextStyles.knowledge, color = if (lit) t.litInk.color() else t.muted.color(), modifier = Modifier.width(22.dp))
                    Text(k, style = TextStyles.knowledge, color = if (lit) t.litInk.color() else t.ink.color())
                }
            }
        }
        val proposals = s.proposedPoints.orEmpty()
        if (proposals.isNotEmpty()) {
            SectionLabel("待确认")
            proposals.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.text, style = TextStyles.knowledge.copy(lineHeight = 1.6.em), color = t.ink.color())
                        Text(if (p.source == "ai") "AI 提议" else "${p.proposer}提议", style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color())
                    }
                    GhostButton("忽略", { l.decidePoint(p.id, false) }, small = true)
                    SecondaryButton("采纳", { l.decidePoint(p.id, true) }, enabled = !full, small = true)
                }
            }
        }
        if (full) {
            FinePrint("要点已经有 6 个了，先讲透这些。")
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Input(text, { text = it }, "新的要点", placeholder = if (debate) "写一个值得交锋的争点" else "写一个还想弄懂的要点", maxLength = 40, onDone = {
                        if (l.proposePoint(text)) {
                            text =
                                ""
                        }
                    })
                }
                SecondaryButton("加入", { if (l.proposePoint(text)) text = "" }, enabled = text.isNotBlank())
            }
            GhostButton("让 AI 提议一条", { model.run { suggestPoint() } }, Modifier.fillMaxWidth(), enabled = st.ui.busy == null, icon = IconPaths.BULB)
        }
        FinePrint(if (debate) "争点是交锋的方向，不改变赛程；采纳后会点亮追踪，并进入复盘。" else "采纳后会点亮追踪，并进入复盘。")
    }
}

@Composable
private fun FinishSheet(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    Sheet("现在结束比赛？", { l.openSheet(null) }) {
        Text("还有 ${s.turns.size - s.cursor} 次发言没有进行，包括后面的总结陈词。结束后你只能按已有的发言复盘。", style = SheetText, color = t.ink.color())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("继续比赛", { l.openSheet(null) }, Modifier.weight(1f))
            PrimaryButton(
                "结束并复盘",
                {
                    l.openSheet(null)
                    model.run { finish() }
                },
                Modifier.weight(1f),
            )
        }
    }
}
