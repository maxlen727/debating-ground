package debating.ground.app.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Session
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Live
import debating.ground.app.flow.QUICK
import debating.ground.app.room.link
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.Quote
import debating.ground.app.ui.RevealText
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Sheet
import debating.ground.app.ui.SheetHost
import debating.ground.app.ui.SheetSpec
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 讨论页的面板：追问、讲解、查词、要点、提前结束（DiscussScreen.kt 的一部分）。

/* ---------- 面板：追问、讲解、查词、要点、提前结束 ---------- */

@Composable
internal fun Sheets(s: Session, st: AppState, live: Live?, model: AppModel) {
    val l = model.learning
    val ui = st.ui
    val sheetMsg = s.entries.find { it.id == ui.sheet } as? ChatMessage
    // 每个面板捕获打开时的数据：关上时托管它的 SheetHost 让它带着原来的内容沉下去
    val spec = when {
        ui.explain != null -> SheetSpec("explain") {
            ExplainSheet(ui.explain.label, ui.explain.quote, ui.explain.text, live, s.room != null) { l.closeExplain() }
        }

        ui.term != null -> SheetSpec("term") { TermSheet(s, st, model) }

        sheetMsg != null -> SheetSpec("quick-${sheetMsg.id}") { QuickSheet(s, sheetMsg, ui.busy != null, model) }

        ui.sheet == "points" -> SheetSpec("points") { PointsSheet(s, st, model) }

        ui.sheet == "finish" -> SheetSpec("finish") { FinishSheet(s, model) }

        ui.sheet == "roster" && s.mode == "debate" -> SheetSpec("roster") { RosterSheet(s, model) }

        ui.sheet == "tree" && s.mode == "canvas" -> SheetSpec("tree") { GraphSheet(s, ui.busy != null, model) }

        ui.sheet == "back" && s.mode == "canvas" -> SheetSpec("back") { BackSheet(s, model) }

        s.link != null && ui.sheet == "seats" && s.mode == "debate" -> SheetSpec("seats") { SeatSheet(s, model) }

        s.link != null && ui.sheet == "callon" -> SheetSpec("callon") { CallOnSheet(s, model) }

        s.link?.host == true && ui.invite -> SheetSpec("invite") { InviteSheet(s, model) }

        else -> null
    }
    SheetHost(spec)
}

@Composable
private fun ExplainSheet(label: String, quote: String, text: String, live: Live?, room: Boolean, onClose: () -> Unit) {
    val t = LocalTokens.current
    Sheet("$label · 讲解助手", onClose) {
        Quote(quote)
        val streaming = live?.takeIf { it.kind == "explain" && it.text.isNotEmpty() }
        when {
            streaming != null -> RevealText("explain", streaming.text, live = true, style = SheetText, color = t.ink.color())
            text.isNotEmpty() -> RevealText("explain", text, live = false, style = SheetText, color = t.ink.color())
            else -> Text("正在看这句话…", style = SheetText, color = t.muted.color())
        }
        FinePrint(if (room) "讲解只有你能看到，不占用发言轮次。" else "讲解不进入比赛记录，也不占用发言轮次。")
    }
}

private val SheetText = TextStyles.body.copy(fontSize = 15.5.sp, lineHeight = 1.8.em)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSheet(s: Session, m: ChatMessage, busy: Boolean, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val debate = s.mode == "debate"
    Sheet(if (s.mode == "canvas" && !s.finished) "追问或展开" else "追问这句", { l.openSheet(null) }) {
        Quote(if (m.text.length > 80) m.text.take(80) + "…" else m.text)
        Column(Modifier.fillMaxWidth().background(t.raised.color(), RoundedCornerShape(14.dp)).border(1.dp, t.line.color(), RoundedCornerShape(14.dp))) {
            QUICK.forEachIndexed { i, q ->
                if (i > 0) Box(Modifier.fillMaxWidth().size(height = 1.dp, width = 0.dp).background(t.line.color()))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) {
                        model.run { quick(q.kind, m.id) }
                    }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(q.label, style = TextStyles.body, color = t.ink.color(), modifier = Modifier.weight(1f))
                    Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(18.dp), tint = t.muted.color())
                }
            }
        }
        if (!m.terms.isNullOrEmpty()) {
            SectionLabel("这句里的新词")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.terms.forEach { term -> debating.ground.app.ui.Chip(term, on = false, onClick = { model.run { openTerm(term, m.id) } }) }
            }
        }
        if (s.mode == "canvas" && !s.finished) ForkForm(m, busy, model)
        if (s.room != null) {
            FinePrint("解释由讲解助手单独给你，不打断别人的发言。")
        } else if (debate) {
            FinePrint("辩论中，解释由讲解助手单独给出，不打断赛程。")
        }
    }
}

