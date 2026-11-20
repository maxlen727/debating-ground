package debating.ground.app.ai

import debating.ground.app.data.ChatMessage
import debating.ground.app.data.Reflection
import debating.ground.app.data.Session
import debating.ground.app.data.Turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// 演示模式：没有连接 AI 时，用本地模板走完整个流程（约定 3：没有网络和密钥时，“机会成本”能走完）。
// 和网页版 src/lib/demo.ts 逐字一致（DemoTest 用网页版记下的调用逐条比对）。
// 网页版直接读改全局状态；这里输入讨论，输出结果和新的 demoStep，由调用方写回。

/** 一次演示调用的结果：[result] 和网页版返回的 JSON 一样，[demoStep] 是调用后的计数（没有讨论时为 null）。 */
data class DemoOutcome(val result: JsonElement, val demoStep: Int?)

class DemoException(message: String) : Exception(message)

private data class Role(val name: String, val title: String, val persona: String)

private val ROLES = mapOf(
    "socratic" to listOf(Role("苏珩", "追问者", "温和，但不放过任何含糊的说法")),
    "roundtable" to listOf(
        Role("周衡", "经济学家", "从稀缺和取舍看问题"),
        Role("沈知", "心理学家", "关注人为什么常常忽略代价"),
        Role("顾远", "企业管理者", "从真实的经营决策出发"),
    ),
    "teach" to listOf(Role("小满", "学生", "高二学生，爱较真")),
    "debate" to emptyList(),
    "canvas" to listOf(Role("林枝", "伙伴", "好奇心重，聊着聊着爱说“这个词值得单独聊聊”")),
)
private val GENERIC_ROLES = ROLES + (
    "roundtable" to listOf(
        Role("周衡", "理论研究者", "关心定义是否严谨"),
        Role("沈知", "心理学家", "关心人们为什么会误解它"),
        Role("顾远", "实践者", "关心它在真实场景里怎么用"),
    )
    )

private fun prepJson(concept: String, topic: String, keyPoints: List<String>, roles: List<Role>, opening: String) = mutableMapOf<String, JsonElement>(
    "concept" to JsonPrimitive(concept),
    "topic" to JsonPrimitive(topic),
    "keyPoints" to JsonArray(keyPoints.map(::JsonPrimitive)),
    "roles" to
        JsonArray(
            roles.map {
                JsonObject(mapOf("name" to JsonPrimitive(it.name), "title" to JsonPrimitive(it.title), "persona" to JsonPrimitive(it.persona)))
            },
        ),
    "opening" to JsonPrimitive(opening),
)

private fun costPrep(mode: String) = prepJson(
    "机会成本",
    mapOf(
        "socratic" to "周末去打工赚 300 元，真正的代价是什么？",
        "roundtable" to "为什么人们总是低估自己选择的代价？",
        "teach" to "向一个高中生讲清楚什么是机会成本",
        "debate" to "上大学的最大成本是学费",
        "canvas" to "周末去打工赚 300 元，真正的代价是什么？",
    ).getValue(mode),
    listOf("放弃的最佳选择", "只算次优那一项", "包含隐性代价", "区别于沉没成本", "用于比较决策"),
    ROLES.getValue(mode),
    mapOf(
        "socratic" to "我们从一个具体场景开始吧。你周六去打工赚了 300 元，你觉得这个选择花掉了你什么？",
        "roundtable" to "我先抛砖引玉：经济学里没有免费的午餐，任何选择都有代价。主持人想先听哪个角度？",
        "teach" to "老师好！我听说过“机会成本”这个词，但它和普通的“成本”有啥区别啊？花钱不就是成本吗？",
        "debate" to "",
        "canvas" to "我们从一个具体场景开始吧。你周六去打工赚了 300 元，你觉得这个选择花掉了你什么？聊到哪个词想深挖，点那句话就能展开一个分支。",
    ).getValue(mode),
)

