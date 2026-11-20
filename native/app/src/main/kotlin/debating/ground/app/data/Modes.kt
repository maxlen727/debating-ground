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
data class ModeInfo(val name: String, val short: String, val desc: String, val whenToUse: String)

val MODES = mapOf(
    "socratic" to ModeInfo("苏格拉底追问", "追问", "AI 陪你一步步想，用好问题和例子帮你发现哪里没想清；遇到想深挖的词，从那句话展开一个分支单独聊。", "刚接触一个概念，想一步步把它想清楚"),
    "roundtable" to ModeInfo("圆桌讨论", "圆桌", "三位不同学科背景的 AI 各说各的视角，你来主持；想听谁细说，就从他那句话展开一个分支。", "想从几个角度看同一个概念"),
    "teach" to ModeInfo("讲给 AI 听", "讲解", "你来讲，AI 扮演刚接触这个概念的学生，听你讲、复述、提疑问。从概念笔记里开始，讲完的复盘会更新这篇笔记。", "已经有些理解，检验能不能讲清楚"),
    "debate" to ModeInfo("正反辩论", "辩论", "分正反方按赛制交锋，席位和流程都可以自定。", "概念有争议或有多种解释"),
    "canvas" to ModeInfo("知识画布", "画布", "讨论像一棵树：遇到想深挖的词，就从那句话分出一枝单独聊，聊完带回主干。", "一个概念牵出很多新概念，想深挖又怕聊跑题"),
)

/** 学习页上列出的讨论方式和顺序。“讲给 AI 听”从概念笔记里开始；知识画布并进了追问和圆桌（#142），旧的画布讨论照常显示。 */
val MODE_ORDER = listOf("socratic", "roundtable", "debate")

/** 能从一句发言展开分支的讨论方式（和 shared/modes.ts 的 BRANCH_MODES 一致）。辩论不带分支。 */
val BRANCH_MODES = listOf("socratic", "roundtable", "canvas")

/** 讨论方式卡片上的字，和网页版学习页一致。 */
val MODE_GLYPH = mapOf("socratic" to "问", "roundtable" to "圆", "teach" to "讲", "debate" to "辩", "canvas" to "枝")
