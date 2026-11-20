package debating.ground.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.ai.SAMPLE_MATERIAL_TEXT
import debating.ground.app.ai.SAMPLE_MATERIAL_TITLE
import debating.ground.app.data.Material
import debating.ground.app.data.MaterialMeta
import debating.ground.app.flow.AppState
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.QuietLink
import debating.ground.app.ui.SecondaryButton
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

// 读材料（#144、#154、#166）：贴一篇论文或文章（或者链接、PDF），选追问或圆桌，直接围绕它讨论。
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
    var link by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("socratic") }
    // 材料从哪来：读到的链接（存进材料，以后知道它出自哪里）
    var source by rememberSaveable { mutableStateOf("") }
    // 读链接的结果说明：读到了多少字、是不是全文（warn 时用黄色）
    var note by rememberSaveable { mutableStateOf("") }
    var warn by rememberSaveable { mutableStateOf(false) }
    val chars = text.trim().length
    // 选 PDF 文件（#164）：系统的文件选择器，只读学习者选的这一个文件
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            model.run {
                importPdf(uri.toString())?.let { r ->
                    title = r.title.take(80)
                    text = r.text
                    note = "从 ${r.pages} 页 PDF 里读到了 ${r.text.count { !it.isWhitespace() }} 字。${r.note}核对一下，没问题就开始讨论。"
                    warn = false
                }
            }
        }
    }
    // 内置浏览器打开着的地址（#160）
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val fill = { r: debating.ground.app.ai.LinkPage, from: String ->
        title = r.title.take(80)
        text = r.text
        source = from
        note = "读到了 ${r.text.count { !it.isWhitespace() }} 字。" + r.note.ifEmpty { "核对一下，没问题就开始讨论。" }
        warn = !r.complete
    }
    browsing?.let { start ->
        InAppBrowser(
            start,
            st.ui.busy,
            st.ui.error,
            onClearError = { l.clearError() },
            onCancel = { l.cancelAI() },
            onUse = { url, html ->
                model.run {
                    usePage(url, html)?.let {
                        fill(it, url)
                        link = url
                        browsing = null
                    }
                }
            },
            onClose = { browsing = null },
        )
        return
    }
    val read = {
        model.run {
            fetchLink(link)?.let { fill(it, link.trim()) }
        }
    }
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        ModePick(mode, !busy) { mode = it }
        PrimaryButton(
            "围绕这篇材料开始讨论",
            { model.run { addMaterial(title, text, mode, source.ifEmpty { null }) } },
            Modifier.fillMaxWidth(),
            enabled = !busy && chars > 0,
        )
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar("读材料", "贴一篇论文或文章，一个点一个点弄懂", onBack = { l.go("learn") })
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 读链接（#157）：手机自己去取，取到的正文填进下面的框，核对后再开始讨论
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Input(
                        link,
                        { link = it },
                        "材料链接",
                        Modifier.weight(1f),
                        placeholder = "贴一个链接：arXiv 论文、网页文章",
                        maxLength = 2000,
                        enabled = !busy,
                        onDone = { if (link.isNotBlank()) read() },
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    SecondaryButton("读取", { read() }, enabled = !busy && link.isNotBlank())
                }
                // 读不到（反爬、要登录、正文要等网页加载）：在 App 里自己打开它
                if (link.isNotBlank()) {
                    QuietLink("读不到？在 App 里打开它，登录或验证后再读", {
                        l.clearError()
                        browsing = link.trim().let { if (it.contains("://")) it else "https://$it" }
                    }, enabled = !busy)
                }
                GhostButton("选一个 PDF 文件", { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !busy, small = true, icon = IconPaths.NOTE)
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        style = TextStyles.small.copy(lineHeight = 1.6.em),
                        color = if (warn) t.warn.color() else t.litInk.color(),
                        modifier = Modifier.fillMaxWidth().background(if (warn) t.warnSoft.color() else t.lit.color(), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                Input(title, { title = it }, "材料标题", placeholder = "标题（可以不写）", maxLength = 80, enabled = !busy)
                Input(
                    text,
                    { text = it },
                    "材料正文",
                    // 论文可能有几万字：框的高度封顶，在框里滚动，不把整页撑得很长
                    Modifier.heightIn(min = 220.dp, max = 420.dp),
                    placeholder = "把论文或文章的正文贴在这里。带着小节标题更好，讨论时 AI 会按节找到相关的原文。",
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
                FinePrint("材料只保存在这台设备上。讨论时只把目录和与眼下话题最相关的几节发给 AI，不会每次都发整篇。读链接时由这台手机直接去取那个网页，只读不改。PDF 只在这台手机上解析，不上传。")
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
                dateFormat.format(Date(m.created)) + " · ${m.sections} 节",
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

// 怎么讨论：追问（一位 AI 一路追问）或圆桌（几位 AI 各有角度）
@Composable
private fun ModePick(mode: String, enabled: Boolean, onSelect: (String) -> Unit) =
    Segmented(listOf("socratic" to "追问", "roundtable" to "圆桌"), mode, "怎么讨论", enabled = enabled, onSelect = onSelect)

// 读过的一份材料：从哪来、原文，继续围绕它讨论
@Composable
private fun Reading(st: AppState, m: Material, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val busy = st.ui.busy != null
    var mode by rememberSaveable { mutableStateOf("socratic") }
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        ModePick(mode, !busy) { mode = it }
        PrimaryButton("围绕这篇材料讨论", { model.run { discussMaterial(mode) } }, Modifier.fillMaxWidth(), enabled = !busy)
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar(m.title, "${m.sections.size} 节 · ${dateFormat.format(Date(m.created))}", onBack = { model.run { openMaterials() } })
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                m.source?.let { FinePrint("出自：$it") }
                Text(
                    "AI 会看着这篇材料的目录，每轮翻到和你们正在聊的最相关的几节。讨论完的复盘会沉淀进核心概念的笔记，也会沉淀进这篇材料的笔记。",
                    style = TextStyles.small.copy(lineHeight = 1.6.em),
                    color = t.muted.color(),
                )
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
