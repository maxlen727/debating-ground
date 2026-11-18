package debating.ground.app.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
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
import debating.ground.app.data.QuizItem
import debating.ground.app.data.Reflection
import debating.ground.app.data.STATUS_LABEL
import debating.ground.app.data.Stance
import debating.ground.app.data.TermCard
import debating.ground.app.data.sessionMarkdown
import debating.ground.app.flow.AppState
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.lineIcon
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
fun ExportButton(label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(lineIcon("download", IconPaths.DOWNLOAD), null, tint = t.ink.color())
    }
}

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
    val card = st.library.find { it.sessionId == s.id }
    val busy = st.ui.busy != null
    WithFloatingDock(dock = {
        if (r != null && card != null) {
            ErrorBanner(st.ui.error) { l.clearError() }
            BusyStrip(st.ui.busy) { l.cancelAI() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("回看讨论", { l.go("session") })
                PrimaryButton("从薄弱点再讨论", { model.run { discussAgain(card) } }, Modifier.weight(1f), enabled = !busy)
            }
        }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar("理解复盘", "${s.concept} · ${MODES[s.mode]?.name.orEmpty()}", onBack = { l.go("session") }) {
                ExportButton("导出讨论记录和复盘") { shareMarkdown(context, "${s.concept} · 讨论记录", sessionMarkdown(s)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
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
                                (if (r.source == "api") "由 ${r.model} 根据你的发言和小结生成，供参考" else "演示模式：预设示例或规则统计") + " · 已存入知识库",
                                style = TextStyles.small.copy(fontSize = 12.sp),
                                color = t.muted.color(),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    ReviewBody(r.points, r.misconceptions, r.stances, s.glossary)
                    if (r.quiz.isNotEmpty()) {
                        Panel {
                            SectionLabel("检验一下")
                            Quiz(r.quiz, st.ui.quiz["s:${s.id}"].orEmpty()) { q, o -> l.pickAnswer("s:${s.id}", q, o) }
                        }
                    }
                    FinePrint("第一次复习在明天。到时候知识库会提醒你，先凭记忆做题，再看这份复盘。")
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

// 复盘的正文：要点掌握情况、需要纠正的理解、立场、新词。复盘页和知识卡片共用
@Composable
fun ReviewBody(points: List<Point>, misconceptions: List<Misconception>, stances: List<Stance>?, terms: List<TermCard>?) {
    val t = LocalTokens.current
    Panel {
        SectionLabel("要点掌握情况")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (p in points) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(56.dp).padding(top = 3.dp)) { Pill(p.status) }
                    Column {
                        Text(p.label, style = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 1.5.em), color = t.ink.color())
                        if (p.note.isNotEmpty()) Text(p.note, style = TextStyles.small.copy(fontSize = 13.sp), color = t.muted.color())
                    }
                }
            }
        }
    }
    if (misconceptions.isNotEmpty()) {
        Panel {
            SectionLabel("需要纠正的理解")
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
    if (!stances.isNullOrEmpty()) {
        Panel {
            SectionLabel("你的立场")
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
            FinePrint("立场没有对错，这里只看理由是否说清楚、有没有考虑反方。")
        }
    }
    if (!terms.isNullOrEmpty()) {
        Panel {
            SectionLabel("这次遇到的新词")
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
}

// 检验题：选过就定了，显示对错和解释
@Composable
fun Quiz(items: List<QuizItem>, picks: Map<Int, Int>, onPick: (Int, Int) -> Unit) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items.forEachIndexed { qi, q ->
            val picked = picks[qi]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = t.muted.color())) { append("${qi + 1}. ") }
                        append(q.q)
                    },
                    style = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 1.7.em),
                    color = t.ink.color(),
                )
                q.options.forEachIndexed { oi, o ->
                    val state = when {
                        picked == null -> ""
                        oi == q.answer -> "right"
                        oi == picked -> "wrong"
                        else -> "dim"
                    }
                    val (bg, border) = when (state) {
                        "right" -> t.goodSoft.color() to t.good.color()
                        "wrong" -> t.badSoft.color() to t.bad.color()
                        else -> t.surface.color() to t.line.color()
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).alpha(if (state == "dim") 0.55f else 1f)
                            .background(bg, RoundedCornerShape(12.dp)).border(1.dp, border, RoundedCornerShape(12.dp))
                            .clickable(enabled = picked == null, role = Role.Button) { onPick(qi, oi) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "ABCD".getOrElse(oi) {
                                '?'
                            }.toString(),
                            style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            color = t.muted.color(),
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        Text(o, style = TextStyles.body.copy(fontSize = 15.sp), color = t.ink.color())
                    }
                }
                if (picked != null) {
                    val ok = picked == q.answer
                    Text(
                        (if (ok) "答对了。" else "正确答案是 ${"ABCD".getOrElse(q.answer) { '?' }}。") + q.why,
                        style = TextStyles.small.copy(fontSize = 13.5.sp, lineHeight = 1.7.em),
                        color = if (ok) t.good.color() else t.bad.color(),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
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