@Composable
private fun TermSheet(s: Session, st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val term = st.ui.term ?: return
    Sheet(term.term, {
        model.learning.closeTerm()
    }, titleStyle = TextStyles.knowledge.copy(fontWeight = FontWeight.SemiBold, fontSize = TextSize.xl, lineHeight = 1.3.em, letterSpacing = 0.03.em)) {
        if (term.definition.isNotEmpty()) {
            Text(term.definition, style = TextStyles.knowledge, color = t.ink.color())
            if (term.relation.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("和「${s.concept}」的关系", style = TextStyles.small.copy(fontWeight = FontWeight.SemiBold), color = t.ink.color())
                    Text(term.relation, style = TextStyles.small.copy(lineHeight = 1.75.em), color = t.ink.color())
                }
            }
            FinePrint((if (s.review != null) "已存进这次的知识卡片。" else "已记下，复盘后会和这次的知识卡片存在一起。") + "查词不占用发言轮次。")
            if (s.mode == "canvas" && !s.finished) {
                SecondaryButton(
                    "展开一个分支，聊聊「${term.term}」",
                    { model.run { openBranch(term.messageId, term.term) } },
                    Modifier.fillMaxWidth(),
                    enabled =
                    st.ui.busy == null,
                )
            }
        } else {
            Text("正在查这个词……", style = TextStyles.knowledge, color = t.muted.color())
        }
    }
}

// 补充讨论要点：AI 提议的先待确认，采纳后才点亮追踪、进入复盘
@Composable
private fun PointsSheet(s: Session, st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val debate = s.mode == "debate"
    val full = s.keyPoints.size >= 6
    var text by rememberSaveable { mutableStateOf("") }
    Sheet(if (debate) "核心争点" else "要弄懂的要点", { l.openSheet(null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.keyPoints.forEachIndexed { i, k ->
                val lit = i in s.covered
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}.", style = TextStyles.knowledge, color = if (lit) t.litInk.color() else t.muted.color(), modifier = Modifier.width(22.dp))
                    Text(k, style = TextStyles.knowledge, color = if (lit) t.litInk.color() else t.ink.color())
                }
            }
        }
        val proposals = s.proposedPoints.orEmpty()
        if (proposals.isNotEmpty()) {
            SectionLabel("待确认")
            proposals.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.text, style = TextStyles.knowledge.copy(lineHeight = 1.6.em), color = t.ink.color())
                        Text(if (p.source == "ai") "AI 提议" else "${p.proposer}提议", style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color())
                    }
                    // 多人讨论里由房主决定
                    if (s.link?.host != false) {
                        GhostButton("忽略", { l.decidePoint(p.id, false) }, small = true)
                        SecondaryButton("采纳", { l.decidePoint(p.id, true) }, enabled = !full, small = true)
                    }
                }
            }
        }
        if (full) {
            FinePrint("要点已经有 6 个了，先讲透这些。")
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Input(text, { text = it }, "新的要点", placeholder = if (debate) "写一个值得交锋的争点" else "写一个还想弄懂的要点", maxLength = 40, onDone = {
                        if (l.proposePoint(text)) {
                            text =
                                ""
                        }
                    })
                }
                SecondaryButton("加入", { if (l.proposePoint(text)) text = "" }, enabled = text.isNotBlank())
            }
            GhostButton("让 AI 提议一条", { model.run { suggestPoint() } }, Modifier.fillMaxWidth(), enabled = st.ui.busy == null, icon = IconPaths.BULB)
        }
        FinePrint(
            if (s.link?.host == false) {
                "你的提议会给房主确认，采纳后大家的要点都会更新。"
            } else if (debate) {
                "争点是交锋的方向，不改变赛程；采纳后会点亮追踪，并进入复盘。"
            } else {
                "采纳后会点亮追踪，并进入复盘。"
            },
        )
    }
}

@Composable
private fun FinishSheet(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    Sheet("现在结束比赛？", { l.openSheet(null) }) {
        Text("还有 ${s.turns.size - s.cursor} 次发言没有进行，包括后面的总结陈词。结束后${if (s.room != null) "每个人" else "你"}只能按已有的发言复盘。", style = SheetText, color = t.ink.color())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("继续比赛", { l.openSheet(null) }, Modifier.weight(1f))
            PrimaryButton(
                "结束并复盘",
                {
                    l.openSheet(null)
                    model.run { finish() }
                },
                Modifier.weight(1f),
            )
        }
    }
}
