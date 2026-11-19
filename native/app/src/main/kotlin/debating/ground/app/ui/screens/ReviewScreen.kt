package debating.ground.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.MODES
import debating.ground.app.data.Misconception
import debating.ground.app.data.Point
import debating.ground.app.data.Reflection
import debating.ground.app.data.STATUS_LABEL
import debating.ground.app.data.SessionReview
import debating.ground.app.data.Stance
import debating.ground.app.data.TermCard
import debating.ground.app.data.sessionMarkdown
import debating.ground.app.flow.AppState
import debating.ground.app.ui.Appear
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GlassIconButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LINE_HEIGHT_SERIF
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Serif
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 理解复盘：和网页版 src/screens/Review.tsx 一致。复盘只依据学习者本人的发言和小结（约定 4）。

/** 导出：交给系统的分享面板（存成文件、发给别的 App 都从这里走）。导出内容里没有后端地址和口令（约定 1）。 */
fun shareMarkdown(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}

@Composable
fun ExportButton(label: String, onClick: () -> Unit) = GlassIconButton(IconPaths.DOWNLOAD, label, onClick)

@Composable
fun ReviewScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val s = st.session
    val context = LocalContext.current
    if (s == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("理解复盘", onBack = { l.go("learn") })
            EmptyState("还没有复盘", "完成一次讨论后，这里会显示你弄懂了多少。")
        }
        return
    }
    val r = s.review
    val hasInput = s.entries.any { (it is ChatMessage && it.human == true) || (it is Reflection && it.text.isNotEmpty()) }
    // 这次复盘沉淀进了哪篇概念笔记
    val note = l.settledInto(s)
    val busy = st.ui.busy != null
    WithFloatingDock(dock = {
        if (r != null && note != null) {
            ErrorBanner(st.ui.error) { l.clearError() }
            BusyStrip(st.ui.busy) { l.cancelAI() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("回看讨论", { l.go("session") })
                PrimaryButton("从薄弱点再讨论", { model.run { discussAgain(note) } }, Modifier.weight(1f), enabled = !busy)
            }
        }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar("理解复盘", "${s.concept} · ${MODES[s.mode]?.name.orEmpty()}", onBack = { l.go("session") }) {
                ExportButton("导出讨论记录和复盘") { shareMarkdown(context, "${s.concept} · 讨论记录", sessionMarkdown(s)) }
            }
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (r == null) {
                    Panel {
                        when {
                            !hasInput -> {
                                Text("这次讨论里还没有你的发言，没有可以复盘的内容。", style = TextStyles.body, color = t.ink.color())
                                PrimaryButton("回到讨论", { l.resume() }, Modifier.fillMaxWidth())
                            }

                            busy -> Text("正在复盘整场讨论……", style = TextStyles.body, color = t.muted.color())

                            else -> {
                                Text("复盘还没有生成。", style = TextStyles.body, color = t.ink.color())
                                PrimaryButton("生成理解复盘", { model.run { generateReview() } }, Modifier.fillMaxWidth())
                            }
                        }
                        ErrorBanner(st.ui.error) { l.clearError() }
                    }
                } else {
                    val mastered = r.points.count { it.status == "mastered" }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Ring(mastered, r.points.size)
                        Column(Modifier.weight(1f)) {
                            Text(r.oneLine, style = DisplaySm, color = t.ink.color())
                            Text(
                                (if (r.source == "api") "由 ${r.model} 根据你的发言和小结生成，供参考" else "演示模式：预设示例或规则统计"),
                                style = TextStyles.small.copy(fontSize = 12.sp),
                                color = t.muted.color(),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    if (!r.summary.isNullOrEmpty() || !r.useWhen.isNullOrEmpty()) {
                        Panel {
                            if (!r.summary.isNullOrEmpty()) {
                                SectionLabel("用你的话说")
                                Text(r.summary, style = TextStyles.knowledge, color = t.ink.color())
                            }
                            if (!r.useWhen.isNullOrEmpty()) UseWhen(r.useWhen)
                        }
                    }
                    ReviewBody(r, s.glossary)
                    if (note != null) {
                        Settled(
                            note.concept,
                            (
                                if (note.sources.size > 1) {
                                    "这篇笔记来自 ${note.sources.size} 次讨论，这次的理解合并进去了"
                                } else {
                                    "新的一篇：以后再讨论这个概念，理解会接着沉淀进来"
                                }
                                ) + (if (note.checks.isNotEmpty()) "，有 ${note.checks.size} 道自检题" else ""),
                        ) { l.openCard(note.id) }
                    }
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

val DisplaySm = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = TextSize.lg, lineHeight = 1.65.em)

// 掌握度圆环：复盘出来时从 0 转到实际比例
@Composable
fun Ring(done: Int, total: Int) {
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val target = if (total > 0) done.toFloat() / total else 0f
    var started by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { started = true }
    val frac by animateFloatAsState(if (started) target else 0f, tween(800), label = "ring")
    val track = t.bg.color()
    val fill = t.brand.color()
    Box(Modifier.size(84.dp).semantics { contentDescription = "已掌握 $done / $total" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val stroke = 8.dp.toPx() * size.width / 76.dp.toPx()
            val r = 30f / 76f * size.width
            val tl = Offset(center.x - r, center.y - r)
            drawCircle(track, r, center, style = Stroke(stroke))
            if (frac > 0f) drawArc(fill, -90f, 360f * frac, false, tl, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$done", style = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 24.sp), color = t.ink.color())
            Text("/ $total", style = TextStyles.small.copy(fontSize = 11.sp, lineHeight = 13.sp), color = t.muted.color())
        }
    }
}

@Composable
fun Pill(status: String) {
    val t = LocalTokens.current
    val (bg, fg) = when (status) {
        "mastered" -> t.goodSoft.color() to t.good.color()
        "partial" -> t.warnSoft.color() to t.warn.color()
        else -> t.badSoft.color() to t.bad.color()
    }
    Text(
        STATUS_LABEL[status] ?: status,
        color = fg,
        style = TextStyles.small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// 要点：状态、理由，学习者自己说过的那句话（依据）。复盘页和概念笔记共用
@Composable
fun PointList(points: List<Point>) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (p in points) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(56.dp).padding(top = 3.dp)) { Pill(p.status) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.label, style = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 1.5.em), color = t.ink.color())
                    if (p.note.isNotEmpty()) Text(p.note, style = TextStyles.small.copy(fontSize = 13.sp), color = t.muted.color())
                    if (!p.evidence.isNullOrEmpty()) Evidence(p.evidence)
                }
            }
        }
    }
}

