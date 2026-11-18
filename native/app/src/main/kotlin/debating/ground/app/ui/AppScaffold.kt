package debating.ground.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import debating.ground.app.AppModel
import debating.ground.app.flow.AppState
import debating.ground.app.ui.screens.CardScreen
import debating.ground.app.ui.screens.DiscussScreen
import debating.ground.app.ui.screens.LearnScreen
import debating.ground.app.ui.screens.LibraryScreen
import debating.ground.app.ui.screens.PrepareScreen
import debating.ground.app.ui.screens.ReviewScreen
import debating.ground.app.ui.screens.SettingsScreen
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import kotlinx.coroutines.delay

@Composable
fun AppScaffold(model: AppModel) {
    val st by model.state.collectAsState()
    val l = model.learning
    val screen = st.ui.screen
    // 返回键：先关面板、再回上一页（流程层的 goBack）；已经在最外层就交给系统退出
    BackHandler(enabled = screen != "learn" || st.ui.sheet != null || st.ui.term != null || st.ui.explain != null) { l.goBack() }
    // 短暂提示 3 秒后消失（和网页版 toast 一样）
    LaunchedEffect(st.ui.toast) {
        if (st.ui.toast.isNotEmpty()) {
            delay(3000)
            l.clearToast()
        }
    }
    // 这次换页是不是点底部导航引起的：决定左右滑还是前进后退
    val viaTab = remember { booleanArrayOf(false) }
    val motion = LocalMotion.current
    val pages: @Composable () -> Unit = {
        PageHost(screen, st, nav = {
            val kind = navKind(initialState, targetState, viaTab[0])
            viaTab[0] = false
            pageTransform(kind, motion)
        }) { scr, state -> Page(scr, state, model) }
    }
    val onTab = { tab: Tab ->
        viaTab[0] = true
        // 讨论结束且已有复盘时，“讨论”标签打开复盘页
        val s = st.session
        l.go(if (tab == Tab.Discuss && s?.finished == true && s.review != null) "review" else tab.screen)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 整个窗口底下铺一层背景氛围，页面和导航都是透明的，让它透出来
        Atmosphere()
        val style = navStyleFor(maxWidth.value.toInt(), maxHeight.value.toInt())
        val showNav = !immersive(screen)
        if (style == NavStyle.Rail) {
            Row(Modifier.fillMaxSize()) {
                if (showNav) Rail(activeTab(screen), onTab)
                // 内容铺满导航右边的整块区域，不留两侧空白（和网页版宽屏时一样）。
                // 左侧导航自己避开系统栏；内容这边避开状态栏、底部手势条和横屏时的刘海
                Box(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(
                            if (showNav) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End) else WindowInsets.safeDrawing,
                        )
                        .imePadding(),
                ) { pages() }
            }
        } else {
            // 手机竖屏：底部导航是悬浮的胶囊，页面铺满整屏、可以滚到它下面。打字时键盘顶上来，胶囊先收起
            val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val floatingNav = showNav && !ime
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                // 往下滚时胶囊收起成只有当前标签的小圆，往回滚、滚到顶或点一下再展开（参考 iOS 的标签栏）。
                // 收起时留白不变，页面不会跟着跳
                var collapsed by remember { mutableStateOf(false) }
                LaunchedEffect(screen) { collapsed = false }
                val watchScroll = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (consumed.y < -2f) collapsed = true
                            if (consumed.y > 2f || available.y > 0f) collapsed = false
                            return Offset.Zero
                        }
                    }
                }
                CompositionLocalProvider(LocalNavClearance provides if (floatingNav) NAV_CLEARANCE else 0.dp) {
                    // 淡出交给页面里的滚动内容（scrollWithEdge）：整页一起淡会把悬浮的底部按钮也淡掉
                    Box(Modifier.fillMaxSize().nestedScroll(watchScroll)) { pages() }
                }
                // 进出沉浸页（讨论、准备、卡片）、键盘弹起时，胶囊沉下去或升上来
                AnimatedVisibility(
                    floatingNav,
                    Modifier.fillMaxSize(),
                    enter = if (motion) slideInVertically(tween(360, easing = EaseOut)) { it / 6 } + fadeIn(tween(240)) else EnterTransition.None,
                    exit = if (motion) slideOutVertically(tween(240)) { it / 6 } + fadeOut(tween(180)) else ExitTransition.None,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) { FloatingNav(activeTab(screen), collapsed, { collapsed = false }, onTab) }
                }
            }
        }
        Toast(st.ui.toast)
    }
}

