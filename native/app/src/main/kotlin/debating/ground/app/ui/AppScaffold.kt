package debating.ground.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import debating.ground.app.ui.screens.DiscussScreen
import debating.ground.app.ui.screens.LearnScreen
import debating.ground.app.ui.screens.LibraryScreen
import debating.ground.app.ui.screens.SettingsScreen
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

@Composable
fun AppScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Learn) }
    backTarget(tab)?.let { target -> BackHandler { tab = target } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 整个窗口底下铺一层背景氛围，页面和导航都是透明的，让它透出来
        Atmosphere()
        val style = navStyleFor(maxWidth.value.toInt(), maxHeight.value.toInt())
        if (style == NavStyle.Rail) {
            Row(Modifier.fillMaxSize()) {
                Rail(tab) { tab = it }
                // 内容铺满导航右边的整块区域，不留两侧空白（和网页版宽屏时一样）。
                // 左侧导航自己避开系统栏；内容这边避开状态栏、底部手势条和横屏时的刘海
                Box(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                ) { Page(tab) }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { BottomBar(tab) { tab = it } },
            ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { Page(tab) } }
        }
    }
}

@Composable
private fun Page(tab: Tab) = when (tab) {
    Tab.Learn -> LearnScreen()
    Tab.Discuss -> DiscussScreen()
    Tab.Library -> LibraryScreen()
    Tab.Settings -> SettingsScreen()
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
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
private fun Rail(current: Tab, onSelect: (Tab) -> Unit) {
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
