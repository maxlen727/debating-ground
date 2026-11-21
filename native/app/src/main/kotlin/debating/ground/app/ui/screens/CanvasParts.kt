package debating.ground.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Session
import debating.ground.app.data.SystemLine
import debating.ground.app.data.branchById
import debating.ground.app.data.branchesFrom
import debating.ground.app.data.graph
import debating.ground.app.data.talkCount
import debating.ground.app.flow.branchChain
import debating.ground.app.flow.pathEntries
import debating.ground.app.ui.Chip
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Sheet
import debating.ground.app.ui.glass
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.mix
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 知识画布：路径条、分叉处的分支入口、分支开头、带回来的小结、分支图，以及展开和带回的面板。
// 和网页版 src/components/Canvas.tsx 一致，只负责展示，操作都在流程层。

private fun clip(t: String, n: Int) = if (t.length > n) t.take(n) + "…" else t

// 顶部路径：主干 › 分支 › 子分支；右边打开分支图
@Composable
fun CanvasPath(s: Session, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val chain = s.branchChain()
    val crumbs = listOf<Pair<String?, String>>(null to "主干") + chain.map { it.id to it.topic }
    val total = s.branches?.size ?: 0
    Row(
        // 路径条是一条悬浮的磨砂胶囊
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), 6.dp).padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(crumbs) { i, (id, name) ->
                val here = i == crumbs.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (i >
                        0
                    ) {
                        Text("›", style = TextStyles.small.copy(fontSize = 13.sp), color = t.muted.color(), modifier = Modifier.padding(horizontal = 2.dp))
                    }
                    Text(
                        name,
                        style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (here) t.brand.color() else t.muted.color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 160.dp)
                            .heightIn(min = 36.dp)
                            .background(if (here) t.brand.color().copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy && !here, role = Role.Button) { l.goToBranch(id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(
            Modifier.heightIn(
                min = 36.dp,
            ).widthIn(min = 44.dp).background(t.surface.color(), RoundedCornerShape(10.dp)).border(1.dp, t.line.color(), RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) {
                    l.openSheet("tree")
                }.semantics { contentDescription = "分支图，共 $total 个分支" }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            Icon(lineIcon("branch", IconPaths.BRANCH), null, Modifier.size(18.dp), tint = t.brand.color())
            if (total > 0) Text("$total", style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = t.brand.color())
        }
    }
}

// 分支开头：从哪句话展开、这一枝要弄懂什么
@Composable
fun ForkHeader(s: Session) {
    val t = LocalTokens.current
    val here = s.branchById(s.at) ?: return
    val from = s.entries.find { it.id == here.from } as? ChatMessage
    val line = t.line.color()
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(
                line,
                Offset(0f, size.height),
                Offset(size.width, size.height),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }.padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (from != null) {
            val bar = t.brand.color().mix(0.55f, t.line.color())
            Column(
                Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                    .drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }.padding(start = 15.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    "从${if (from.human == true) "你" else from.name}的这句话展开",
                    style = TextStyles.small.copy(fontSize = 12.sp, lineHeight = 1.6.em),
                    color = t.muted.color(),
                )
                Text(clip(from.text, 90), style = TextStyles.knowledge.copy(fontSize = TextSize.sm), color = t.muted.color())
            }
        }
        Text(here.topic, style = TextStyles.knowledge.copy(fontWeight = FontWeight.Bold, fontSize = TextSize.lg, lineHeight = 1.4.em), color = t.ink.color())
    }
}

