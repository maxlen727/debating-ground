package debating.ground.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.MODES
import debating.ground.app.data.MODE_GLYPH
import debating.ground.app.data.MODE_ORDER
import debating.ground.app.flow.AppState
import debating.ground.app.ui.Banner
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.Chip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.Input
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.QuietLink
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 学习页：写下想弄懂的概念、选讨论方式、一句话开始。和网页版 src/screens/Learn.tsx 一致

private val EXAMPLES = listOf("机会成本", "贝叶斯定理", "边际效用", "认知失调", "光速不变原理", "梯度下降")

@Composable
fun LearnScreen(st: AppState, model: AppModel, now: Long) {
    val t = LocalTokens.current
    val l = model.learning
    val (config, _, session, library, ui) = st
    val busy = ui.busy != null
    val due = library.filter { it.due <= now }
    val canStart = !busy && config.concept.trim().isNotEmpty() && (ui.context == null || ui.context.text.trim().isNotEmpty())
    WithFloatingDock(dock = {
        ErrorBanner(ui.error) { l.clearError() }
        BusyStrip(ui.busy) { l.cancelAI() }
        // 一句话开始：写下概念就进讨论；想先看看题目、要点和角色，走准备页
        PrimaryButton(
            if (ui.context !=
                null
            ) {
                "按这个经过开始讨论"
            } else {
                "开始讨论"
            },
            { model.run { prepare(null, null, "start") } },
            Modifier.fillMaxWidth(),
            enabled = canStart,
        )
        QuietLink("先看看题目和角色", { model.run { prepare() } }, enabled = canStart)
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Brand(Modifier.weight(1f))
                    val label = when (config.aiMode) {
                        "remote" -> if (config.remote.url.isNotEmpty()) "我的后端" else "后端未连接"
                        "api" -> "直接连接"
                        else -> "演示模式"
                    }
                    Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { l.go("settings") }, contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            color = t.muted.color(),
                            style = TextStyles.small.copy(fontSize = 12.sp),
                            modifier = Modifier
                                .background(t.bg.color(), RoundedCornerShape(20.dp))
                                .border(1.dp, t.line.color(), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                if (session != null && !session.finished) {
                    Banner("继续讨论", "${session.concept} · 已点亮 ${session.covered.size}/${session.keyPoints.size}") { l.go("session") }
                }
                if (due.isNotEmpty()) {
                    Banner("今天有 ${due.size} 张卡片要复习", due.take(3).joinToString("、") { it.concept }, warm = true) {
                        if (due.size == 1) l.openCard(due[0].id) else l.go("library")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("今天想弄懂\n什么？", style = TextStyles.display, color = t.ink.color(), modifier = Modifier.semantics { heading() })
                    Input(
                        config.concept,
                        { l.setConcept(it) },
                        "想弄懂的概念",
                        placeholder = "写下一个概念，比如：机会成本",
                        maxLength = 80,
                        enabled = !busy,
                        style = TextStyles.title.copy(fontSize = TextSize.xl),
                        big = true,
                        onDone = { if (canStart) model.run { prepare(null, null, "start") } },
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EXAMPLES.forEach { c -> Chip(c, config.concept == c, { l.setConcept(c) }, enabled = !busy) }
                    }
                    // 写的是一件具体的事：AI 先请你交代经过，再出题
                    ui.context?.let { ask ->
                        Column(
                            Modifier.fillMaxWidth().background(t.accentSoft.color(), RoundedCornerShape(16.dp)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(ask.ask, color = t.ink.color(), style = TextStyles.small)
                            Input(ask.text, {
                                l.setContextDraft(it)
                            }, "事情的经过", placeholder = "用一两句话说说经过：发生了什么、谁怎么想", singleLine = false, minLines = 3, maxLength = 600, enabled = !busy)
                            FinePrint("AI 只按你写的经过来讨论，不会自己编细节。")
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("怎么讨论")
                    MODE_ORDER.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { m -> ModeCard(m, config.mode == m, !busy, Modifier.weight(1f)) { l.setMode(m) } }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
                if (library.isEmpty()) FinePrint("讨论结束后会生成理解复盘，存成知识卡片，按 1、3、7、14 天的间隔提醒你回顾。")
                if (config.aiMode == "demo") {
                    FinePrint("当前是演示模式：“机会成本”有完整示例，其他概念用通用模板。要让 AI 真正参与讨论，在设置里连接你自己部署的后端。")
                }
                Box(Modifier.size(8.dp))
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

@Composable
private fun ModeCard(mode: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTokens.current
    val info = MODES.getValue(mode)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .semantics { selected = on }
            .background(t.raised.color(), shape)
            .background(if (on) t.brand.color().copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent, shape)
            .border(if (on) 2.dp else 1.dp, if (on) t.brand.color() else t.line.color(), shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(if (on) t.brand.color() else t.bg.color(), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                MODE_GLYPH.getValue(mode),
                color = if (on) t.brandInk.color() else t.brand.color(),
                style = TextStyles.title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            info.name,
            color = t.ink.color(),
            style = TextStyles.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(info.desc, color = t.muted.color(), style = TextStyles.small.copy(fontSize = 13.sp))
        Text("适合：${info.whenToUse}", color = t.muted.color().copy(alpha = 0.85f), style = TextStyles.small.copy(fontSize = 11.5.sp))
    }
}
