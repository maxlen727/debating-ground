package debating.ground.app

import debating.ground.app.ui.Nav
import debating.ground.app.ui.navKind
import org.junit.Assert.assertEquals
import org.junit.Test

// 换页的方向：和网页版 src/lib/motion.ts 的 navKind 一致
class MotionTest {
    @Test
    fun goingDeeperIsForwardAndBackIsBack() {
        assertEquals(Nav.Forward, navKind("learn", "prepare", false))
        assertEquals(Nav.Forward, navKind("prepare", "session", false))
        assertEquals(Nav.Forward, navKind("session", "review", false))
        assertEquals(Nav.Back, navKind("session", "prepare", false))
        assertEquals(Nav.Back, navKind("review", "session", false))
    }

    @Test
    fun startingStraightFromLearnIsForwardButTheTabBarSlidesSideways() {
        // 学习页“一句话开始”直接进讨论：往里走
        assertEquals(Nav.Forward, navKind("learn", "session", false))
        // 点底部导航从“学习”到“讨论”：按标签顺序往左滑
        assertEquals(Nav.Left, navKind("learn", "session", true))
        assertEquals(Nav.Right, navKind("session", "learn", true))
        // 同一层的标签之间
        assertEquals(Nav.Left, navKind("learn", "library", false))
        assertEquals(Nav.Right, navKind("settings", "library", false))
    }

    @Test
    fun cardsGrowOutOfTheListAndShrinkBack() {
        assertEquals(Nav.Expand, navKind("library", "card", false))
        assertEquals(Nav.Collapse, navKind("card", "library", false))
    }
}