// 依据：学习者的原话，引号括起来、左边一道细线（.evidence）
@Composable
private fun Evidence(text: String) {
    val t = LocalTokens.current
    val bar = t.line.color()
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = t.muted.color(), fontFamily = FontFamily.Default, fontSize = 12.sp)) { append("你说过：") }
            append("“$text”")
        },
        style = TextStyles.small.copy(fontFamily = Serif, lineHeight = LINE_HEIGHT_SERIF.em),
        color = t.ink.color(),
        modifier = Modifier.padding(top = 4.dp).drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }.padding(start = 10.dp),
    )
}

// 一组短条目（例子、边界）
@Composable
fun Bullets(title: String, items: List<String>?, note: String? = null) {
    if (items.isNullOrEmpty()) return
    val t = LocalTokens.current
    Panel {
        SectionLabel(title)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (x in items) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val dot = t.brand.color()
                    Box(Modifier.padding(top = 10.dp).size(5.dp).drawBehind { drawCircle(dot) })
                    Text(x, style = TextStyles.knowledge.copy(fontSize = 15.5.sp), color = t.ink.color())
                }
            }
        }
        if (note != null) FinePrint(note)
    }
}

// “什么时候想到它”：像 skill 的 description，什么情况下该调出这个概念
@Composable
fun UseWhen(text: String) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().background(t.lit.color(), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("什么时候想到它", style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = t.litInk.color().copy(alpha = 0.8f))
        Text(text, style = TextStyles.small.copy(lineHeight = 1.7.em), color = t.litInk.color())
    }
}

