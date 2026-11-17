package debating.ground.app.data

// 知识画布：讨论是一棵树。所有发言仍按时间存在一个列表里，每条带着自己所在的分支；
// 这里按分支把它们取出来。和网页版 src/lib/canvas.ts 一致（M1 先移植导出要用的部分）

/** 只属于某一层的发言（主干传 null）。 */
fun Session.levelEntries(at: String?): List<Entry> = entries.filter { it.branch == at }

/** 从某一层直接展开的分支。 */
fun Session.childrenOf(parent: String?): List<Branch> = branches.orEmpty().filter { it.parent == parent }
