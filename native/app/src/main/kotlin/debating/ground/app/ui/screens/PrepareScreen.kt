package debating.ground.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.MODES
import debating.ground.app.flow.AppState
import debating.ground.app.flow.debateTurns
import debating.ground.app.flow.seatName
import debating.ground.app.flow.slots
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.Chip
import debating.ground.app.ui.Dock
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors

// 准备页：开始前看看、改改讨论题、要点和角色。和网页版 src/screens/Prepare.tsx 一致（赛程编辑在辩论台那一步做）

@Composable
fun PrepareScreen(st: AppState, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    val plan = st.plan
    if (plan == null) {
        TopBar("准备讨论", onBack = { l.go("learn") })
        return
    }
    val debate = plan.mode == "debate"
    val busy = st.ui.busy != null
    Column(Modifier.fillMaxSize()) {
        TopBar(plan.concept, MODES[plan.mode]?.name, onBack = { l.go("learn") })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                (if (plan.source == "api") "由 ${plan.model} 准备" else "演示模板") + "，开始前都可以改。" + if (plan.previous != null) "这次会从上次还模糊的地方切入。" else "",
                color = t.muted.color(),
                style = TextStyles.small,
            )
            if (plan.context != null) {
                Panel {
                    SectionLabel("你交代的经过")
                    Input(plan.context, { v -> l.editPlan { it.copy(context = v) } }, "事情的经过", singleLine = false, minLines = 3, maxLength = 600)
                    FinePrint("讨论时 AI 会以这段经过为准。")
                }
            }
            Panel {
                SectionLabel(if (debate) "辩题" else "讨论题")
                Input(plan.topic, { v ->
                    l.editPlan { it.copy(topic = v) }
                }, "讨论题", singleLine = false, minLines = 2, maxLength = 200, style = TextStyles.title)
                // 角色是按原来的题目设定的：题目改了，提示一下，可以只更新角色
                if (plan.source == "api" && plan.rolesFor != null && plan.topic.trim().isNotEmpty() && plan.topic.trim() != plan.rolesFor.trim()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (debate) "辩手背景" else "角色"}还是按原来的题目设定的。", color = t.warn.color(), style = TextStyles.small, modifier = Modifier.weight(1f))
                        GhostButton("按新题目更新${if (debate) "辩手背景" else "角色"}", { model.run { refreshRoles() } }, enabled = !busy, small = true)
                    }
                }
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel(if (debate) "核心争点" else "要弄懂的要点") }
                    Text("${plan.keyPoints.size} / 6", color = t.muted.color(), style = TextStyles.small)
                }
                plan.keyPoints.forEachIndexed { i, k ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(24.dp).background(t.bg.color(), CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = t.muted.color(), style = TextStyles.small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                        }
                        Input(
                            k,
                            { v -> l.editPlan { p -> p.copy(keyPoints = p.keyPoints.mapIndexed { j, x -> if (j == i) v else x }) } },
                            "要点 ${i + 1}",
                            Modifier.weight(1f),
                            maxLength = 40,
                            style = TextStyles.knowledge,
                        )
                        val canDrop = plan.keyPoints.size > 2
                        Box(
                            Modifier.size(44.dp).alpha(if (canDrop) 1f else 0.45f).clickable(enabled = canDrop, role = Role.Button) {
                                l.editPlan { p -> p.copy(keyPoints = p.keyPoints.filterIndexed { j, _ -> j != i }) }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(lineIcon("close", IconPaths.CLOSE), "删除要点 ${i + 1}", Modifier.size(16.dp), tint = t.muted.color()) }
                    }
                }
                if (plan.keyPoints.size < 6) GhostButton("添加要点", { l.editPlan { it.copy(keyPoints = it.keyPoints + "") } }, small = true, icon = IconPaths.PLUS)
                FinePrint(
                    if (debate) {
                        "3 到 4 个核心争点就够了：它们是交锋的方向，不是要逐条完成的清单，也不影响赛程。复盘看的是你对每个争点背后概念的理解，不看输赢。"
                    } else {
                        "讨论中讲清楚一个要点，它会在顶部点亮；复盘也按这份清单逐条评估。"
                    },
                )
            }
            if (!debate) {
                Panel {
                    SectionLabel(if (plan.personas.size > 1) "圆桌嘉宾" else "讨论对象")
                    plan.personas.forEachIndexed { i, r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val (bg, fg) = toneColors(r.tone)
                            Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                                Text(r.name.take(1), color = fg, style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Input(r.name, { v ->
                                        l.editPlan { p ->
                                            p.copy(
                                                personas = p.personas.mapIndexed { j, x ->
                                                    if (j ==
                                                        i
                                                    ) {
                                                        x.copy(name = v)
                                                    } else {
                                                        x
                                                    }
                                                },
                                            )
                                        }
                                    }, "角色 ${i + 1} 名字", Modifier.weight(1f), maxLength = 20)
                                    Input(r.title, { v ->
                                        l.editPlan { p ->
                                            p.copy(
                                                personas = p.personas.mapIndexed { j, x ->
                                                    if (j ==
                                                        i
                                                    ) {
                                                        x.copy(title = v)
                                                    } else {
                                                        x
                                                    }
                                                },
                                            )
                                        }
                                    }, "角色 ${i + 1} 身份", Modifier.weight(1f), maxLength = 20)
                                }
                                Input(
                                    r.persona,
                                    { v -> l.editPlan { p -> p.copy(personas = p.personas.mapIndexed { j, x -> if (j == i) x.copy(persona = v) else x }) } },
                                    "角色 ${i + 1} 认知背景",
                                    placeholder = "认知背景：会影响它的知识范围和表达方式",
                                    singleLine = false,
                                    minLines = 2,
                                    maxLength = 300,
                                )
                            }
                        }
                    }
                }
            } else {
                Panel {
                    SectionLabel("席位")
                    slots(st.config).forEach { seat ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    seatName(seat),
                                    color = t.ink.color(),
                                    style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f),
                                )
                                Chip("我", seat.type == "human", { setSeat(model, seat.team, seat.index, "human") })
                                Chip("AI", seat.type == "ai", { setSeat(model, seat.team, seat.index, "ai") })
                            }
                            if (seat.type != "human") {
                                Input(
                                    seat.bg,
                                    { v ->
                                        l.editConfig { c ->
                                            c.copy(
                                                seats = c.seats.mapValues { (team, list) ->
                                                    if (team ==
                                                        seat.team
                                                    ) {
                                                        list.mapIndexed { j, x -> if (j == seat.index) x.copy(bg = v) else x }
                                                    } else {
                                                        list
                                                    }
                                                },
                                            )
                                        }
                                    },
                                    "${seatName(seat)} 认知背景",
                                    placeholder = "认知背景",
                                    singleLine = false,
                                    minLines = 2,
                                    maxLength = 500,
                                )
                            }
                        }
                    }
                }
                FinePrint("共 ${debateTurns(st.config).size} 次发言。简化赛制：双方一辩立论；二辩质询对方三辩（不足三席时取末席）；自由辩论轮流；末辩总结，反方先行。")
            }
            Box(Modifier.size(8.dp))
        }
        Dock {
            ErrorBanner(st.ui.error) { l.clearError() }
            BusyStrip(st.ui.busy) { l.cancelAI() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    "重新生成",
                    {
                        l.setConcept(plan.concept)
                        l.setMode(plan.mode)
                        model.run { prepare(plan.previous) }
                    },
                    enabled = !busy,
                )
                PrimaryButton("开始讨论", { l.startSession() }, Modifier.weight(1f), enabled = !busy)
            }
        }
    }
}

private fun setSeat(model: AppModel, team: String, index: Int, type: String) = model.learning.editConfig { c ->
    c.copy(seats = c.seats.mapValues { (t, list) -> if (t == team) list.mapIndexed { j, x -> if (j == index) x.copy(type = type) else x } else list })
}