private fun genericPrep(c: String, mode: String) = prepJson(
    c,
    mapOf(
        "socratic" to "「$c」到底是什么？它和相近的概念有什么不同？",
        "roundtable" to "「$c」在不同领域里意味着同一件事吗？",
        "teach" to "向一个高中生讲清楚「$c」",
        "debate" to "理解「$c」的关键在于它的定义，而不在于它的应用",
        "canvas" to "「$c」到底在讲什么？它牵扯到哪些别的概念？",
    ).getValue(mode),
    listOf("${c}的定义", "它要解决什么问题", "一个典型例子", "常见的误解", "和相近概念的区别"),
    GENERIC_ROLES.getValue(mode),
    mapOf(
        "socratic" to "先说说你的直觉：你觉得「$c」大概在讲什么？",
        "roundtable" to "我们今天聊「$c」。主持人能先说说，你是在什么场合第一次遇到它的吗？",
        "teach" to "老师好！「$c」这个词我常听到，但一直不懂。它到底是什么意思呀？",
        "debate" to "",
        "canvas" to "我们从直觉开始：你觉得「$c」大概在讲什么？聊到哪个词想深挖，就从那句话展开一个分支。",
    ).getValue(mode),
)

// 演示：辩手的认知背景（真实模型会按论题生成）
private val COST_DEBATERS = mapOf(
    "A" to listOf(
        "在县城读完高中、家里供两个孩子上学的大一新生",
        "做过三年学费贷款咨询的银行职员",
        "研究教育回报率的经济学研究生",
        "自己交学费、边打工边读书的夜校学生",
    ),
    "B" to listOf(
        "高中毕业直接进厂、五年后当上班组长的技术工人",
        "劳动经济学老师，关心被放弃的工资和经验",
        "中途辍学创业的程序员",
        "心理咨询师，常听学生谈“耽误的青春”",
    ),
)

private fun genericDebaters(c: String) = mapOf(
    "A" to listOf("在工作中天天用到「$c」的从业者", "教「$c」的中学老师", "写过「$c」科普文章的作者", "刚学完「$c」的大学生"),
    "B" to listOf("觉得「$c」被说得太玄的工程师", "研究「$c」反例的学者", "只在考试里见过「$c」的学生", "对「$c」有不同定义的另一学科的研究者"),
)

private data class Reply(val text: String, val covered: List<Int>, val terms: List<String>? = null)

private val COST_REPLIES = listOf(
    Reply("你说到了“少玩了一天”，这很重要：时间也有稀缺性，代价不只是钱。那如果那天你本来可以去做家教赚 500 元，这时打工的代价又是什么？", listOf(0, 2), listOf("稀缺性")),
    Reply("对，关键是你放弃的选项里最好的那一个。睡懒觉、打游戏、做家教都被放弃了，为什么只算做家教？", listOf(1)),
    Reply("很好。再想一个问题：上个月你已经交了 200 元的健身卡，这笔钱该不该算进周六的选择里？", listOf(3)),
    Reply("没错，已经花掉、拿不回来的钱不影响现在的选择。那你会怎样用机会成本做一次成本收益分析，决定周六干什么？", listOf(4), listOf("成本收益分析")),
)

// 演示模式里能查到的术语（顺序要和网页版一样：标术语时按这个顺序取前三个）
private val DEMO_POINTS = listOf("机会成本不等于花掉的钱", "时间也有机会成本", "只比较最好的那个放弃项")
private val DEMO_TERMS = linkedMapOf(
    "稀缺性" to ("想要的东西总比能拿到的多：时间、钱、精力都有限，用在一处就不能用在别处。" to "正因为资源稀缺，选择才有代价，机会成本才存在。"),
    "成本收益分析" to ("把一个选择的所有代价和好处列出来比较，好处大于代价才值得做。" to "机会成本是“代价”这一栏里最容易漏掉的一项。"),
    "必要条件" to ("一个结论要成立就必须满足的条件；不满足它，结论一定不成立。" to "辩论中用来检验对方主张的适用范围。"),
    "举证责任" to ("谁提出主张，谁就有责任拿出证据来支持它。" to "决定辩论中哪一方需要先证明自己的说法。"),
    "适用边界" to ("一个说法在什么条件下成立、超出什么范围就不再成立。" to "说清边界，才能避免把概念用错地方。"),
    "代表性" to ("一个例子能不能代表整体的情况；不具代表性的例子推不出普遍结论。" to "辩论中常用来质疑以偏概全的论证。"),
)

private fun termsIn(text: String) = DEMO_TERMS.keys.filter { text.contains(it) }.take(3)

