package debating.ground.app.data

import kotlinx.serialization.Serializable

// 知识画布：讨论是一棵树。所有发言仍按时间存在一个列表里，每条带着自己所在的分支；
// 这里按分支把它们取出来。和网页版 src/lib/canvas.ts 一致

/** 这场讨论能不能展开分支：追问、圆桌（和旧的知识画布）可以；辩论和多人房间不行（#142）。 */
fun Session.canBranch(): Boolean = mode in BRANCH_MODES && room == null

/** 只属于某一层的发言（主干传 null）。 */
fun Session.levelEntries(at: String?): List<Entry> = entries.filter { it.branch == at }

/** 从某一层直接展开的分支。 */
fun Session.childrenOf(parent: String?): List<Branch> = branches.orEmpty().filter { it.parent == parent }

fun Session.branchById(id: String?): Branch? = id?.let { branches?.find { b -> b.id == it } }

/** 从这条发言展开过的分支。 */
fun Session.branchesFrom(entryId: String): List<Branch> = branches.orEmpty().filter { it.from == entryId }

/** 一个分支里有几条发言（只算 AI 和学习者说的话）。 */
fun Session.talkCount(at: String?): Int = levelEntries(at).count { it is ChatMessage }

/* ---------- 分支图（像 GitLens 的提交图） ---------- */
// 每条发言、每句带回来的话是一行，按时间排；主干在第 0 条轨道，每个分支占一条轨道。
// 分支的轨道在它“活着”的那段行里被占用（从分叉那一行到它最后一行、或带回的那一行），用完可以给后面的分支复用。

@Serializable
data class GraphRow(val id: String, val index: Int, var lane: Int, val branch: String? = null, val kind: String, val who: String, val text: String)

@Serializable
data class GraphEdge(val kind: String, val branch: String, val fromRow: Int, val fromLane: Int, val toRow: Int, val toLane: Int)

@Serializable
data class GraphLane(val branch: String? = null, val lane: Int, val fromRow: Int, val toRow: Int)

@Serializable
data class Graph(val rows: List<GraphRow>, val lanes: List<GraphLane>, val edges: List<GraphEdge>, val width: Int)

fun Session.graph(): Graph {
    val rows = mutableListOf<GraphRow>()
    for (e in entries) {
        val kind = when {
            e is ChatMessage -> if (e.human == true) "me" else "ai"
            e is SystemLine && e.back != null -> "back"
            e is Reflection && e.text.isNotEmpty() -> "reflect"
            else -> null
        } ?: continue
        val who = when {
            e is ChatMessage -> if (e.human == true) "你" else e.name
            kind == "back" -> "带回"
            else -> "小结"
        }
        rows += GraphRow(e.id, rows.size, 0, e.branch, kind, who, e.text)
    }
    val rowOf = rows.associate { it.id to it.index }
    val laneOf = HashMap<String, Int>()
    val taken = mutableListOf<Triple<Int, Int, Int>>() // 轨道、起始行、结束行
    val lanes = mutableListOf<GraphLane>()
    val edges = mutableListOf<GraphEdge>()
    val trunk = rows.filter { it.branch == null }
    if (trunk.isNotEmpty()) lanes += GraphLane(null, 0, trunk.first().index, trunk.last().index)
    // 父分支先分配（按创建顺序，父分支一定比子分支早）
    for (b in branches.orEmpty().sortedBy { it.created }) {
        val own = rows.filter { it.branch == b.id }
        val backs = rows.filter { r -> r.kind == "back" && entries.any { it.id == r.id && it is SystemLine && it.back == b.id } }
        val start = rowOf[b.from] ?: own.firstOrNull()?.index ?: 0
        val end = maxOf(start, own.lastOrNull()?.index ?: start, backs.lastOrNull()?.index ?: start)
        var lane = 1
        while (taken.any { (l, s, e) -> l == lane && s <= end && start <= e }) lane++
        taken += Triple(lane, start, end)
        laneOf[b.id] = lane
        for (r in own) r.lane = lane
        val parentLane = b.parent?.let { laneOf[it] } ?: 0
        if (own.isNotEmpty()) {
            lanes += GraphLane(b.id, lane, own.first().index, own.last().index)
            edges += GraphEdge("fork", b.id, start, parentLane, own.first().index, lane)
        }
        // 带回：从分支在那之前的最后一行，连回上一层带回的那一行
        for (back in backs) {
            val before = own.lastOrNull { it.index < back.index } ?: continue
            edges += GraphEdge("merge", b.id, before.index, lane, back.index, parentLane)
        }
    }
    val width = (listOf(0) + rows.map { it.lane } + lanes.map { it.lane }).max() + 1
    return Graph(rows, lanes, edges, width)
}