// 发言下面：从这句话展开过的分支
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BranchChips(s: Session, entryId: String, mine: Boolean, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val list = s.branchesFrom(entryId)
    if (list.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(start = if (mine) 0.dp else 44.dp, end = if (mine) 44.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (mine) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (b in list) {
            val back = b.gist != null
            Row(
                Modifier.heightIn(min = 36.dp).alpha(if (busy) 0.45f else 1f)
                    .background(if (back) t.brand.color().mix(0.08f, t.surface.color()) else t.surface.color(), RoundedCornerShape(18.dp))
                    .border(1.dp, t.brand.color().mix(0.35f, t.line.color()), RoundedCornerShape(18.dp))
                    .clickable(enabled = !busy, role = Role.Button) { model.learning.goToBranch(b.id) }
                    .padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(lineIcon("branch", IconPaths.BRANCH), null, Modifier.size(15.dp), tint = t.brand.color())
                Text(b.topic, style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = t.brand.color())
                Text("${s.talkCount(b.id)} 条" + if (back) " · 已带回" else "", style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color())
            }
        }
    }
}

// 从分支带回来的那句话（学习者自己写的，属于知识本身）
@Composable
fun BackLine(m: SystemLine, s: Session, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val b = s.branchById(m.back)
    Column(
        Modifier.fillMaxWidth()
            .background(t.brand.color().mix(0.06f, t.surface.color()), RoundedCornerShape(14.dp))
            .border(1.dp, t.brand.color().mix(0.3f, t.line.color()), RoundedCornerShape(14.dp))
            .clickable(enabled = !busy && b != null, role = Role.Button) { b?.let { model.learning.goToBranch(it.id) } }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(lineIcon("turn-back", IconPaths.TURN_BACK), null, Modifier.size(14.dp), tint = t.brand.color())
            Text("从「${b?.topic ?: "分支"}」带回", style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = t.brand.color())
        }
        Text(m.text, style = TextStyles.knowledge, color = t.ink.color())
    }
}

// 展开分支：AI 标出的新词一点就开；也可以自己写想弄懂什么
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForkForm(m: ChatMessage, busy: Boolean, model: AppModel) {
    var topic by rememberSaveable(m.id) { mutableStateOf("") }
    SectionLabel("从这句展开一个分支")
    if (!m.terms.isNullOrEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            m.terms.forEach { term -> Chip(term, on = false, enabled = !busy, onClick = { model.run { openBranch(m.id, term) } }) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            Input(topic, { topic = it }, "分支话题", placeholder = "想单独弄懂什么？比如一个词", maxLength = 40, onDone = { model.run { openBranch(m.id, topic) } })
        }
        PrimaryButton("展开", { model.run { openBranch(m.id, topic) } }, enabled = !busy && topic.isNotBlank())
    }
}

// 带回上一层：学习者自己写一句这个分支弄懂了什么
@Composable
fun BackSheet(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val here = s.branchById(s.at) ?: return
    var gist by rememberSaveable(here.id) { mutableStateOf(here.gist ?: "") }
    val up = s.branchById(here.parent)?.topic
    Sheet(if (up == null) "带回主干" else "带回「$up」", { l.openSheet(null) }) {
        Text(
            "用一句话说说，在「${here.topic}」这里你弄懂了什么。这句话会挂回${if (up == null) "主干" else "上一层"}，之后的讨论会接着它聊，复盘也会算上它。",
            style = TextStyles.body.copy(fontSize = 15.5.sp, lineHeight = 1.8.em),
            color = t.ink.color(),
        )
        Input(gist, { gist = it }, "带回的一句话", placeholder = "我弄懂了……", singleLine = false, minLines = 3, maxLength = 300, style = TextStyles.knowledge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("先不写，直接回去", { l.goToBranch(here.parent) }, Modifier.weight(1f))
            PrimaryButton("带回去", { l.bringBack(gist) }, Modifier.weight(1f), enabled = gist.isNotBlank())
        }
    }
}

/* ---------- 分支图 ---------- */
// 像 GitLens 的提交图：每条发言一行，主干和每个分支各占一条彩色轨道，分叉和带回用曲线连起来；点一行就跳到那个分支。

private val ROW = 56.dp
private val GAP = 20.dp
private val PAD = 16.dp

@Composable
private fun laneColors(): List<Color> {
    val t = LocalTokens.current
    return listOf(t.brand.color(), t.cFg.color(), t.accent.color(), t.pFg.color(), t.aFg.color(), t.bFg.color())
}

