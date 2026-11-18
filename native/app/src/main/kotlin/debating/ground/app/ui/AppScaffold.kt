package debating.ground.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val onTab = { tab: Tab ->
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
                ) { Page(st, model) }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { if (showNav) BottomBar(activeTab(screen), onTab) },
                contentWindowInsets = WindowInsets.safeDrawing,
            ) { padding -> Box(Modifier.fillMaxSize().padding(padding).imePadding()) { Page(st, model) } }
        }
        Toast(st.ui.toast)
    }
}

@Composable
private fun Page(st: AppState, model: AppModel) = when (st.ui.screen) {
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
    if (text.isEmpty()) return
    val t = LocalTokens.current
    Text(
        text,
        color = t.page.color(),
        style = TextStyles.small,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
            .background(t.ink.color().copy(alpha = 0.88f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun BottomBar(current: Tab?, onSelect: (Tab) -> Unit) {
    val t = LocalTokens.current
    NavigationBar(containerColor = Color.Transparent) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = { Icon(lineIcon(tab.name, tab.icon), contentDescription = null) },
                label = { Text(tab.label, style = TextStyles.label) },
                colors = NavigationBarItemDefaults.colors(
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
