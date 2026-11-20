package debating.ground.app.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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
import debating.ground.app.data.Reflection
import debating.ground.app.data.Session
import debating.ground.app.data.SystemLine
import debating.ground.app.data.branchesFrom
import debating.ground.app.data.canBranch
import debating.ground.app.data.levelEntries
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Live
import debating.ground.app.flow.seatName
import debating.ground.app.flow.voiceReady
import debating.ground.app.room.link
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.Input
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.RevealText
import debating.ground.app.ui.RiseIn
import debating.ground.app.ui.mix
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Serif
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors

// 讨论页的讨论记录：消息气泡、生成中的气泡、小结卡（DiscussScreen.kt 的一部分）。

/* ---------- 讨论记录 ---------- */

@Composable
internal fun Thread(s: Session, st: AppState, live: Live?, model: AppModel, modifier: Modifier, top: Dp, bottom: Dp) {
    val l = model.learning
    val ui = st.ui
    val debate = s.mode == "debate"
    // 知识画布只显示正在看的这一层（主干或某个分支）
    val shown = if (s.canBranch()) s.levelEntries(s.at) else s.entries
    val lastAi = s.entries.lastOrNull { it is ChatMessage && it.human != true }?.id
    val liveMsg = live?.takeIf { it.kind == "msg" && s.entries.none { e -> e.id == it.id } }
    // 朗读：后端配好讯飞时才有（从状态算出来往下传，后端状态变了条目才会跟着重画）
    val voice = model.speech != null && (s.link?.voiceAvailable ?: st.voiceReady())
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
            liveMsg != null -> item(key = liveMsg.id) { RiseIn(liveMsg.id, seen) { LiveMessage(s, liveMsg) { if (s.room == null) l.cancelAI() } } }

            ui.busy != null -> item(key = "busy") { RiseIn("busy-${s.entries.size}", seen) { Typing(ui.busy) { l.cancelAI() } } }

            debate -> item(key = "lectern") { RiseIn("lectern-${s.cursor}", seen) { NextLectern(s) } }
        }
        val rev = shown.asReversed()
        for ((k, m) in rev.withIndex()) {
            // 知识画布：从这句话展开过的分支挂在它下面（倒着排，所以先放）
            if (s.canBranch() && m is ChatMessage && s.branchesFrom(m.id).isNotEmpty()) {
                item(key = "chips-${m.id}") { BranchChips(s, m.id, m.human == true, ui.busy != null, model) }
            }
            item(key = m.id) {
                RiseIn(m.id, seen) {
                    EntryView(
                        m,
                        s,
                        showTip = m.id == lastAi && s.learnerTurns == 0 && !s.finished,
                        live = live,
                        busy = ui.busy != null,
                        model = model,
                        voice = voice,
                    )
                }
            }
            // 辩论：环节变了就画一条分隔线（倒着排，所以放在这条后面）
            if (debate && m is ChatMessage && m.stage != null) {
                val prev = rev.drop(k + 1).firstNotNullOfOrNull { (it as? ChatMessage)?.stage }
                if (m.stage != prev) item(key = "stage-${m.id}") { StageDivider(s.stages.getOrNull(m.stage)?.name.orEmpty()) }
            }
        }
        if (s.canBranch() && s.at != null) item(key = "fork-head-${s.at}") { ForkHeader(s) }
    }
}

@Composable
private fun EntryView(m: Entry, s: Session, showTip: Boolean, live: Live?, busy: Boolean, model: AppModel, voice: Boolean) {
    when (m) {
        is SystemLine -> if (m.back != null) {
            BackLine(m, s, busy, model)
        } else {
            val topic = Regex("^(讨论题|辩题)：([\\s\\S]+)$").find(m.text)
            if (topic != null) TopicLine(topic.groupValues[1], topic.groupValues[2]) else SysLine(m.text)
        }

        is Reflection -> ReflectCard(m, s, live, busy, model)

        is ChatMessage -> MessageView(m, s, showTip, model, voice)
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
private fun MessageView(m: ChatMessage, s: Session, showTip: Boolean, model: AppModel, voice: Boolean) {
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
                // 以前“换我来讲”留下的发言（#142 去掉了这个开关），旧讨论里照样标出来
                "讲给伙伴听" -> "讲解"

                "当学生" -> "当学生"

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
        // 多人讨论：同学的发言
        if (m.memberId != null && !me && !debate) "同学" else "",
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
            MetaLine(label, meta, if (m.skipped == true) "已跳过" else null) {
                // 朗读：后端配好讯飞时才出现
                val speech = model.speech
                if (speech != null && voice && m.skipped != true) VoiceButton(speech, m, s, label, model)
            }
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
                    (if (s.canBranch()) "轻点发言可以追问，或从这句展开一个分支单独聊。" else "轻点发言可以追问：没听懂、举例、反驳、分辨事实。") +
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
