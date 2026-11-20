package debating.ground.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.MODES
import debating.ground.app.data.Note
import debating.ground.app.data.SEARCH_MIN_CARDS
import debating.ground.app.data.isMaterialNote
import debating.ground.app.data.isNoteFor
import debating.ground.app.data.libraryMarkdown
import debating.ground.app.data.noteMarkdown
import debating.ground.app.data.sameConcept
import debating.ground.app.data.searchCards
import debating.ground.app.data.tally
import debating.ground.app.flow.AppState
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.Chip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.NavSpacer
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
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

// 知识库：一个概念一篇笔记，每次讨论完把理解沉淀进去（#140）。只保存在这台设备上。和网页版 src/screens/Library.tsx 一致

@Composable
fun LibraryScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val library = st.library
    val context = LocalContext.current
    // 搜索词只在这一页里，不存档
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val list = if (searching) searchCards(library, query) else library
    Column(Modifier.fillMaxSize()) {
        // 有几篇概念笔记、几篇材料笔记（#166）
        val materials = library.count { it.isMaterialNote() }
        TopBar("知识库", "${library.size - materials} 个概念" + (if (materials > 0) " · $materials 篇材料" else "") + " · 只保存在这台设备") {
            if (library.isNotEmpty()) ExportButton("导出知识库") { shareMarkdown(context, "思辨场知识库", libraryMarkdown(library)) }
        }
        Column(
            Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (library.isEmpty()) {
                EmptyState(
                    "知识库还是空的",
                    "每讨论完一个概念，复盘会整理成一篇它的笔记：用你自己的话说清它是什么、什么时候用、你举过的例子和容易错的地方，还有几道自检题。以后再讨论同一个概念，新的理解会接着沉淀进这篇笔记。",
                ) {
                    PrimaryButton("开始学习", { l.go("learn") })
                }
            } else {
                if (library.size >= SEARCH_MIN_CARDS) SearchField(query) { query = it }
                if (searching && list.isNotEmpty()) Text("找到 ${list.size} 个概念", style = TextStyles.small, color = t.muted.color())
                if (list.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "没有找到和“${query.trim()}”有关的笔记。",
                            style = TextStyles.small,
                            color = t.muted.color(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        GhostButton("清除搜索", { query = "" }, small = true)
                    }
                } else {
                    // 搜索结果变了时整列淡入淡出，不是一下子换掉
                    val ms = if (LocalMotion.current) 200 else 0
                    AnimatedContent(list.map { it.id }, transitionSpec = { fadeIn(tween(ms)) togetherWith fadeOut(tween(ms)) }, label = "notes") { ids ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (id in ids) library.find { it.id == id }?.let { n -> NoteItem(n) { l.openCard(n.id) } }
                        }
                    }
                }
            }
            NavSpacer()
        }
    }
}

// 搜索框（.search-box）：内容层里的输入框，和其它输入框一样的底色和描边，聚焦时描边变成品牌色
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val t = LocalTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(23.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(t.raised.color(), shape)
            .border(1.dp, if (focused) t.brand.color() else t.line.color(), shape)
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(lineIcon("search", IconPaths.SEARCH), null, Modifier.size(18.dp), tint = t.muted.color())
        BasicTextField(
            value,
            { onChange(it.take(80)) },
            singleLine = true,
            textStyle = TextStyles.body.copy(color = t.ink.color()),
            cursorBrush = SolidColor(t.brand.color()),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "搜索概念笔记" },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("搜概念、要点、例子、术语", style = TextStyles.body, color = t.muted.color(), maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) {
                    onChange("")
                }.semantics { contentDescription = "清除搜索" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(lineIcon("close", IconPaths.CLOSE), null, Modifier.size(16.dp), tint = t.muted.color())
            }
        }
    }
}

private val dateFormat get() = SimpleDateFormat("yyyy/M/d", Locale.CHINA)

private fun date(t: Long) = dateFormat.format(Date(t))

