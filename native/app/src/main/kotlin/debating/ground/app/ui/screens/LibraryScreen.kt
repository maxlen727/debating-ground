package debating.ground.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
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
import debating.ground.app.data.Card
import debating.ground.app.data.MODES
import debating.ground.app.data.SEARCH_MIN_CARDS
import debating.ground.app.data.dueLabel
import debating.ground.app.data.libraryMarkdown
import debating.ground.app.data.searchCards
import debating.ground.app.flow.AppState
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
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

// 知识库和知识卡片：和网页版 src/screens/Library.tsx 一致。卡片只保存在这台设备上，按间隔提醒复习。

@Composable
fun LibraryScreen(st: AppState, model: AppModel, now: Long) {
    val t = LocalTokens.current
    val l = model.learning
    val library = st.library
    val context = LocalContext.current
    val due = library.filter { it.due <= now }
    var tab by rememberSaveable { mutableStateOf(if (due.isNotEmpty()) "due" else "all") }
    // 搜索词只在这一页里，不存档；搜索时不分组，在全部卡片里找
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val list = if (searching) {
        searchCards(library, query)
    } else if (tab == "due") {
        due
    } else {
        library
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("知识库", "${library.size} 张卡片 · 只保存在这台设备") {
            if (library.isNotEmpty()) ExportButton("导出知识库") { shareMarkdown(context, "思辨场知识库", libraryMarkdown(library)) }
        }
        Column(
            Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (library.isEmpty()) {
                EmptyState("知识库还是空的", "完成一次讨论并生成复盘后，它会变成一张知识卡片，并按 1、3、7、14 天的间隔提醒你回顾。") {
                    PrimaryButton("开始学习", { l.go("learn") })
                }
            } else {
                if (library.size >= SEARCH_MIN_CARDS) SearchField(query) { query = it }
                if (searching) {
                    if (list.isNotEmpty()) Text("找到 ${list.size} 张卡片", style = TextStyles.small, color = t.muted.color())
                } else {
                    Segmented(listOf("due" to "待复习 ${due.size}", "all" to "全部 ${library.size}"), tab, "卡片范围") { tab = it }
                }
                if (list.isEmpty() && searching) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "没有找到和“${query.trim()}”有关的卡片。",
                            style = TextStyles.small,
                            color = t.muted.color(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        GhostButton("清除搜索", { query = "" }, small = true)
                    }
                } else if (list.isEmpty()) {
                    Text(
                        "今天没有要复习的卡片。",
                        style = TextStyles.small,
                        color = t.muted.color(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    for (c in list) CardItem(c, now) { l.openCard(c.id) }
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
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "搜索知识卡片" },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("搜概念、结论、术语", style = TextStyles.body, color = t.muted.color(), maxLines = 1)
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

@Composable
private fun CardItem(c: Card, now: Long, onOpen: () -> Unit) {
    val t = LocalTokens.current
    val isDue = c.due <= now
    Column(
        Modifier.fillMaxWidth()
            .background(t.raised.color(), RoundedCornerShape(14.dp))
            .border(1.dp, if (isDue) t.accent.color().mix(0.45f, t.line.color()) else t.line.color(), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.concept,
                style = TextStyles.body.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp),
                color = t.ink.color(),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (isDue) "待复习" else "下次 " + dueLabel(c.due, now),
                style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = if (isDue) FontWeight.SemiBold else FontWeight.Normal),
                color = if (isDue) t.accent.color() else t.muted.color(),
            )
        }
        if (c.oneLine.isNotEmpty()) {
            Text(
                c.oneLine,
                style = TextStyles.small.copy(lineHeight = 1.6.em),
                color = t.muted.color(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Meter(c)
        Text(
            "${MODES[c.mode]?.name.orEmpty()} · ${dateFormat.format(Date(c.created))} · 复习 ${c.reviews.size} 次",
            style = TextStyles.small.copy(fontSize = 12.sp),
            color = t.muted.color(),
        )
    }
}

// 每个要点一格：掌握了是绿的，还模糊是黄的
@Composable
private fun Meter(c: Card) {
    val t = LocalTokens.current
    val mastered = c.points.count { it.status == "mastered" }
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "已掌握 $mastered/${c.points.size}" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in c.points) {
            val (color, alpha) = when (p.status) {
                "mastered" -> t.good.color() to 1f
                "partial" -> t.warn.color() to 0.7f
                else -> t.line.color() to 1f
            }
            Box(Modifier.weight(1f).height(5.dp).alpha(alpha).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

// 一张知识卡片：先凭记忆做题，再看上次的复盘，最后说说记得怎么样
@Composable
fun CardScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val c = st.library.find { it.id == st.ui.openCard }
    if (c == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("知识卡片", onBack = { l.go("library") })
            EmptyState("找不到这张卡片", "它可能已经被清除。")
        }
        return
    }
    val key = "c:${c.id}"
    val picks = st.ui.quiz[key].orEmpty()
    var showReview by rememberSaveable(c.id) { mutableStateOf(false) }
    val open = showReview || c.quiz.isEmpty() || picks.size >= c.quiz.size
    val busy = st.ui.busy != null
    val haptic = LocalHapticFeedback.current
    WithFloatingDock(dock = {
        ErrorBanner(st.ui.error) { l.clearError() }
        BusyStrip(st.ui.busy) { l.cancelAI() }
        Text("这次回顾得怎么样？", style = TextStyles.small.copy(fontSize = 13.sp), color = t.muted.color())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("还有点模糊", { l.rateCard(c.id, false) }, Modifier.weight(1f))
            PrimaryButton("记得很清楚", {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                l.rateCard(c.id, true)
            }, Modifier.weight(1f))
        }
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar(c.concept, "${MODES[c.mode]?.name.orEmpty()} · 已复习 ${c.reviews.size} 次", onBack = { l.go("library") })
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(c.topic, style = TextStyles.knowledge, color = t.ink.color())
                if (c.quiz.isNotEmpty()) {
                    Panel {
                        SectionLabel("先凭记忆作答")
                        Quiz(c.quiz, picks) { q, o -> l.pickAnswer(key, q, o) }
                    }
                }
                if (open) {
                    Text(c.oneLine, style = DisplaySm, color = t.ink.color())
                    ReviewBody(c.points, c.misconceptions, c.stances, c.terms)
                } else {
                    GhostButton("先看上次的复盘", { showReview = true }, Modifier.fillMaxWidth())
                }
                GhostButton("从薄弱点再讨论一次", { model.run { discussAgain(c) } }, Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(bottom))
            }
        }
    }
}
