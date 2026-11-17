package debating.ground.app.data

// 讨论方式的名字和要点状态的文字，和网页版 shared/modes.ts 一致（ModesTest 比对）
val MODE_NAMES = mapOf(
    "socratic" to "苏格拉底追问",
    "roundtable" to "圆桌讨论",
    // 反向讲解已并进苏格拉底追问（讨论中“换我来讲”）；保留名字，旧的讨论和知识卡片还要显示
    "teach" to "反向讲解",
    "debate" to "正反辩论",
    "canvas" to "知识画布",
)

val STATUS_LABEL = mapOf("mastered" to "已掌握", "partial" to "还模糊", "missing" to "没讲到")

fun modeName(mode: String) = MODE_NAMES[mode] ?: mode
