package debating.ground.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

// 全局的按下效果（替换 Material 的波纹）：按下时整块轻轻缩小，松手弹回。
// Material 的波纹不知道控件的形状，圆角卡片、胶囊、圆按钮上会画出方的一块溢出去；缩放跟着控件自己的形状走，
// 而且和主按钮、次按钮原来的按下手感一致（iOS 的按钮也是这样）。只缩画面、不改布局，周围的东西不会被挤动。
// [motion] 为 false（系统移除动画）时不缩，什么也不画。

data class PressIndication(val motion: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, motion)
}

private class PressNode(private val source: InteractionSource, private val motion: Boolean) :
    Modifier.Node(),
    DrawModifierNode {
    private val scale = Animatable(1f)

    override fun onAttach() {
        if (!motion) return
        coroutineScope.launch {
            var pressed = 0
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = maxOf(0, pressed - 1)
                }
                // 按下快、松手带一点弹性；连点时上一段动画直接被接管
                launch { scale.animateTo(if (pressed > 0) PRESSED else 1f, spring(dampingRatio = 0.55f, stiffness = if (pressed > 0) 1400f else 500f)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = scale.value
        if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
    }
}

private const val PRESSED = 0.96f