@Composable
fun GraphSheet(s: Session, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val g = remember(s.entries, s.branches) { s.graph() }
    val onPath = remember(s.entries, s.branches, s.at) { s.pathEntries().map { it.id }.toSet() }
    val colors = laneColors()
    val surface = t.surface.color()
    fun topic(id: String?) = if (id == null) "主干" else s.branchById(id)?.topic.orEmpty()
    val firstOf = HashMap<String, String>()
    for (r in g.rows) firstOf.putIfAbsent(r.branch ?: "", r.id)
    Sheet("分支图", { l.openSheet(null) }) {
        if (g.rows.isEmpty()) {
            Text("还没有发言。", style = TextStyles.body, color = t.muted.color())
        } else {
            val width: Dp = PAD + GAP * (g.width - 1) + PAD
            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.matchParentSize()) {
                    val row = ROW.toPx()
                    fun lx(lane: Int) = (PAD + GAP * lane).toPx()
                    fun ry(i: Int) = row / 2 + i * row
                    val stroke = 2.5.dp.toPx()
                    for (ln in g.lanes) {
                        drawLine(
                            colors[ln.lane % colors.size],
                            Offset(lx(ln.lane), ry(ln.fromRow)),
                            Offset(lx(ln.lane), ry(ln.toRow)),
                            stroke,
                            StrokeCap.Round,
                        )
                    }
                    for (e in g.edges) {
                        val x1 = lx(e.fromLane)
                        val y1 = ry(e.fromRow)
                        val x2 = lx(e.toLane)
                        val y2 = ry(e.toRow)
                        // 分叉时一出节点就拐进自己的轨道再直着往下走；带回时直着走到那一行前面才拐回去
                        val p = Path().apply {
                            moveTo(x1, y1)
                            if (e.kind == "fork") {
                                cubicTo(x1, y1 + row * 0.42f, x2, y1 + row * 0.18f, x2, y1 + row * 0.6f)
                                lineTo(x2, y2)
                            } else {
                                lineTo(x1, y2 - row * 0.6f)
                                cubicTo(x1, y2 - row * 0.18f, x2, y2 - row * 0.42f, x2, y2)
                            }
                        }
                        val c = colors[(if (e.kind == "fork") e.toLane else e.fromLane) % colors.size]
                        val dash = if (e.kind == "merge") PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx())) else null
                        drawPath(p, c, style = Stroke(if (e.kind == "merge") 3.dp.toPx() else stroke, cap = StrokeCap.Round, pathEffect = dash))
                    }
                    for (r in g.rows) {
                        val c = colors[r.lane % colors.size]
                        val center = Offset(lx(r.lane), ry(r.index))
                        val a = if (r.id in onPath) 1f else 0.45f
                        val hollow = r.kind != "ai"
                        if (r.kind == "back") {
                            val half = 6.dp.toPx()
                            rotate(45f, center) {
                                drawRoundRect(
                                    if (hollow) surface else c,
                                    Offset(center.x - half, center.y - half),
                                    Size(half * 2, half * 2),
                                    CornerRadius(2.dp.toPx()),
                                    alpha = a,
                                )
                                drawRoundRect(
                                    c,
                                    Offset(center.x - half, center.y - half),
                                    Size(half * 2, half * 2),
                                    CornerRadius(2.dp.toPx()),
                                    alpha = a,
                                    style = Stroke(stroke),
                                )
                            }
                        } else {
                            val radius = (if (r.kind == "me") 6.5.dp else 5.5.dp).toPx()
                            drawCircle(if (hollow) surface else c, radius, center, alpha = a)
                            drawCircle(if (hollow) c else surface, radius, center, alpha = a, style = Stroke(stroke))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(start = width)) {
                    for (r in g.rows) {
                        val here = r.branch == s.at
                        Column(
                            Modifier.fillMaxWidth().height(ROW)
                                .background(if (here) t.brand.color().copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy, role = Role.Button) { l.goToBranch(r.branch) }
                                .semantics { contentDescription = "${r.who}：${clip(r.text, 40)}，在${topic(r.branch)}" }
                                .alpha(if (r.id in onPath) 1f else 0.55f)
                                .padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (firstOf[r.branch ?: ""] == r.id) {
                                    val c = colors[r.lane % colors.size]
                                    Text(
                                        topic(r.branch),
                                        style = TextStyles.knowledge.copy(fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold),
                                        color = c,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 110.dp).background(c.mix(0.14f, surface), RoundedCornerShape(6.dp))
                                            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                                Text(
                                    r.who,
                                    style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                    color = t.ink.color(),
                                    maxLines = 1,
                                )
                            }
                            Text(
                                r.text,
                                style = TextStyles.small.copy(fontSize = 13.5.sp),
                                color = t.ink.color(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        FinePrint("分支里 AI 只看得到主干到分叉点和这个分支。点一行跳过去。")
    }
}