@Composable
private fun NoteItem(n: Note, onOpen: () -> Unit) {
    val t = LocalTokens.current
    val c = n.tally()
    val material = n.isMaterialNote()
    val weak = c.partial + c.missing
    Column(
        Modifier.fillMaxWidth()
            .background(t.raised.color(), RoundedCornerShape(14.dp))
            .border(1.dp, t.line.color(), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (material) "《${n.concept}》" else n.concept,
                style = TextStyles.body.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp),
                color = t.ink.color(),
                modifier = Modifier.weight(1f),
            )
            if (material) {
                Text(
                    "材料",
                    style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = t.cFg.color(),
                    modifier = Modifier.background(t.cBg.color(), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else if (weak > 0) {
                Text(
                    "$weak 处还模糊",
                    style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = t.warn.color(),
                    modifier = Modifier.background(t.warnSoft.color(), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        // 材料笔记的一句话：讨论过它里面的哪些概念（#166）
        val gist = if (material) {
            if (n.related.isNotEmpty()) "讨论过：" + n.related.joinToString("、") else "围绕这篇材料的讨论都会沉淀在这里。"
        } else {
            n.summary
        }
        Text(
            gist.ifEmpty { "还没有用自己的话说清它，再讨论一次就能补上。" },
            style = TextStyles.small.copy(fontFamily = if (n.summary.isNotEmpty()) Serif else null, lineHeight = 1.6.em),
            color = if (gist.isNotEmpty()) t.ink.color() else t.muted.color(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Meter(n)
        Text(
            "${n.sources.size} 次讨论 · 更新于 ${date(n.updated)}" + (if (n.checks.isNotEmpty()) " · ${n.checks.size} 道自检" else ""),
            style = TextStyles.small.copy(fontSize = 12.sp),
            color = t.muted.color(),
        )
    }
}

// 每个要点一格：掌握是绿的，模糊是黄的，没讲到是灰的
@Composable
private fun Meter(n: Note) {
    val t = LocalTokens.current
    val c = n.tally()
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "掌握 ${c.mastered}，模糊 ${c.partial}，没讲到 ${c.missing}" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (p in n.points) {
            val (color, alpha) = when (p.status) {
                "mastered" -> t.good.color() to 1f
                "partial" -> t.warn.color() to 0.7f
                else -> t.line.color() to 1f
            }
            Box(Modifier.weight(1f).height(5.dp).alpha(alpha).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

// 自检：先自己想（最好说出来或写下来），再看参考要点，标答出来没有。结果会改对应要点的状态
@Composable
private fun SelfChecks(n: Note, model: AppModel) {
    if (n.checks.isEmpty()) return
    val t = LocalTokens.current
    val haptic = LocalHapticFeedback.current
    // 哪几道已经翻开了参考要点：只在这一页里
    var open by rememberSaveable(n.id) { mutableStateOf(listOf<Int>()) }
    Panel {
        SectionLabel("自检")
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            n.checks.forEachIndexed { i, c ->
                val point = c.point?.let { pt -> n.points.find { sameConcept(it.label, pt) } }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${i + 1}. ${c.q}",
                        style = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 1.7.em),
                        color = t.ink.color(),
                    )
                    // 翻开时参考要点从按钮的位置展开
                    AnimatedContent(i in open, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "check") { shown ->
                        if (!shown) {
                            GhostButton("想好了，看参考要点", { open = open + i }, Modifier.fillMaxWidth())
                        } else {
                            Column(
                                Modifier.fillMaxWidth().background(t.surface.color(), RoundedCornerShape(12.dp))
                                    .border(1.dp, t.line.color(), RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("参考要点", style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = t.muted.color())
                                Text(c.answer, style = TextStyles.small.copy(lineHeight = 1.7.em), color = t.ink.color())
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton("没答出", { model.learning.selfCheck(n.id, i, false) }, Modifier.weight(1f))
                                    PrimaryButton("答出来了", {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        model.learning.selfCheck(n.id, i, true)
                                    }, Modifier.weight(1f))
                                }
                                if (point != null) {
                                    FinePrint("检验的是“${point.label}”" + (point.checked?.let { "，上次自检在 ${date(it)}" } ?: "") + "。")
                                }
                            }
                        }
                    }
                }
            }
        }
        FinePrint("先不看答案，用自己的话说一遍，再对照参考要点。只看得懂不算，说得出来才算。想完整地检验一次，点下面的“讲给 AI 听”。")
    }
}

// 一篇概念笔记：从上到下是“它是什么、什么时候用、要点（带依据）、例子、易错、边界、立场、相关、术语、自检、来源”
@Composable
fun CardScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val context = LocalContext.current
    val n = st.library.find { it.id == st.ui.openCard }
    if (n == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("概念笔记", onBack = { l.go("library") })
            EmptyState("找不到这篇笔记", "它可能已经被清除。")
        }
        return
    }
    val c = n.tally()
    val material = n.isMaterialNote()
    val busy = st.ui.busy != null

    // 相关概念：知识库里有就打开那篇，还没有就去学习页讨论它
    fun openRelated(concept: String) {
        val other = st.library.find { it.isNoteFor(concept) }
        if (other != null) {
            l.openCard(other.id)
        } else {
            l.setConcept(concept)
            l.go("learn")
        }
    }
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        if (material) {
            // 材料笔记：回到这篇材料，接着围绕它讨论（材料只在这台设备上，删了就打不开）
            PrimaryButton(
                "回到这篇材料接着讨论",
                { n.materialId?.let { id -> model.run { openMaterial(id) } } },
                Modifier.fillMaxWidth(),
                enabled =
                !busy && n.materialId != null,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 讲给 AI 听：最能检验是不是真懂；讲完的复盘合并回这篇笔记（#142）
                if (n.points.size >= 2) SecondaryButton("讲给 AI 听", { l.teachBack(n) }, enabled = !busy)
                PrimaryButton(
                    if (c.partial + c.missing > 0) "从模糊处再讨论" else "再讨论一次",
                    { model.run { discussAgain(n) } },
                    Modifier.weight(1f),
                    enabled = !busy,
                )
            }
        }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar(
                if (material) "《${n.concept}》" else n.concept,
                (if (material) "材料笔记 · " else "") + "${n.sources.size} 次讨论 · 掌握 ${c.mastered}/${n.points.size}",
                onBack = { l.go("library") },
            ) {
                ExportButton("导出这篇笔记") { shareMarkdown(context, n.concept, noteMarkdown(n)) }
            }
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (material) {
                        Text(
                            "围绕这篇材料的每次讨论都沉淀在这里：从它里面弄懂的要点、例子和容易错的地方。讨论过的核心概念各有一篇自己的笔记，在下面的“相关概念”里。",
                            style = TextStyles.body,
                            color = t.muted.color(),
                        )
                    } else if (n.summary.isNotEmpty()) {
                        Text(n.summary, style = DisplaySm, color = t.ink.color())
                    } else {
                        Text("还没有用自己的话说清它。再讨论一次，复盘会补上。", style = TextStyles.body, color = t.muted.color())
                    }
                    if (n.useWhen.isNotEmpty()) UseWhen(n.useWhen)
                }
                if (n.points.isNotEmpty()) {
                    Panel {
                        SectionLabel("要点")
                        PointList(n.points)
                    }
                }
                Bullets("你举过的例子", n.examples)
                FixList("易错", n.misconceptions)
                Bullets("边界", n.boundaries, "它不是什么、和哪些相近的概念容易混。")
                StanceList("我的立场", n.stances)
                if (n.related.isNotEmpty()) {
                    Panel {
                        SectionLabel("相关概念")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (r in n.related) {
                                val has = st.library.any { it.isNoteFor(r) }
                                Chip(if (has) r else "$r · 去讨论", has, { openRelated(r) })
                            }
                        }
                    }
                }
                TermList("术语", n.terms)
                SelfChecks(n, model)
                Panel {
                    SectionLabel("来源")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (x in n.sources.asReversed()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${date(x.at)} · ${MODES[x.mode]?.name ?: x.mode}" + (if (x.source == "demo") " · 演示" else ""),
                                    style = TextStyles.small.copy(fontSize = 12.sp),
                                    color = t.muted.color(),
                                )
                                Text(x.topic, style = TextStyles.small, color = t.ink.color())
                                x.material?.let { Text("读《$it》", style = TextStyles.small.copy(fontSize = 12.5.sp), color = t.muted.color()) }
                                if (x.oneLine.isNotEmpty()) Text(x.oneLine, style = TextStyles.small.copy(fontSize = 12.5.sp), color = t.muted.color())
                            }
                        }
                    }
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}
