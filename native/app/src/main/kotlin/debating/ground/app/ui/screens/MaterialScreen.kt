package debating.ground.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.ai.SAMPLE_MATERIAL_TEXT
import debating.ground.app.ai.SAMPLE_MATERIAL_TITLE
import debating.ground.app.data.MapPoint
import debating.ground.app.data.Material
import debating.ground.app.data.MaterialMeta
import debating.ground.app.data.ReadStep
import debating.ground.app.flow.AppState
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Segmented
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.mix
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Serif
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 读材料（#144、#154）：贴一篇论文或文章，研读助手读完整理出技术点地图，挑一个点开始讨论。
// 和网页版 src/screens/Material.tsx 一致；只负责展示，操作都在 Learning 里。完整的材料只存在这台设备上。

@Composable
fun MaterialScreen(st: AppState, model: AppModel) {
    LaunchedEffect(Unit) { model.learning.loadMaterials() }
    val m = st.ui.material
    if (m != null) Reading(st, m, model) else Paste(st, model)
}

private val dateFormat get() = SimpleDateFormat("yyyy/M/d", Locale.CHINA)

// 贴材料：标题可以不写（用第一行）；下面是最近读过的材料
@Composable
private fun Paste(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val busy = st.ui.busy != null
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    val chars = text.trim().length
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        PrimaryButton("开始研读", { model.run { addMaterial(title, text) } }, Modifier.fillMaxWidth(), enabled = !busy && chars > 0)
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar("读材料", "贴一篇论文或文章，一个点一个点弄懂", onBack = { l.go("learn") })
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Input(title, { title = it }, "材料标题", placeholder = "标题（可以不写）", maxLength = 80, enabled = !busy)
                Input(
                    text,
                    { text = it },
                    "材料正文",
                    // 论文可能有几万字：框的高度封顶，在框里滚动，不把整页撑得很长
                    Modifier.heightIn(min = 220.dp, max = 420.dp),
                    placeholder = "把论文或文章的正文贴在这里。带着小节标题更好，研读助手会按节来读。",
                    singleLine = false,
                    minLines = 9,
                    enabled = !busy,
                    style = TextStyles.body.copy(fontFamily = Serif, lineHeight = 1.75.em),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (chars >
                            0
                        ) {
                            "$chars 字"
                        } else {
                            ""
                        },
                        style = TextStyles.small.copy(fontSize = 13.sp),
                        color = t.muted.color(),
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton("用示例文章试试", {
                        title = SAMPLE_MATERIAL_TITLE
                        text = SAMPLE_MATERIAL_TEXT
                    }, enabled = !busy, small = true)
                }
                FinePrint("材料只保存在这台设备上。研读时，只把研读助手要读的几节发给 AI；讨论时，只带这个点的原文节选。链接和 PDF 还在做，先把正文复制过来。")
                if (st.ui.materials.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    SectionLabel("读过的材料")
                    for (m in st.ui.materials) MaterialItem(m, busy, { model.run { openMaterial(m.id) } }, { model.run { removeMaterial(m.id) } })
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

@Composable
private fun MaterialItem(m: MaterialMeta, busy: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(t.raised.color(), shape).border(1.dp, t.line.color(), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(m.title, style = TextStyles.body.copy(fontFamily = Serif, fontWeight = FontWeight.Bold), color = t.ink.color())
            Text(
                dateFormat.format(Date(m.created)) + if (m.points > 0) " · ${m.points} 个技术点" else " · 还没读完",
                style = TextStyles.small.copy(fontSize = 12.sp),
                color = t.muted.color(),
            )
        }
        Box(
            Modifier.size(44.dp).clickable(enabled = !busy, role = Role.Button, onClick = onDelete).semantics { contentDescription = "删除《${m.title}》" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(lineIcon("close", IconPaths.CLOSE), null, Modifier.size(18.dp), tint = t.muted.color())
        }
    }
}

// 读一份材料：研读助手每一步在做什么、整理出的技术点地图、原文
@Composable
private fun Reading(st: AppState, m: Material, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val busy = st.ui.busy != null
    val steps = st.ui.reading ?: m.steps.orEmpty()
    val map = m.map
    var picked by rememberSaveable(m.id) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("socratic") }
    val point = map?.points?.find { it.name == picked } ?: map?.points?.firstOrNull()
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        if (map != null && point != null) {
            Segmented(listOf("socratic" to "追问", "roundtable" to "圆桌"), mode, "怎么讨论", enabled = !busy) { mode = it }
            PrimaryButton("从「${point.name}」开始讨论", { model.run { startFromPoint(point, mode) } }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar(
                map?.title?.ifEmpty { null } ?: m.title,
                "${m.sections.size} 节" + (map?.let { " · ${it.points.size} 个技术点" } ?: ""),
                onBack = { model.run { openMaterials() } },
            )
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                map?.summary?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = TextStyles.body.copy(fontFamily = Serif, lineHeight = 1.75.em), color = t.ink.color())
                }
                if (steps.isNotEmpty()) Trace(steps, openAtFirst = map == null)
                if (map == null && !busy) {
                    Panel {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("研读助手还没整理出技术点。", style = TextStyles.body, color = t.ink.color())
                            PrimaryButton("让研读助手读一遍", { model.run { readMaterial() } })
                        }
                    }
                }
                if (map != null) {
                    SectionLabel("技术点地图 · 挑一个开始")
                    for (p in map.points) MapPointItem(p, p.name == point?.name, !busy) { picked = p.name }
                }
                Original(m)
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

// 可以折起来的一块（网页版的 <details class="panel">）：标题行可点，箭头跟着转
@Composable
private fun Fold(title: String, startOpen: Boolean, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    var open by rememberSaveable(title, startOpen) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "fold")
    Panel {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(role = Role.Button) { open = !open }.semantics {
                contentDescription =
                    title + if (open) "，点一下收起" else "，点一下展开"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = TextStyles.small.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                color = t.muted.color(),
                modifier = Modifier.weight(1f),
            )
            Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(16.dp).rotate(turn), tint = t.muted.color())
        }
        if (open) content()
    }
}