private fun point(label: String, status: String, note: String) =
    JsonObject(mapOf("label" to JsonPrimitive(label), "status" to JsonPrimitive(status), "note" to JsonPrimitive(note)))

// 机会成本的演示复盘：和网页版 src/lib/demo.ts 的 COST_REVIEW 一字不差（DemoTest 比对）
private val COST_REVIEW = Json.parseToJsonElement(
    """
    {
      "oneLine": "你已经理解机会成本是“放弃的最好选择”，它和沉没成本的区别还需要再巩固。",
      "summary": "做一个选择时，放弃掉的那些选项里最好的一个的价值，就是这个选择的机会成本。",
      "useWhen": "在几个选项之间做决定，想知道“选这个到底花了什么”的时候。",
      "points": [
        {
          "label": "放弃的最佳选择",
          "status": "mastered",
          "note": "能用打工和家教的例子说清楚。",
          "evidence": "机会成本就是做一件事放弃的另一件事最好的价值。"
        },
        {
          "label": "只算次优那一项",
          "status": "partial",
          "note": "一开始把所有放弃的选项加在了一起。",
          "evidence": "机会成本是放弃的东西。"
        },
        {
          "label": "包含隐性代价",
          "status": "mastered",
          "note": "主动提到了时间和休息。",
          "evidence": "比如我周末去打工，就放弃了在家复习的收获。"
        },
        {
          "label": "区别于沉没成本",
          "status": "partial",
          "note": "知道健身卡不该算，但理由说得不完整。"
        },
        {
          "label": "用于比较决策",
          "status": "missing",
          "note": "还没有用它做过一次完整的选择比较。"
        }
      ],
      "examples": [
        "周末去打工赚 300 元，放弃了在家复习的收获。"
      ],
      "misconceptions": [
        {
          "said": "机会成本是所有放弃的选项加起来。",
          "fix": "只算放弃的选项中价值最高的那一个，因为你一次只能选一个。"
        }
      ],
      "boundaries": [
        "不是已经花掉、拿不回来的钱：那是沉没成本，不该影响现在的选择。"
      ],
      "related": [
        "沉没成本",
        "边际效用"
      ],
      "checks": [
        {
          "q": "小王周六可以打工赚 300 元、做家教赚 500 元，或者在家休息。他选了打工，他的机会成本是多少？为什么不是 800 元？",
          "answer": "500 元。只算放弃的选项里价值最高的那一个（家教），一次只能选一个，不能把放弃的都加起来。",
          "point": "只算次优那一项"
        },
        {
          "q": "已经付了 200 元、不能退的演唱会门票，当天你更想去加班。这 200 元该不该影响你的决定？",
          "answer": "不该。钱已经花了、拿不回来，选哪个都一样，是沉没成本；只比较去看和去加班各自接下来的得失。",
          "point": "区别于沉没成本"
        },
        {
          "q": "用机会成本比较一下：毕业后直接工作，还是读研三年？各列出你会放弃什么。",
          "answer": "读研放弃三年的工资、工作经验和人脉；直接工作放弃学历带来的机会。两边都算上放弃的最好选项，再比较。",
          "point": "用于比较决策"
        }
      ]
    }
    """,
).jsonObject

