package debating.ground.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color
import kotlinx.coroutines.launch

// 底部操作面板（网页版的 Sheet、.sheet）。参考 iOS 的面板规范：
// - 悬浮的卡片：四边留出空隙、四角都是圆角，不贴着屏幕边缘；
// - 内容多时先停在半高，往上拖到全高；往下拖（或在内容顶端继续往下滑）关掉，半途松手会弹回去；
// - 点外面或按返回键也能关。宽屏（≥ 840dp）时是居中的对话框。返回键由 AppScaffold 交给流程层的 goBack() 统一处理。
@Composable
fun Sheet(title: String, onClose: () -> Unit, titleStyle: TextStyle? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(280, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) }
    // 关上时：托管它的 SheetHost 等它沉下去、遮罩淡出，再移除（没有托管时直接消失）
    val host = LocalSheetScope.current
    fun leaving(exit: ExitTransition) = host?.run { Modifier.animateEnterExit(EnterTransition.None, if (motion) exit else ExitTransition.None) } ?: Modifier
    // 往下拖了多少（像素）：跟着手指走，松手后弹回或关掉
    val drag = remember { Animatable(0f) }
    var expanded by remember { mutableStateOf(false) }
    var contentHeight by remember { mutableStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val half = maxHeight * 0.55f
        val full = maxHeight - 24.dp
        // 内容比半高还多时才分两档（和 iOS 一样，可调高度的面板才显示拖动条）
        val canExpand = !wide && with(density) { contentHeight.toDp() } > half - 72.dp
        val limit by animateDpAsState(
            if (wide) {
                maxHeight * 0.86f
            } else if (expanded) {
                full
            } else {
                half
            },
            tween(if (motion) 300 else 0),
            label = "sheet-height",
        )
        val dismissAt = with(density) { 120.dp.toPx() }
        fun settle(velocity: Float) {
            scope.launch {
                val d = drag.value
                when {
                    // 往上甩或拖：半高变全高
                    (d < -dismissAt / 2 || velocity < -1200f) && canExpand && !expanded -> {
                        expanded = true
                        drag.animateTo(0f)
                    }

                    // 往下甩或拖：全高先回到半高，半高就关掉
                    d > dismissAt || velocity > 1200f -> if (expanded) {
                        expanded = false
                        drag.animateTo(0f)
                    } else {
                        onClose()
                    }

                    else -> drag.animateTo(0f, spring())
                }
            }
        }
        val dragState = rememberDraggableState { delta ->
            scope.launch {
                drag.snapTo(
                    (drag.value + delta).coerceAtLeast(
                        if (canExpand &&
                            !expanded
                        ) {
                            -dismissAt
                        } else {
                            0f
                        },
                    ),
                )
            }
        }
        // 内容已经滚到最上面还继续往下拉：拉的是面板本身
        val nested = remember(canExpand, expanded) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // 面板被拉下来一点时，先把它推回去，再滚内容
                    if (available.y < 0 && drag.value > 0f) {
                        val used = maxOf(available.y, -drag.value)
                        scope.launch { drag.snapTo(drag.value + used) }
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        scope.launch { drag.snapTo(drag.value + available.y) }
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag.value != 0f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(leaving(fadeOut(tween(240))))
                .graphicsLayer { alpha = rise.value * (1f - (drag.value / (dismissAt * 3)).coerceIn(0f, 0.6f)) }
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        )
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .align(if (wide) Alignment.Center else Alignment.BottomCenter)
                .then(
                    leaving(
                        if (wide) {
                            fadeOut(tween(180)) + scaleOut(tween(220), 0.96f)
                        } else {
                            slideOutVertically(tween(260, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f))) { it } + fadeOut(tween(200, 60))
                        },
                    ),
                )
                .padding(if (wide) 0.dp else 8.dp)
                .widthIn(max = if (wide) 560.dp else 520.dp)
                .fillMaxWidth()
                .heightIn(max = limit)
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1 - rise.value) * 40.dp.toPx() + drag.value.coerceAtLeast(0f)
                }
                .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.2f))
                .background(t.surface.color(), shape)
                .clip(shape)
                // 吃掉面板上的点击，免得穿到下面的遮罩把面板关掉
                .clickable(remember { MutableInteractionSource() }, null, onClick = {})
                .semantics { paneTitle = title },
        ) {
            // 拖动条和标题：按住能拖（往上展开、往下关掉），点拖动条在两档之间切换
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(dragState, Orientation.Vertical, enabled = !wide, onDragStopped = { v -> settle(v) })
                    .padding(start = 18.dp, end = 10.dp, top = 8.dp),
            ) {
                if (!wide) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 44.dp, height = 20.dp)
                            .clickable(enabled = canExpand, role = Role.Button, onClickLabel = if (expanded) "收起到半高" else "展开到全高") { expanded = !expanded },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(36.dp, 5.dp).background(t.line.color(), RoundedCornerShape(3.dp)))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = titleStyle ?: TextStyles.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = t.ink.color(),
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    GlassIconButton(IconPaths.CLOSE, "关闭", onClose)
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .nestedScroll(nested)
                    .verticalScroll(rememberScrollState())
                    .onSizeChanged { contentHeight = it.height }
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

/** 引用的那句话（.quote）：左边一道线，衬线小字。 */
@Composable
fun Quote(text: String) {
    val t = LocalTokens.current
    val bar = t.line.color()
    Text(
        text,
        style = TextStyles.knowledge.copy(fontSize = 14.sp, lineHeight = 1.8.em()),
        color = t.muted.color(),
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg.color(), RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
            .drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    )
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
