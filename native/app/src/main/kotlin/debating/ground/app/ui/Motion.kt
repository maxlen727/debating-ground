package debating.ground.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// 页面和面板的连贯动效：从哪里来，回哪里去。照网页版 src/lib/motion.ts 和 styles.css 的“连贯动效”一节：
// 往里走（学习 → 准备 → 讨论 → 复盘）新页从右边滑进来、旧页往左退半步；返回反过来；
// 底部导航切标签按标签顺序左右滑；知识卡片从列表里放大成整页，返回时缩回去。

/** 页面的层级：越深越“往里走”（和网页版 DEPTH 一致）。 */
private val DEPTH = mapOf("learn" to 0, "library" to 0, "settings" to 0, "prepare" to 1, "card" to 1, "join" to 1, "session" to 2, "review" to 3)

enum class Nav { Forward, Back, Left, Right, Expand, Collapse }

/** 从 from 到 to 是哪一种导航。viaTab：是不是点底部导航切过来的（从学习页“一句话开始”进讨论是往里走，不是切标签）。 */
fun navKind(from: String, to: String, viaTab: Boolean): Nav {
    if (from == "library" && to == "card") return Nav.Expand
    if (from == "card" && to == "library") return Nav.Collapse
    val a = DEPTH[from] ?: 0
    val b = DEPTH[to] ?: 0
    val ta = activeTab(from)?.ordinal
    val tb = activeTab(to)?.ordinal
    if ((viaTab || a == b) && ta != null && tb != null && ta != tb) return if (tb > ta) Nav.Left else Nav.Right
    return if (b >= a) Nav.Forward else Nav.Back
}

/** 和网页版 --vt-ease 一样：起步快、收尾很慢。 */
val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private const val PAGE_MS = 480

private fun <T> ease(ms: Int, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, EaseOut)

/** 换页的动画（网页版的 vt-page-*、vt-tab-*、vt-recede/return）。 */
fun pageTransform(kind: Nav, motion: Boolean): ContentTransform {
    if (!motion) return EnterTransition.None togetherWith ExitTransition.None
    val tab = (PAGE_MS * 0.8).toInt()
    val pair = when (kind) {
        Nav.Forward ->
            (slideInHorizontally(ease<IntOffset>(PAGE_MS, 40)) { (it * 0.28f).toInt() } + fadeIn(ease(PAGE_MS * 6 / 10, 40))) togetherWith
                (slideOutHorizontally(ease<IntOffset>(PAGE_MS)) { -(it * 0.18f).toInt() } + scaleOut(ease(PAGE_MS), 0.97f) + fadeOut(ease(PAGE_MS * 6 / 10)))

        Nav.Back ->
            (
                slideInHorizontally(ease<IntOffset>(PAGE_MS)) {
                    -(it * 0.18f).toInt()
                } + scaleIn(ease(PAGE_MS), 0.97f) + fadeIn(ease(PAGE_MS * 6 / 10))
                ) togetherWith
                (slideOutHorizontally(ease<IntOffset>(PAGE_MS * 85 / 100)) { (it * 0.28f).toInt() } + fadeOut(ease(PAGE_MS / 2)))

        Nav.Left ->
            (slideInHorizontally(ease<IntOffset>(tab)) { (it * 0.08f).toInt() } + fadeIn(ease(tab))) togetherWith
                (slideOutHorizontally(ease<IntOffset>(tab)) { -(it * 0.08f).toInt() } + fadeOut(ease(tab / 2)))

        Nav.Right ->
            (slideInHorizontally(ease<IntOffset>(tab)) { -(it * 0.08f).toInt() } + fadeIn(ease(tab))) togetherWith
                (slideOutHorizontally(ease<IntOffset>(tab)) { (it * 0.08f).toInt() } + fadeOut(ease(tab / 2)))

        Nav.Expand ->
            (scaleIn(ease(PAGE_MS), 0.92f) + fadeIn(ease(PAGE_MS / 2, 80))) togetherWith
                (scaleOut(ease(PAGE_MS), 0.94f) + fadeOut(ease(PAGE_MS / 2)))

        Nav.Collapse ->
            (scaleIn(ease(PAGE_MS), 0.94f) + fadeIn(ease(PAGE_MS / 2))) togetherWith
                (scaleOut(ease(PAGE_MS * 7 / 10), 0.92f) + fadeOut(ease(PAGE_MS / 3)))
    }
    // 往回走时旧页盖在新页上面滑走
    val z = if (kind == Nav.Back || kind == Nav.Collapse) -1f else 1f
    return ContentTransform(pair.targetContentEnter, pair.initialContentExit, z, SizeTransform(clip = false))
}

/**
 * 出现时展开、淡入，消失时收起、淡出（错误提示、忙碌条、提示卡、短暂提示）。
 * 收起的过程中还显示最后一次的内容，不会先变成空白再收起。
 */
@Composable
fun <T : Any> Appear(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val motion = LocalMotion.current
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    AnimatedVisibility(
        value != null,
        modifier,
        enter = if (motion) expandVertically(ease(280)) + fadeIn(ease(280, 60)) else EnterTransition.None,
        exit = if (motion) shrinkVertically(tween(220, easing = EaseIn)) + fadeOut(tween(160)) else ExitTransition.None,
    ) {
        @Suppress("UNCHECKED_CAST")
        (last[0] as T?)?.let { content(it) }
    }
}

/** 正在显示的面板：key 区分是哪一个面板，content 画它（捕获打开时的数据，关上的过程中照样画得出来）。 */
class SheetSpec(val key: String, val content: @Composable () -> Unit)

/** 面板所在的动画范围：Sheet 用它决定自己怎么沉下去（没有托管时只做升起）。 */
val LocalSheetScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** 托管页面上的面板：换成别的面板或关掉时，旧面板沉下去、遮罩淡出，再移除。 */
@Composable
fun SheetHost(spec: SheetSpec?) {
    AnimatedContent(
        spec,
        Modifier.fillMaxSize(),
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using null },
        contentKey = { it?.key },
        label = "sheet",
    ) { target ->
        if (target != null) CompositionLocalProvider(LocalSheetScope provides this) { target.content() }
    }
}

/** 换页：旧页面带着离开那一刻的样子退场（网页版是浏览器拍下前后两帧）。 */
@Composable
fun <S> PageHost(screen: String, state: S, nav: AnimatedContentTransitionScope<String>.() -> ContentTransform, content: @Composable (String, S) -> Unit) {
    val frozen = remember { HashMap<String, S>() }
    frozen[screen] = state
    AnimatedContent(screen, Modifier.fillMaxSize(), transitionSpec = nav, label = "page") { s ->
        content(s, if (s == screen) state else frozen[s] ?: state)
    }
}

/**
 * 讨论里新来的一条：淡入、从下面轻轻浮上来（网页版的 .ink）。seen 记着已经出现过的条目，
 * 打开讨论时已经在的、翻回去重新进入屏幕的，都不再重播。
 */
@Composable
fun RiseIn(id: String, seen: MutableSet<String>, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val fresh = remember(id) { seen.add(id) && motion }
    val p = remember(id) { Animatable(if (fresh) 0f else 1f) }
    if (fresh) LaunchedEffect(id) { p.animateTo(1f, tween(380, easing = EaseOut)) }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1 - p.value) * 14.dp.toPx()
        },
    ) { content() }
}