// 复盘页底下：这次沉淀进了哪篇笔记，点开去看（.settled）
@Composable
private fun Settled(concept: String, sub: String, onOpen: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth()
            .background(t.raised.color(), RoundedCornerShape(16.dp))
            .border(1.dp, t.line.color(), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(t.lit.color(), CircleShape), contentAlignment = Alignment.Center) {
            Icon(lineIcon("library", IconPaths.LIBRARY), null, Modifier.size(20.dp), tint = t.litInk.color())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("已整理进「$concept」的笔记", style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = t.ink.color())
            Text(sub, style = TextStyles.small.copy(fontSize = 12.5.sp, lineHeight = 1.6.em), color = t.muted.color())
        }
        Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(18.dp), tint = t.muted.color())
    }
}

// 需要纠正的理解（概念笔记里叫“易错”）：说错的划掉，下面是改正
@Composable
fun FixList(title: String, misconceptions: List<Misconception>) {
    if (misconceptions.isEmpty()) return
    val t = LocalTokens.current
    Panel {
        SectionLabel(title)
        for (m in misconceptions) {
            val bar = t.badSoft.color()
            Column(
                Modifier.fillMaxWidth().drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(m.said, style = TextStyles.small.copy(textDecoration = TextDecoration.LineThrough), color = t.muted.color())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.padding(top = 5.dp).size(14.dp), tint = t.good.color())
                    Text(m.fix, style = TextStyles.body.copy(fontSize = 15.sp), color = t.ink.color())
                }
            }
        }
    }
}

// 立场不是误解：不划掉、不纠正，只点评理由
@Composable
fun StanceList(title: String, stances: List<Stance>?, fine: String? = null) {
    if (stances.isNullOrEmpty()) return
    val t = LocalTokens.current
    Panel {
        SectionLabel(title)
        for (m in stances) {
            val bar = t.line.color()
            Column(
                Modifier.fillMaxWidth().drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(m.said, style = TextStyles.knowledge, color = t.ink.color())
                if (m.note.isNotEmpty()) Text(m.note, style = TextStyles.small.copy(lineHeight = 1.7.em), color = t.muted.color())
            }
        }
        if (fine != null) FinePrint(fine)
    }
}

@Composable
fun TermList(title: String, terms: List<TermCard>?) {
    if (terms.isNullOrEmpty()) return
    val t = LocalTokens.current
    Panel {
        SectionLabel(title)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (term in terms) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        term.term,
                        style = TextStyles.knowledge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = TextSize.lg,
                            lineHeight = 1.4.em,
                            letterSpacing = 0.02.em,
                        ),
                        color = t.ink.color(),
                    )
                    Text(term.definition, style = TextStyles.small.copy(lineHeight = 1.7.em), color = t.ink.color())
                    if (term.relation.isNotEmpty()) Text(term.relation, style = TextStyles.small.copy(fontSize = 12.5.sp), color = t.muted.color())
                }
            }
        }
    }
}

// 复盘的正文：要点掌握情况、例子、需要纠正的理解、边界、立场、新词
@Composable
fun ReviewBody(r: SessionReview, terms: List<TermCard>?) {
    Panel {
        SectionLabel("要点掌握情况")
        PointList(r.points)
    }
    Bullets("你举过的例子", r.examples)
    FixList("需要纠正的理解", r.misconceptions)
    Bullets("边界", r.boundaries, "它不是什么、和哪些相近的概念容易混。")
    StanceList("你的立场", r.stances, "立场没有对错，这里只看理由是否说清楚、有没有考虑反方。")
    TermList("这次遇到的新词", terms)
}

@Composable
fun EmptyState(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = TextStyles.body.copy(fontWeight = FontWeight.Bold, fontSize = TextSize.lg), color = t.ink.color())
        Text(text, style = TextStyles.small.copy(lineHeight = LINE_HEIGHT_SERIF.em), color = t.muted.color(), textAlign = TextAlign.Center)
        if (action != null) {
            Box(Modifier.padding(top = 4.dp)) { action() }
        }
    }
}