private fun debateText(s: Session, t: Turn): String {
    val positive = t.role.team == "A"
    val stance = if (positive) "支持" else "反对"
    val opponent = s.entries.asReversed().firstOrNull {
        it is ChatMessage && it.team != null && it.team.isNotEmpty() && it.team != t.role.team &&
            it.skipped != true
    } as ChatMessage?
    var text = when (t.action) {
        "立论" ->
            "谢谢主席、各位评委、对方辩友。我方对“${s.topic}”持${stance}立场。判断标准应当是：先把「${s.concept}」的含义说清楚，再比较收益、代价与可替代方案。" +
                if (positive) {
                    "我方认为，应当区分可以约束的实施风险与主张本身的价值，不能仅以存在风险推出否定结论。"
                } else {
                    "我方认为，不能从个别场景中的可行性直接推出普遍适用，支持方必须说明其必要条件和适用边界。"
                }

        "质询" -> "请问对方辩友：贵方结论成立的必要条件是什么？当该条件不满足时，贵方是否仍坚持同样的主张？"

        "答辩" -> "回应对方质询：我方主张以明确的适用范围为前提，不主张无条件适用。边界之外的情形需要另行判断，不能直接否定边界以内的结论。"

        "总结陈词" -> "谢谢主席。我方对本辩题的${stance}立场不变。核心分歧在于「${s.concept}」的适用条件、比较标准和举证责任。结论必须由相应论据支持，不能把可能性直接等同于必然性。谢谢。"

        else -> "对方辩友，从一个案例推导普遍结论，需要说明案例具有何种代表性。我方质疑的正是这一推导，而非案例是否可能发生。请对方回应其适用范围。"
    }
    if (opponent != null && t.action != "立论" && t.action != "总结陈词") {
        // 只引对方这次自己说的话，不连带他引用的上一段
        val own = opponent.text.split("\n\n").last()
        text = "对方辩友刚才表示：“${own.take(60)}${if (own.length > 60) "…" else ""}”\n\n" + text
    }
    return text
}

private fun reply(text: String, covered: List<Int>, terms: List<String>? = null) = buildJsonObject {
    put("text", text)
    putJsonArray("covered") { covered.forEach { add(JsonPrimitive(it)) } }
    if (terms != null) putJsonArray("terms") { terms.forEach { add(JsonPrimitive(it)) } }
}

private fun textOnly(text: String) = buildJsonObject { put("text", text) }

