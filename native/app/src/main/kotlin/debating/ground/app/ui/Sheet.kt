package debating.ground.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 底部操作面板（网页版的 Sheet、.sheet）：从底下升起，点外面或按返回键关掉。
// 宽屏（≥ 840dp）时和网页版一样变成居中的对话框。返回键由 AppScaffold 交给流程层的 goBack() 统一处理。
@Composable
fun Sheet(title: String, onClose: () -> Unit, titleStyle: TextStyle? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val motion = LocalMotion.current
    val rise = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(250, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = rise.value }
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        )
        val shape = if (wide) RoundedCornerShape(20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        Column(
            Modifier
                .align(if (wide) Alignment.Center else Alignment.BottomCenter)
                .widthIn(max = if (wide) 560.dp else 520.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.8f)
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1 - rise.value) * 40.dp.toPx()
                }
                .background(t.surface.color(), shape)
                // 吃掉面板上的点击，免得穿到下面的遮罩把面板关掉
                .clickable(remember { MutableInteractionSource() }, null, onClick = {})
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!wide) Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(t.line.color(), RoundedCornerShape(2.dp)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = titleStyle ?: TextStyles.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = t.ink.color(),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(lineIcon("close", IconPaths.CLOSE), "关闭", Modifier.size(20.dp), tint = t.muted.color())
                }
            }
            content()
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
