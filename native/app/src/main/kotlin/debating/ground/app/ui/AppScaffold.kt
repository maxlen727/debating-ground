package debating.ground.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import debating.ground.app.AppModel
import debating.ground.app.flow.AppState
import debating.ground.app.ui.screens.CardScreen
import debating.ground.app.ui.screens.DiscussScreen
import debating.ground.app.ui.screens.JoinScreen
import debating.ground.app.ui.screens.LearnScreen
import debating.ground.app.ui.screens.LibraryScreen
import debating.ground.app.ui.screens.MaterialScreen
import debating.ground.app.ui.screens.PrepareScreen
import debating.ground.app.ui.screens.ReviewScreen
import debating.ground.app.ui.screens.SettingsScreen
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AppScaffold(model: AppModel) {
    val st by model.state.collectAsState()
    val l = model.learning
    val screen = st.ui.screen
    // 返回键：先关面板、再回上一页（流程层的 goBack）；已经在最外层就交给系统退出
    // 返回预览（Android 14 起）：手指一滑，面板跟着往下沉、页面跟着缩小，松手才真的返回，反悔就弹回。
    // 最外层（学习页）交给系统，退出 App 时用系统自己的预览
    val gesture = remember { BackGesture() }
    val motionOn = LocalMotion.current
    val overlay = st.ui.sheet != null || st.ui.term != null || st.ui.explain != null || st.ui.invite
    PredictiveBackHandler(enabled = screen != "learn" || overlay) { events ->
        gesture.overlay = l.state.ui.let { it.sheet != null || it.term != null || it.explain != null || it.invite }
        try {
            events.collect { e ->
                if (motionOn) {
                    gesture.progress = e.progress
                    gesture.fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                }
            }
            l.goBack()
            // 松手：返回的换页动画接着播，跟手的缩放同时收回去；面板等它沉下去再清零
            if (gesture.overlay) delay(320) else animate(gesture.progress, 0f, animationSpec = tween(300, easing = EaseOut)) { v, _ -> gesture.progress = v }
            gesture.progress = 0f
        } catch (e: CancellationException) {
            // 反悔：弹回原位
            withContext(NonCancellable) { animate(gesture.progress, 0f, animationSpec = tween(220, easing = EaseOut)) { v, _ -> gesture.progress = v } }
            throw e
        }
    }
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
        CompositionLocalProvider(LocalBackGesture provides gesture) {
            Box(Modifier.fillMaxSize().followBack(gesture, LocalTokens.current.surface.color())) {
                PageHost(screen, st, nav = {
                    val kind = navKind(initialState, targetState, viaTab[0])
                    viaTab[0] = false
                    pageTransform(kind, motion)
                }) { scr, state -> Page(scr, state, model) }
            }
        }
    }
    // 今天要复习几张：写在“知识库”标签上
    val onTab = { tab: Tab ->
        viaTab[0] = true
        // 讨论结束且已有复盘时，“讨论”标签打开复盘页
        val s = st.session
        l.go(if (tab == Tab.Discuss && s?.finished == true && s.review != null) "review" else tab.screen)
    }
    // 点输入框以外的空白处、换页时收起键盘（Compose 默认不处理，键盘会一直开着挡住按钮）
    val focus = LocalFocusManager.current
    LaunchedEffect(screen) { focus.clearFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }) {
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
    "library" -> LibraryScreen(st, model)
    "card" -> CardScreen(st, model)
    "settings" -> SettingsScreen(st, model)
    "join" -> JoinScreen(st, model)
    "material" -> MaterialScreen(st, model)
    else -> LearnScreen(st, model)
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

// 标签图标；知识库的右上角写着今天要复习几张（和网页版 .badge-dot 一样：强调色底、白字）
@Composable
private fun TabIcon(tab: Tab, modifier: Modifier, tint: Color?, description: String?) {
    val icon = lineIcon(tab.name, tab.icon)
    if (tint != null) Icon(icon, description, modifier, tint = tint) else Icon(icon, description, modifier)
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
                TabIcon(tab, Modifier.size(22.dp), fg, if (collapsed) "${tab.label}，展开导航" else null)
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
                icon = { TabIcon(tab, Modifier, null, null) },
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
