package debating.ground.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.color

// 悬浮的东西（底部导航胶囊、底部操作、讨论页的输入栏）：不垫满宽的底色，自己浮在页面上，
// 页面内容可以滚到它们下面。质感是“半透明磨砂”：半透明的卡片色、一圈细边、很淡的投影，背景氛围透得出来。
// Compose 没有“模糊身后内容”的能力（不引入第三方库），所以几乎不透明（97%），滚到下面的文字不会透出来和按钮上的字叠在一起。

/** 磨砂底：半透明卡片色 + 细边 + 淡投影。 */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 8.dp): Modifier {
    val t = LocalTokens.current
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
        .background(t.surface.color().copy(alpha = 0.97f), shape)
        .border(1.dp, t.line.color().copy(alpha = 0.7f), shape)
}

/**
 * 内容滚到悬浮控件附近时淡出（只改内容自己的透明度，不铺底色，背景氛围照样透出来）。
 * [height] 是底部悬浮控件占掉的高度：从它上方 64dp 开始变淡，离控件还有几 dp 时就完全看不见了，
 * 文字不会半透明地贴着按钮、再被按钮硬生生截断（#170）。
 */
fun Modifier.fadeBottom(height: Dp): Modifier = if (height <= 0.dp) {
    this
} else {
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = height.toPx()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = size.height - h - 64.dp.toPx(),
                    endY = size.height - h - 2.dp.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/** 底部导航胶囊占掉的高度（含上下空隙）。没有胶囊时是 0。页面滚动内容的末尾、悬浮的底部操作都要让开它。 */
val LocalNavClearance = compositionLocalOf { 0.dp }

/** 在悬浮的底部操作里：按钮自己带投影、文字链接带磨砂底，免得和滚到下面的内容混在一起。 */
val LocalFloating = compositionLocalOf { false }

/** 滚动内容的末尾留出空白，最后一行能完全滚到胶囊上面。 */
@Composable
fun NavSpacer(extra: Dp = 0.dp) = Spacer(Modifier.height(LocalNavClearance.current + extra))

/**
 * 页面 + 悬浮在底部的操作。[content] 拿到底部要留的空白（操作区的高度加导航胶囊），放在滚动内容的末尾。
 * 操作区量出自己的高度再告诉页面，所以按钮多一行、少一行都不会挡住内容。
 */
@Composable
fun WithFloatingDock(dock: @Composable ColumnScope.() -> Unit, content: @Composable (bottom: Dp) -> Unit) {
    val density = LocalDensity.current
    val nav = LocalNavClearance.current
    var height by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fadeBottom(height + nav)) { content(height + nav) }
        CompositionLocalProvider(LocalFloating provides true) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(bottom = nav)
                    .onSizeChanged { height = with(density) { it.height.toDp() } }
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { dock() }
        }
    }
}

/**
 * 能滚动，并且滚动后内容在顶上淡出（iOS 的“滚动边缘效果”）。没滚动时不淡，第一行完整显示；
 * 滚得越多越淡，最多是顶上 20dp 从透明渐到不透明。读滚动位置放在绘制阶段，滚动时不重组。
 * 页面底下有导航胶囊时，底部也按 [fadeBottom] 淡出。
 */
@Composable
fun Modifier.scrollWithEdge(): Modifier {
    val scroll = rememberScrollState()
    // 底下有悬浮的导航胶囊时，内容快滚到它那里就淡出。只淡滚动的内容，悬浮的按钮层不受影响
    val nav = LocalNavClearance.current
    return this
        .fadeBottom(nav)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = 20.dp.toPx()
            val k = (scroll.value / h).coerceIn(0f, 1f)
            if (k > 0f) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 1f - k), 1f to Color.Black, startY = 0f, endY = h),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
        .verticalScroll(scroll)
}

/** 只有图标的按钮：圆形磨砂（返回、导出、关闭……）。可点区域 44dp。 */
@Composable
fun GlassIconButton(path: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val t = LocalTokens.current
    Box(
        Modifier
            .size(44.dp)
            .glass(CircleShape, 4.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(lineIcon(path, path), null, Modifier.size(20.dp), tint = t.ink.color()) }
}

/** 上下都有悬浮控件时（讨论页）：内容滚到顶上的栏和底下的输入栏附近都淡出。规则和 [fadeBottom] 一样，上下对称。 */
fun Modifier.fadeUnder(top: Dp, bottom: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val tp = top.toPx()
        if (tp > 0f) {
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = tp - 4.dp.toPx(), endY = tp + 40.dp.toPx()),
                blendMode = BlendMode.DstIn,
            )
        }
        val bp = bottom.toPx()
        if (bp > 0f) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = size.height - bp - 48.dp.toPx(),
                    endY = size.height - bp + 4.dp.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * 横向滚动的一排（例子、工具、要点进度……）：还能往哪边滚，哪边就渐隐一小段，而不是被屏幕边缘硬切断。
 * 读滚动位置放在绘制阶段，滚动时不重组。
 */
fun Modifier.fadeEdges(canBack: () -> Boolean, canForward: () -> Boolean, width: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (canBack()) drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = w), blendMode = BlendMode.DstIn)
        if (canForward()) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - w, endX = size.width),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** 能横向滚动、两端按需渐隐的一排。 */
@Composable
fun Modifier.scrollRow(): Modifier {
    val state = rememberScrollState()
    return fadeEdges({ state.canScrollBackward }, { state.canScrollForward }).horizontalScroll(state)
}
