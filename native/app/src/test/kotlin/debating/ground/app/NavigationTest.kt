package debating.ground.app

import debating.ground.app.ui.NavStyle
import debating.ground.app.ui.Tab
import debating.ground.app.ui.backTarget
import debating.ground.app.ui.navStyleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    @Test fun phonesUseTheBottomBar() {
        assertEquals(NavStyle.BottomBar, navStyleFor(390, 844))
        assertEquals(NavStyle.BottomBar, navStyleFor(320, 568))   // 最窄
        assertEquals(NavStyle.BottomBar, navStyleFor(600, 960))   // 折叠屏、小平板竖着拿
    }

    @Test fun tabletsAndLandscapePhonesUseTheRail() {
        assertEquals(NavStyle.Rail, navStyleFor(840, 1200))       // 平板
        assertEquals(NavStyle.Rail, navStyleFor(1280, 800))       // 电脑
        assertEquals(NavStyle.Rail, navStyleFor(844, 390))        // 横着拿的手机
        assertEquals(NavStyle.Rail, navStyleFor(700, 400))
    }

    @Test fun backReturnsToLearnFirstThenLeaves() {
        assertEquals(Tab.Learn, backTarget(Tab.Library))
        assertEquals(Tab.Learn, backTarget(Tab.Settings))
        assertNull(backTarget(Tab.Learn))
    }

    @Test fun tabsMatchTheWebApp() {
        assertEquals(listOf("学习", "讨论", "知识库", "设置"), Tab.entries.map { it.label })
    }
}
