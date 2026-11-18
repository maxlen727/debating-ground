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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.AppModel
import debating.ground.app.data.MODES
import debating.ground.app.data.STAGE_KINDS
import debating.ground.app.data.Stage
import debating.ground.app.flow.AppState
import debating.ground.app.flow.debateTurns
import debating.ground.app.flow.seatName
import debating.ground.app.flow.slots
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.Chip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.GhostButton
import debating.ground.app.ui.IconPaths
import debating.ground.app.ui.Input
import debating.ground.app.ui.Panel
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.SecondaryButton
import debating.ground.app.ui.SectionLabel
import debating.ground.app.ui.Stepper
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import debating.ground.app.ui.lineIcon
import debating.ground.app.ui.scrollWithEdge
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import debating.ground.app.ui.theme.toneColors

// 准备页：开始前看看、改改讨论题、要点和角色，辩论还有席位和比赛流程。和网页版 src/screens/Prepare.tsx 一致

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
    WithFloatingDock(dock = {
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
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar(plan.concept, MODES[plan.mode]?.name, onBack = { l.go("learn") })
            Column(
                Modifier.weight(1f).scrollWithEdge().padding(horizontal = 16.dp),
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
                            Text(
                                "${if (debate) "辩手背景" else "角色"}还是按原来的题目设定的。",
                                color = t.warn.color(),
                                style = TextStyles.small,
                                modifier = Modifier.weight(1f),
                            )
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
                    if (plan.keyPoints.size <
                        6
                    ) {
                        GhostButton("添加要点", { l.editPlan { it.copy(keyPoints = it.keyPoints + "") } }, small = true, icon = IconPaths.PLUS)
                    }
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
                                        { v ->
                                            l.editPlan { p ->
                                                p.copy(
                                                    personas = p.personas.mapIndexed { j, x ->
                                                        if (j ==
                                                            i
                                                        ) {
                                                            x.copy(persona = v)
                                                        } else {
                                                            x
                                                        }
                                                    },
                                                )
                                            }
                                        },
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
                        // 按阵营分组：正方的席位在一起，反方的在一起，各自带人数
                        slots(st.config).sortedBy { it.team }.forEach { seat ->
                            if (seat.index == 0) TeamHeader(seat.team, st.config.counts[seat.team] ?: 1) { n -> l.setTeamCount(seat.team, n) }
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
                    StagesPanel(st.config.stages, model)
                    FinePrint("共 ${debateTurns(st.config).size} 次发言。简化赛制：双方一辩立论；二辩质询对方三辩（不足三席时取末席）；自由辩论轮流；末辩总结，反方先行。")
                }
                Box(Modifier.size(8.dp))
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

private fun setSeat(model: AppModel, team: String, index: Int, type: String) = model.learning.editConfig { c ->
    c.copy(seats = c.seats.mapValues { (t, list) -> if (t == team) list.mapIndexed { j, x -> if (j == index) x.copy(type = type) else x } else list })
}

// 一方的标题和人数（1 到 4 人）
@Composable
private fun TeamHeader(team: String, count: Int, onCount: (Int) -> Unit) {
    val fg = toneColors(if (team == "A") "a" else "b").second
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (team == "A") "正方 · 支持" else "反方 · 反对", color = fg, style = TextStyles.body.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        Stepper(count, 1, 4, if (team == "A") "正方人数" else "反方人数", onCount)
    }
}

// 比赛流程：每个环节的名称、规则、轮数，可以调整顺序、删掉、添加（最多 6 个）
@Composable
private fun StagesPanel(stages: List<Stage>, model: AppModel) {
    val t = LocalTokens.current
    val l = model.learning
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("比赛流程") }
            if (stages.size < 6) GhostButton("添加环节", { l.editStages { it + Stage("自由辩论", "free", 1) } }, small = true, icon = IconPaths.PLUS)
        }
        stages.forEachIndexed { i, stage ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}", color = t.muted.color(), style = TextStyles.body.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Input(stage.name, { v ->
                        l.editStages { list ->
                            list.mapIndexed { j, x ->
                                if (j ==
                                    i
                                ) {
                                    x.copy(name = v)
                                } else {
                                    x
                                }
                            }
                        }
                    }, "环节 ${i + 1} 名称", maxLength = 20)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            KindPicker(stage.kind, "环节 ${i + 1} 规则") { k ->
                                l.editStages { list ->
                                    list.mapIndexed { j, x ->
                                        if (j ==
                                            i
                                        ) {
                                            x.copy(kind = k)
                                        } else {
                                            x
                                        }
                                    }
                                }
                            }
                        }
                        Stepper(stage.rounds, 1, 3, "环节 ${i + 1} 轮数") { n ->
                            l.editStages { list ->
                                list.mapIndexed { j, x ->
                                    if (j ==
                                        i
                                    ) {
                                        x.copy(rounds = n)
                                    } else {
                                        x
                                    }
                                }
                            }
                        }
                    }
                }
                Column {
                    IconAction(IconPaths.UP, "上移环节 ${i + 1}", enabled = i > 0) {
                        l.editStages { list -> list.toMutableList().apply { add(i - 1, removeAt(i)) } }
                    }
                    IconAction(IconPaths.CLOSE, "删除环节 ${i + 1}", enabled = stages.size > 1) {
                        l.editStages { list -> list.filterIndexed { j, _ -> j != i } }
                    }
                }
            }
        }
    }
}

// 环节规则：点开选一种（网页版是下拉框）
@Composable
private fun KindPicker(kind: String, label: String, onPick: (String) -> Unit) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = 44.dp,
            ).background(t.bg.color(), RoundedCornerShape(12.dp)).border(1.dp, t.line.color(), RoundedCornerShape(12.dp))
                .clickable(role = Role.DropdownList) { open = true }.semantics { contentDescription = label }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                STAGE_KINDS[kind] ?: kind,
                color = t.ink.color(),
                style = TextStyles.small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(lineIcon("chevron", IconPaths.CHEVRON), null, Modifier.size(16.dp).rotate(90f), tint = t.muted.color())
        }
        DropdownMenu(open, { open = false }, containerColor = t.raised.color()) {
            STAGE_KINDS.forEach { (k, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = TextStyles.small, color = if (k == kind) t.brand.color() else t.ink.color()) },
                    onClick = {
                        open = false
                        onPick(k)
                    },
                )
            }
        }
    }
}

@Composable
private fun IconAction(path: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(44.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics {
            contentDescription =
                label
        },
        contentAlignment = Alignment.Center,
    ) { Icon(lineIcon(path, path), null, Modifier.size(16.dp), tint = t.ink.color()) }
}