@Composable
private fun Page(screen: String, st: AppState, model: AppModel) = when (screen) {
    "prepare" -> PrepareScreen(st, model)
    "session" -> DiscussScreen(st, model)
    "review" -> ReviewScreen(st, model)
    "library" -> LibraryScreen(st, model, System.currentTimeMillis())
    "card" -> CardScreen(st, model)
    "settings" -> SettingsScreen(st, model)
    else -> LearnScreen(st, model, System.currentTimeMillis())
}

// 短暂提示（网页版的 .toast）：浮在底部，不挡操作
@Composable
private fun BoxScope.Toast(text: String) {
    val motion = LocalMotion.current
    val last = remember { arrayOf("") }
    if (text.isNotEmpty()) last[0] = text
    AnimatedVisibility(
        text.isNotEmpty(),
        Modifier.align(Alignment.BottomCenter),
        enter = if (motion) slideInVertically(tween(320, easing = EaseOut)) { it } + fadeIn(tween(200)) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(200)) + slideOutVertically(tween(240)) { it / 2 } else ExitTransition.None,
    ) { ToastText(last[0]) }
}

@Composable
private fun ToastText(text: String) {
    val t = LocalTokens.current
    Text(
        text,
        color = t.page.color(),
        style = TextStyles.small,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
            .background(t.ink.color().copy(alpha = 0.88f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// 胶囊高 64dp，上下各留 12dp
private val NAV_CLEARANCE = 88.dp

// 悬浮的胶囊导航：半透明磨砂，选中的那项亮起一块小胶囊（和外框的圆角同心）。
// 收起时只剩当前标签，缩到左下角；点它展开
@Composable
private fun BoxWithConstraintsScope.FloatingNav(current: Tab?, collapsed: Boolean, onExpand: () -> Unit, onSelect: (Tab) -> Unit) {
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val shape = RoundedCornerShape(32.dp)
    val full = minOf(maxWidth - 32.dp, 440.dp)
    val spec = tween<Dp>(if (motion) 320 else 0, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))
    val width by animateDpAsState(if (collapsed) 64.dp else full, spec, label = "nav-width")
    val x by animateDpAsState(if (collapsed) 16.dp else (maxWidth - full) / 2, spec, label = "nav-x")
    Row(
        Modifier
            .align(Alignment.BottomStart)
            .offset { IntOffset(x.roundToPx(), 0) }
            .padding(bottom = 12.dp)
            .width(width)
            .height(64.dp)
            .glass(shape, 12.dp)
            .clip(shape)
            .selectableGroup()
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.filter { !collapsed || it == current }.forEach { tab ->
            val on = tab == current
            val bg by animateColorAsState(if (on) t.lit.color() else Color.Transparent, label = "tab-bg")
            val fg by animateColorAsState(if (on) t.litInk.color() else t.muted.color(), label = "tab-fg")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(bg)
                    .selectable(on, role = Role.Tab) { if (collapsed) onExpand() else onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(lineIcon(tab.name, tab.icon), contentDescription = if (collapsed) "${tab.label}，展开导航" else null, Modifier.size(22.dp), tint = fg)
                if (!collapsed) {
                    Text(
                        tab.label,
                        maxLines = 1,
                        style = TextStyles.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun Rail(current: Tab?, onSelect: (Tab) -> Unit) {
    val t = LocalTokens.current
    NavigationRail(containerColor = Color.Transparent) {
        Tab.entries.forEach { tab ->
            NavigationRailItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = { Icon(lineIcon(tab.name, tab.icon), contentDescription = null) },
                label = { Text(tab.label, style = TextStyles.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = t.brand.color(),
                    selectedTextColor = t.brand.color(),
                    indicatorColor = t.lit.color(),
                    unselectedIconColor = t.muted.color(),
                    unselectedTextColor = t.muted.color(),
                ),
            )
        }
    }
}