// 知识画布的分支：展开时先讲个大概，之后请学习者把它和概念连起来（好带一句话回主干）
private fun branchReply(s: Session, opening: Boolean): JsonObject {
    val topic = s.branches?.find { it.id == s.at }?.topic ?: ""
    val known = DEMO_TERMS[topic]
    if (opening) {
        return reply(
            if (known != null) {
                "好，先把「$topic」单独拎出来聊。${known.first}你身边有什么东西是这样的？"
            } else {
                "好，先把「$topic」单独拎出来聊。演示模式里我没法真的讲解它；连接 API 后，这里会先讲个大概、举个例子，再听你怎么想。"
            },
            emptyList(),
        )
    }
    return reply("这个例子挺具体的。要是让你用一句话说「$topic」和「${s.concept}」是什么关系，你会怎么说？想好了就可以带着这句话回主干。", emptyList())
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 跑一次演示。[purpose] 是用途（prepare、discuss、debate……），[payload] 只用到其中几个字段，
 * [session] 是当前的讨论（准备时为 null 也可以）。
 */
fun demoRun(purpose: String, payload: JsonObject, session: Session?): DemoOutcome {
    if (purpose == "prepare") {
        val concept = payload.str("concept").orEmpty()
        val mode = payload.str("mode") ?: "socratic"
        val prep = if (concept == "机会成本") costPrep(mode) else genericPrep(concept, mode)
        if (mode == "debate") {
            val d = if (concept == "机会成本") COST_DEBATERS else genericDebaters(concept)
            prep["debaters"] = JsonObject(d.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) })
        }
        // 学习者改过的题目照用
        payload.str("topic")?.takeIf { it.isNotEmpty() }?.let { prep["topic"] = JsonPrimitive(it) }
        return DemoOutcome(JsonObject(mapOf("data" to JsonObject(prep))), session?.demoStep)
    }
    val s = session ?: return DemoOutcome(JsonNull, null)
    val cost = s.concept == "机会成本"
    val n = s.keyPoints.size
    var step = s.demoStep
    fun nextStep(): Int = step++

    val result: JsonElement = when (purpose) {
        "discuss" -> {
            val task = payload.str("task")
            val role = s.personas.find { it.id == payload.str("roleId") } ?: s.personas.first()
            val st = nextStep()
            when {
                s.at != null -> branchReply(s, !task.isNullOrEmpty())

                cost && (s.mode == "socratic" || s.mode == "canvas") -> COST_REPLIES[st % COST_REPLIES.size].let { reply(it.text, it.covered, it.terms) }

                else -> {
                    val kp = s.keyPoints[st % n]
                    val text = when (s.mode) {
                        "socratic", "canvas" -> "你说的有一部分是对的。那我们看看「$kp」：你能用自己的话说说，并举一个例子吗？"
                        "teach" -> "老师，我还是没太懂「$kp」。能举个生活里的例子吗？"
                        else -> "从${role.title}的角度看，讨论「${s.concept}」时最容易被忽略的是「$kp」。主持人怎么看？"
                    }
                    reply(text, listOf(st % n))
                }
            }
        }

        "debate" -> {
            val st = nextStep()
            val text = debateText(s, s.turns[s.cursor])
            reply(text, listOf(st % n), termsIn(text))
        }

        "point" -> {
            // 从备选里挑一条还没列出的；“机会成本”给真实的例子，其他概念给通用说明
            val pool = if (cost) DEMO_POINTS else listOf("「${s.concept}」最容易被误解的地方", "「${s.concept}」在生活里的一个例子", "「${s.concept}」不适用的情况")
            val p = pool.find { it !in s.keyPoints } ?: throw DemoException("演示模式里的备选要点都已经列出了。")
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("point", p)
                        put("why", "演示模式：连接 API 后，AI 会根据目前的讨论提议一条还没讲到的要点。")
                    },
                )
            }
        }

        "term" -> {
            val term = payload.str("term").orEmpty()
            val known = DEMO_TERMS[term]
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("term", term)
                        put("definition", known?.first ?: "演示模式：连接 API 后，这里会用简单的话解释「$term」。")
                        put("relation", known?.second ?: "")
                    },
                )
            }
        }

        "hint" -> {
            val left = s.keyPoints.filterIndexed { i, _ -> i !in s.covered }
            textOnly(
                if (left.isNotEmpty()) {
                    "还没讲到「${left[0]}」。试着先举一个你亲身经历过的例子，再说说它和这个要点有什么关系。"
                } else {
                    "要点都讲到了。试着用一句话把它们串起来，看看能不能说给别人听懂。"
                },
            )
        }

        "explain" -> textOnly(
            when (payload.str("kind")) {
                "unclear" -> "演示模式：连接 API 后，这里会用更简单的话重新解释这句话。"
                "example" -> "演示模式：连接 API 后，这里会给出一个能说明这句话的具体例子。"
                else -> "演示模式：连接 API 后，这里会分辨这句话里哪些是事实、哪些是观点。"
            },
        )

        "reflect" -> if (cost) {
            reply("说得不错：你抓住了“放弃的东西”这个核心。还缺一点：机会成本只算放弃的选项里最好的那一个，不是所有放弃的加起来。", listOf(0))
        } else {
            reply("演示模式：连接 API 后，这里会对照要点清单评价你的小结，指出说对的部分和最关键的遗漏。", emptyList())
        }

        "review" -> if (cost && s.mode != "debate") {
            JsonObject(mapOf("data" to COST_REVIEW))
        } else {
            val lit = s.covered.size
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("oneLine", "规则式复盘：本次讨论点亮了 $lit/$n 个要点。连接 API 后可获得针对你发言的具体评价和自检题。")
                        // 规则式：“一句话”用学习者最后写的小结，自检题考还没点亮的要点
                        put("summary", s.entries.asReversed().firstOrNull { it is Reflection && it.text.isNotEmpty() }?.text.orEmpty())
                        put(
                            "points",
                            buildJsonArray {
                                s.keyPoints.forEachIndexed { i, k ->
                                    add(if (i in s.covered) point(k, "mastered", "讨论中点亮了这一点。") else point(k, "missing", "讨论中还没有讲到。"))
                                }
                            },
                        )
                        put("misconceptions", JsonArray(emptyList()))
                        put(
                            "checks",
                            buildJsonArray {
                                s.keyPoints.filterIndexed { i, _ -> i !in s.covered }.take(3).forEach { k ->
                                    add(
                                        buildJsonObject {
                                            put("q", "用你自己的话说说「$k」是什么意思，再举一个例子。")
                                            put("answer", "说得出「$k」的意思，并能举出一个讨论里没出现过的例子，就算答出来了。")
                                            put("point", k)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

        else -> JsonNull
    }
    return DemoOutcome(result, step)
}

/** 结果里的文字（讨论、提示等用途），没有就是 null。 */
fun JsonElement.textOrNull(): String? = (this as? JsonObject)?.get("text")?.jsonPrimitive?.content