@Composable
private fun Trace(steps: List<ReadStep>, openAtFirst: Boolean) {
    val t = LocalTokens.current
    Fold("研读过程 · ${steps.size} 步", openAtFirst) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}.", style = TextStyles.small, color = t.muted.color())
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.action, style = TextStyles.small.copy(fontWeight = FontWeight.Bold), color = t.ink.color())
                        if (s.thought.isNotEmpty()) Text(s.thought, style = TextStyles.small, color = t.muted.color())
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPointItem(p: MapPoint, on: Boolean, enabled: Boolean, onPick: () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(14.dp)
    val where = listOfNotNull(
        p.sections.takeIf { it.isNotEmpty() }?.joinToString(" ") { "§$it" },
        p.needs.takeIf { it.isNotEmpty() }?.let { "要先懂：" + it.joinToString("、") },
    ).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth()
            .background(if (on) t.brand.color().mix(0.08f, t.raised.color()) else t.raised.color(), shape)
            .border(1.dp, if (on) t.brand.color() else t.line.color(), shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onPick)
            .semantics { selected = on }
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(p.name, style = TextStyles.body.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp), color = t.ink.color())
        if (p.why.isNotEmpty()) Text(p.why, style = TextStyles.small.copy(lineHeight = 1.6.em), color = t.ink.color())
        if (where.isNotEmpty()) Text(where, style = TextStyles.small.copy(fontSize = 12.5.sp), color = t.muted.color())
    }
}

// 原文：默认折着，展开才排版（材料可能很长）
@Composable
private fun Original(m: Material) {
    Fold("原文 · ${m.sections.size} 节", false) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (s in m.sections) SectionText("§${s.n} ${s.heading}", s.text)
        }
    }
}

/** 原文的一节：标题加正文（衬线，“知识本身”）。讨论页的“原文”面板也用它。 */
@Composable
fun SectionText(heading: String, text: String) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = TextStyles.small.copy(fontWeight = FontWeight.Bold), color = t.ink.color())
        Text(text, style = TextStyles.body.copy(fontFamily = Serif, fontSize = 15.sp, lineHeight = 1.75.em), color = t.ink.color())
    }
}
