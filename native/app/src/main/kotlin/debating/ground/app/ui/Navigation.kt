package debating.ground.app.ui

// 主导航的四个标签，和网页版底部导航一致（src/components/ui.tsx 的 TabBar）
enum class Tab(val label: String, val icon: String) {
    Learn("学习", IconPaths.learn),
    Discuss("讨论", IconPaths.chat),
    Library("知识库", IconPaths.library),
    Settings("设置", IconPaths.settings),
}

enum class NavStyle { BottomBar, Rail }

// 什么时候用左侧导航：和网页版“响应式布局”一节相同——宽度 ≥ 840（平板、电脑），
// 或者横着拿的手机（高度 ≤ 500）。其余用底部导航，主要操作单手可达。
fun navStyleFor(widthDp: Int, heightDp: Int): NavStyle =
    if (widthDp >= 840 || (widthDp > heightDp && heightDp <= 500)) NavStyle.Rail else NavStyle.BottomBar

// 返回键：不在“学习”页时先回到学习页，已经在学习页才交给系统（退出 App）
fun backTarget(current: Tab): Tab? = if (current == Tab.Learn) null else Tab.Learn
