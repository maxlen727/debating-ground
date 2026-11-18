package debating.ground.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.Seat
import debating.ground.app.data.Session
import debating.ground.app.flow.seatName
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.Sheet
import debating.ground.app.ui.glass
import debating.ground.app.ui.mix
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors

// 辩论场：台面（双方席位 + 赛程条）、下一位的空讲台、双方阵容。
// 正方在左、反方在右，和真实的辩论赛场一样；和网页版 src/components/DebateArena.tsx 一致，只负责展示。

private val SIDE = mapOf("A" to "正方", "B" to "反方")
private val STANCE = mapOf("A" to "支持", "B" to "反对")

/** 这个席位由谁说：我 / AI（单人模式；多人房间不在原生版第一版里）。 */
fun seatWho(seat: Seat): Pair<String, String> = if (seat.type == "human") "我" to "你" else "AI" to "AI"

@Composable
private fun teamColors(team: String) = toneColors(if (team == "A") "a" else "b")

// 台面：左右是双方席位，中间是当前环节和赛程条（每一格是一次发言，按环节分组）
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebateBoard(s: Session, model: AppModel) {
    val t = LocalTokens.current
    val turn = s.turns.getOrNull(s.cursor)
    val over = s.finished || s.awaitingReview == true || turn == null
    val stage = turn?.let { s.stages.getOrNull(it.stage) }
    val motion = LocalMotion.current
    val pulse = if (motion && !over) {
        rememberInfiniteTransition(label = "board").animateFloat(1f, 0.45f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "now").value
    } else {
        1f
    }

    @Composable
    fun side(team: String) {
        val (bg, fg) = teamColors(team)
        val right = team == "B"
        Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(SIDE.getValue(team), style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = fg)
                Text(STANCE.getValue(team), style = TextStyles.small.copy(fontSize = 11.sp), color = t.muted.color())
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (right) Alignment.End else Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val seats = s.seats.filter { it.team == team }
                for (seat in if (right) seats.reversed() else seats) {
                    val (short, _) = seatWho(seat)
                    val on = !over && turn.role.id == seat.id
                    val mine = short == "我"
                    Box(Modifier.padding(top = 4.dp, end = 4.dp)) {
                        Text(
                            short,
                            style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = fg,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .then(if (on) Modifier.border(2.dp, fg.copy(alpha = pulse), RoundedCornerShape(11.dp)).padding(2.dp) else Modifier)
                                .widthIn(min = 30.dp).height(30.dp)
                                .background(bg, RoundedCornerShape(9.dp))
                                .then(if (mine) Modifier.border(1.5.dp, fg, RoundedCornerShape(9.dp)) else Modifier)
                                .padding(start = 6.dp, end = 8.dp, top = 5.dp),
                        )
                        Text(
                            "${seat.index + 1}",
                            style = TextStyles.label.copy(fontSize = 9.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
                            color = t.muted.color(),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(14.dp).background(t.surface.color(), CircleShape),
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // 台面也是悬浮的控件层：磨砂底
            .glass(RoundedCornerShape(20.dp), 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { model.learning.openSheet("roster") }
            .semantics {
                contentDescription = if (over) "比赛结束，查看双方阵容" else "${stage?.name.orEmpty()}，第 ${s.cursor + 1} 次发言，共 ${s.turns.size} 次。查看双方阵容"
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { side("A") }
            Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (over) "比赛结束" else stage?.name.orEmpty(),
                    style = TextStyles.small.copy(fontWeight = FontWeight.Bold),
                    color = t.ink.color(),
                    maxLines = 1,
                )
                Text(
                    if (over) "共 ${s.turns.size} 次发言" else "${s.cursor + 1} / ${s.turns.size}",
                    style = TextStyles.small.copy(fontSize = 11.sp),
                    color = t.muted.color(),
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { side("B") }
        }
        // 赛程条：每格一次发言，按环节分组
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, _) in s.stages.withIndex()) {
                val group = s.turns.withIndex().filter { it.value.stage == i }
                if (group.isEmpty()) continue
                Row(Modifier.weight(group.size.toFloat()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((k, tn) in group) {
                        val fg = teamColors(tn.role.team).second
                        val now = k == s.cursor && !over
                        val c = when {
                            k < s.cursor || over -> fg.mix(0.55f, t.line.color())
                            now -> fg
                            else -> t.line.color()
                        }
                        Box(Modifier.weight(1f).height(5.dp).alpha(if (now) pulse else 1f).background(c, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

// 下一位的空讲台：站在哪一侧、谁来说、做什么
@Composable
fun NextLectern(s: Session) {
    val t = LocalTokens.current
    val turn = s.turns.getOrNull(s.cursor) ?: return
    val seat = s.seats.find { it.id == turn.role.id } ?: return
    if (s.finished || s.awaitingReview == true) return
    val target = turn.target?.let { id -> s.seats.find { it.id == id } }
    val (_, who) = seatWho(seat)
    val waiting = if (seat.type == "human") "轮到你" else "AI 待发言"
    val fg = teamColors(seat.team).second
    val right = seat.team == "B"
    val border = fg.mix(0.45f, t.line.color())
    // 讲台只占自己需要的宽度，最多 78%
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = maxWidth * 0.78f)
                .drawBehind {
                    drawRoundRect(
                        border,
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = if (right) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("${seatName(seat)} · $who", style = TextStyles.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = fg)
            Text(
                turn.action + (target?.let { " → ${seatName(it)}" } ?: "") + " · $waiting",
                style = TextStyles.small.copy(fontSize = 13.sp),
                color = t.muted.color(),
                textAlign = if (right) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

// 双方阵容：每个席位由谁担任、认知背景是什么
@Composable
fun RosterSheet(s: Session, model: AppModel) {
    val t = LocalTokens.current
    Sheet("双方阵容", { model.learning.openSheet(null) }) {
        for (team in listOf("A", "B")) {
            val (bg, fg) = teamColors(team)
            Column(
                Modifier.fillMaxWidth().background(bg.mix(0.5f, t.raised.color()), RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${SIDE.getValue(team)} · ${STANCE.getValue(team)}", style = TextStyles.small.copy(fontWeight = FontWeight.Bold), color = fg)
                for (seat in s.seats.filter { it.team == team }) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(seatName(seat), style = TextStyles.small.copy(fontWeight = FontWeight.Bold), color = t.ink.color())
                            Text(" · ${seatWho(seat).second}", style = TextStyles.small, color = t.muted.color())
                        }
                        if (seat.type != "human" && seat.bg.isNotEmpty()) {
                            Text(
                                seat.bg,
                                style = TextStyles.small.copy(fontSize = 13.sp, lineHeight = 1.6.em),
                                color = t.muted.color(),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
