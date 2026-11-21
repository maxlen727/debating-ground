package debating.ground.app.data

// 讨论方式的名字和要点状态的文字，和网页版 shared/modes.ts 一致（ModesTest 比对）
val MODE_NAMES = mapOf(
    "socratic" to "苏格拉底追问",
    "roundtable" to "圆桌讨论",
    // 讲给 AI 听：从概念笔记里开始（#142）
    "teach" to "讲给 AI 听",
    "debate" to "正反辩论",
    "canvas" to "知识画布",
)

/** 辩论环节的规则（和网页版 shared/modes.ts 的 STAGE_KINDS 一致，DataTest 比对）。 */
val STAGE_KINDS = linkedMapOf(
    "opening" to "立论 · 双方一辩",
    "cross" to "质询 · 问答配对",
    "free" to "自由辩论 · 双方轮替",
    "closing" to "总结 · 双方末辩",
)

val STATUS_LABEL = mapOf("mastered" to "已掌握", "partial" to "还模糊", "missing" to "没讲到")

fun modeName(mode: String) = MODE_NAMES[mode] ?: mode

/** 讨论方式的说明，和 shared/modes.ts 的 MODES 一致（ModesTest 比对）。 */
data class ModeInfo(val name: String, val short: String, val desc: String)

val MODES = mapOf(
    "socratic" to ModeInfo("苏格拉底追问", "追问", "AI 一步步追问，陪你把它想清楚。"),
    "roundtable" to ModeInfo("圆桌讨论", "圆桌", "三位不同背景的 AI 各说视角，你来主持。"),
    "teach" to ModeInfo("讲给 AI 听", "讲解", "你来讲，AI 当学生听你讲、提疑问。"),
    "debate" to ModeInfo("正反辩论", "辩论", "分正反方，按赛制交锋。"),
    "canvas" to ModeInfo("知识画布", "画布", "从一句话分出枝来深挖，聊完带回主干。"),
)

/** 学习页上列出的讨论方式和顺序。“讲给 AI 听”从概念笔记里开始；知识画布并进了追问和圆桌（#142），旧的画布讨论照常显示。 */
val MODE_ORDER = listOf("socratic", "roundtable", "debate")

/** 能从一句发言展开分支的讨论方式（和 shared/modes.ts 的 BRANCH_MODES 一致）。辩论不带分支。 */
val BRANCH_MODES = listOf("socratic", "roundtable", "canvas")

/** 讨论方式卡片上的字，和网页版学习页一致。 */
val MODE_GLYPH = mapOf("socratic" to "问", "roundtable" to "圆", "teach" to "讲", "debate" to "辩", "canvas" to "枝")
