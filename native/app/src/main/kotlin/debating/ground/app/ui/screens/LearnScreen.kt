package debating.ground.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.MODES
import debating.ground.app.data.MODE_GLYPH
import debating.ground.app.data.MODE_ORDER
import debating.ground.app.flow.AppState
import debating.ground.app.ui.Banner
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.Capsule
import debating.ground.app.ui.Chip
import debating.ground.app.ui.EaseOut
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.enabledAlpha
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.scrollRow
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 学习页：写下想弄懂的概念、选讨论方式、一句话开始。和网页版 src/screens/Learn.tsx 一致

private val EXAMPLES = listOf("机会成本", "贝叶斯定理", "边际效用", "认知失调", "光速不变原理", "梯度下降")

@Composable
fun LearnScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val (config, _, session, _, ui) = st
    val busy = ui.busy != null
    val canStart = !busy && config.concept.trim().isNotEmpty() && (ui.context == null || ui.context.text.trim().isNotEmpty())
    WithFloatingDock(dock = {
        ErrorBanner(ui.error) { l.clearError() }
        BusyStrip(ui.busy) { l.cancelAI() }
        // 写下概念后，开始按钮才从底部升起；没写时不摆一条点不了的灰按钮
        StartBar(
            visible = config.concept.trim().isNotEmpty(),
            label = if (ui.context != null) "按这个经过开始" else "开始讨论",
            detail = "${MODES[config.mode]?.name.orEmpty()} · ${config.concept.trim()}",
            enabled = canStart,
            onPreview = { model.run { prepare() } },
            onStart = { model.run { prepare(null, null, "start") } },
        )
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
                    Row(Modifier.scrollRow(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    // 读材料（#144、#154、#166）：从一篇论文或文章开始，直接围绕它讨论
                    MaterialEntry(enabled = !busy) { model.run { openMaterials() } }
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
                // 同学发来了邀请链接：在 App 里加入
                GhostButton("加入同学的讨论", { l.go("join") }, Modifier.fillMaxWidth(), small = true, icon = IconPaths.PEOPLE)
                if (config.aiMode == "demo") {
                    FinePrint("演示模式只有“机会成本”是完整示例。在设置里连接 AI，就能讨论任何概念。")
                }
                Box(Modifier.size(8.dp))
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

// 开始：左边“先看题目”（先看看题目、要点和角色再开始），右边是主按钮，写明用哪种方式讨论哪个概念
@Composable
private fun StartBar(visible: Boolean, label: String, detail: String, enabled: Boolean, onPreview: () -> Unit, onStart: () -> Unit) {
    val t = LocalTokens.current
    val motion = LocalMotion.current
    AnimatedVisibility(
        visible,
        enter = if (motion) slideInVertically(tween(420, easing = EaseOut)) { it } + fadeIn(tween(240)) else EnterTransition.None,
        exit = if (motion) slideOutVertically(tween(220)) { it } + fadeOut(tween(160)) else ExitTransition.None,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("先看题目", onPreview, Modifier.height(56.dp), enabled = enabled)
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, label = "start-press")
            Row(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .scale(scale)
                    .alpha(if (enabled) 1f else 0.5f)
                    .shadow(12.dp, Capsule, spotColor = t.brand.color().copy(alpha = 0.5f))
                    .background(t.brand.color(), Capsule)
                    .clickable(src, null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onStart)
                    .padding(start = 22.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        color = t.brandInk.color(),
                        style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold, fontSize = TextSize.md),
                        maxLines = 1,
                    )
                    Text(
                        detail,
                        color = t.brandInk.color().copy(alpha = 0.72f),
                        style = TextStyles.small.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(40.dp).background(t.brandInk.color().copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(lineIcon("send", IconPaths.SEND), null, Modifier.size(20.dp), tint = t.brandInk.color())
                }
            }
        }
    }
}

@Composable
private fun ModeCard(mode: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTokens.current
    val info = MODES.getValue(mode)
    val shape = RoundedCornerShape(14.dp)
    // 选中时边框、底色和字块渐变过去，不是一下子跳
    val edge by animateColorAsState(if (on) t.brand.color() else t.line.color(), label = "mode-edge")
    val tint by animateColorAsState(if (on) t.brand.color().copy(alpha = 0.07f) else t.brand.color().copy(alpha = 0f), label = "mode-tint")
    val glyphBg by animateColorAsState(if (on) t.brand.color() else t.bg.color(), label = "mode-glyph")
    val width by animateDpAsState(if (on) 2.dp else 1.dp, label = "mode-width")
    Column(
        modifier
            .semantics { selected = on }
            .background(t.raised.color(), shape)
            .background(tint, shape)
            .border(width, edge, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(glyphBg, RoundedCornerShape(10.dp)),
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
    }
}

// 读材料的入口（.material-entry）：内容层的一张卡片，点开去贴材料
@Composable
private fun MaterialEntry(enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(enabledAlpha(enabled))
            .background(t.raised.color(), shape)
            .border(1.dp, t.line.color(), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(lineIcon("note", IconPaths.NOTE), null, Modifier.size(18.dp), tint = t.ink.color())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("从一篇论文或文章开始", style = TextStyles.body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp), color = t.ink.color())
            Text("贴上正文、链接或 PDF，直接围绕它讨论", style = TextStyles.small.copy(fontSize = 13.sp), color = t.muted.color())
        }
        Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(18.dp), tint = t.muted.color())
    }
}
