package debating.ground.app.ui

// 主导航的四个标签，和网页版底部导航一致（src/components/ui.tsx 的 TabBar）
enum class Tab(val label: String, val icon: String, val screen: String) {
    Learn("学习", IconPaths.LEARN, "learn"),
    Discuss("讨论", IconPaths.CHAT, "session"),
    Library("知识库", IconPaths.LIBRARY, "library"),
    Settings("设置", IconPaths.SETTINGS, "settings"),
}

/** 哪个标签算“当前”：卡片属于知识库，复盘属于讨论（和网页版 TabBar 的 active 一致）。 */
fun activeTab(screen: String): Tab? = when (screen) {
    "learn" -> Tab.Learn
    "session", "review" -> Tab.Discuss
    "library", "card" -> Tab.Library
    "settings" -> Tab.Settings
    else -> null
}

/** 沉浸页占满整屏，不显示导航（和网页版 App.tsx 的 immersive 一致）。 */
fun immersive(screen: String) = screen in setOf("session", "prepare", "card", "join")

enum class NavStyle { BottomBar, Rail }

// 什么时候用左侧导航：和网页版“响应式布局”一节相同——宽度 ≥ 840（平板、电脑），
// 或者横着拿的手机（高度 ≤ 500）。其余用底部导航，主要操作单手可达。
fun navStyleFor(widthDp: Int, heightDp: Int): NavStyle = if (widthDp >= 840 || (widthDp > heightDp && heightDp <= 500)) NavStyle.Rail else NavStyle.BottomBar
