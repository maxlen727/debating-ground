package debating.ground.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.TextSize
import debating.ground.app.ui.theme.TextStyles
import debating.ground.app.ui.theme.color

// 通用部件：照网页版 styles.css 的同名样式（.primary、.secondary、.ghost、.chip、.mode、.banner、.dock……）。
// 颜色只用令牌；可点区域至少 44dp（主要按钮 48dp）。

private val R = 14.dp

@Composable
private fun pressScale(source: MutableInteractionSource, enabled: Boolean): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "press").value
}

/** 主按钮（.primary）：深绿底，按下时轻轻缩一点。 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) {
    val t = LocalTokens.current
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = if (small) 40.dp else 48.dp)
            .scale(pressScale(src, enabled))
            .alpha(if (enabled) 1f else 0.45f)
            .background(t.brand.color(), RoundedCornerShape(R))
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = t.brandInk.color(),
            style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold, fontSize = if (small) TextSize.sm else TextSize.md),
        )
    }
}

/** 次按钮（.secondary）：白底描边。 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) {
    val t = LocalTokens.current
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = if (small) 40.dp else 48.dp)
            .scale(pressScale(src, enabled))
            .alpha(if (enabled) 1f else 0.45f)
            .background(t.raised.color(), RoundedCornerShape(R))
            .border(1.dp, t.line.color(), RoundedCornerShape(R))
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.ink.color(), style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold, fontSize = if (small) TextSize.sm else TextSize.md)) }
}

/** 文字按钮（.ghost）：品牌色字，可选图标。[small] 对应 .small。 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false, icon: String? = null) {
    val t = LocalTokens.current
    Row(
        modifier
            .heightIn(min = if (small) 44.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(lineIcon(icon, icon), null, Modifier.size(if (small) 16.dp else 18.dp), tint = t.brand.color())
        Text(text, color = t.brand.color(), style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold, fontSize = if (small) TextSize.sm else TextSize.md))
    }
}

/** 主按钮下面的次要选择（.quiet-link）：不抢主按钮的风头，但要好点。 */
@Composable
fun QuietLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val t = LocalTokens.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.muted.color(), style = TextStyles.small) }
}

/** 小标签（.chip），选中时描品牌色边。 */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .sizeIn(minHeight = 44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (on) t.brand.color() else t.muted.color(),
            style = TextStyles.small.copy(fontSize = 13.sp),
            modifier = Modifier
                .background(if (on) t.brand.color().copy(alpha = 0.08f).compositeOver(t.surface.color()) else t.surface.color(), shape)
                .border(1.dp, if (on) t.brand.color() else t.line.color(), shape)
                .padding(horizontal = 13.dp, vertical = 6.dp),
        )
    }
}

/** 提示条（.banner），[warm] 是暖色那种（今天要复习）。 */
@Composable
fun Banner(title: String, sub: String, warm: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current
    val fg = if (warm) t.accent.color() else t.litInk.color()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (warm) t.accentSoft.color() else t.lit.color(), RoundedCornerShape(R))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, style = TextStyles.body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
            Text(sub, color = fg, style = TextStyles.small.copy(fontSize = 13.sp), maxLines = 1)
        }
        Icon(lineIcon("chevron", IconPaths.CHEVRON), null, tint = fg)
    }
}

/** 小节标题（.section-label）。 */
@Composable
fun SectionLabel(text: String) = Text(
    text,
    color = LocalTokens.current.muted.color(),
    style = TextStyles.small.copy(fontWeight = FontWeight.SemiBold),
    modifier = Modifier.semantics {
        heading()
    },
)

/** 小字说明（.fineprint）。 */
@Composable
fun FinePrint(text: String, modifier: Modifier = Modifier) =
    Text(text, color = LocalTokens.current.muted.color(), style = TextStyles.small.copy(fontSize = 12.5.sp, lineHeight = 1.7.em()), modifier = modifier)

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/** 承载内容的卡片（.panel）：raised 底，保证文字对比度。 */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .background(t.raised.color(), RoundedCornerShape(R))
            .border(1.dp, t.line.color(), RoundedCornerShape(R))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/**
 * 输入框：和网页版一样，平时 bg 底描 line 边，聚焦时 raised 底、品牌色边加一圈淡光。字号不小于 16sp（不触发缩放）。
 * [display] 是“知识本身”（概念、讨论题、要点、小结）用衬线。
 */
@Composable
fun Input(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    style: TextStyle = TextStyles.body,
    big: Boolean = false,
    onDone: (() -> Unit)? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    // 口令：显示成圆点，不联想、不记进输入法词库
    secret: Boolean = false,
) {
    val t = LocalTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (big) 16.dp else 12.dp)
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = style.copy(color = t.ink.color()),
        cursorBrush = SolidColor(t.brand.color()),
        interactionSource = src,
        keyboardOptions = keyboard.copy(
            imeAction = if (onDone != null) ImeAction.Go else ImeAction.Default,
            keyboardType = if (secret) KeyboardType.Password else keyboard.keyboardType,
            autoCorrectEnabled = if (secret) false else keyboard.autoCorrectEnabled,
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier
                    .then(if (focused) Modifier.border(3.dp, t.brand.color().copy(alpha = 0.14f), shape) else Modifier)
                    .padding(if (focused) 0.dp else 0.dp)
                    .background(if (focused || big) t.raised.color() else t.bg.color(), shape)
                    .border(
                        1.dp,
                        if (focused) {
                            t.brand.color()
                        } else if (big) {
                            Color.Transparent
                        } else {
                            t.line.color()
                        },
                        shape,
                    )
                    .padding(horizontal = if (big) 16.dp else 13.dp, vertical = if (big) 15.dp else 11.dp),
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style.copy(color = t.muted.color()))
                inner()
            }
        },
    )
}

/** 加减数字（网页版 Stepper）：减号、数字、加号，按钮可点区域 44dp。 */
@Composable
fun Stepper(value: Int, min: Int, max: Int, label: String, onChange: (Int) -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.background(t.bg.color(), RoundedCornerShape(10.dp)).border(1.dp, t.line.color(), RoundedCornerShape(10.dp)).semantics {
            contentDescription =
                "$label：$value"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(IconPaths.MINUS, "减少$label", value > min) { onChange(value - 1) }
        Text("$value", color = t.ink.color(), style = TextStyles.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 2.dp))
        StepButton(IconPaths.PLUS, "增加$label", value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(path: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(44.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics {
            contentDescription =
                label
        },
        contentAlignment = Alignment.Center,
    ) { Icon(lineIcon(path, path), null, Modifier.size(16.dp), tint = t.ink.color()) }
}

/** 分段选择（.segmented）：几个选项平分一行，选中的那个浮起来。 */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, label: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().background(t.bg.color(), RoundedCornerShape(12.dp)).border(1.dp, t.line.color(), RoundedCornerShape(12.dp)).padding(3.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((id, text) in options) {
            val on = id == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) t.raised.color() else Color.Transparent, RoundedCornerShape(9.dp))
                    .alpha(if (enabled || on) 1f else 0.45f)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = if (on) t.ink.color() else t.muted.color(),
                    style = TextStyles.small.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
        }
    }
}

/** 状态小标签（.status）：[tone] 是 ok、warn 或空（灰色）。 */
@Composable
fun StatusTag(text: String, tone: String = "") {
    val t = LocalTokens.current
    val (bg, fg) = when (tone) {
        "ok" -> t.goodSoft.color() to t.good.color()
        "warn" -> t.warnSoft.color() to t.warn.color()
        else -> t.bg.color() to t.muted.color()
    }
    Text(
        text,
        color = fg,
        style = TextStyles.small.copy(fontSize = 12.sp),
        modifier = Modifier.background(bg, RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 底部操作区（.dock）：主要操作单手可达。上沿渐隐到透明，背景氛围透得出来。 */
@Composable
fun Dock(content: @Composable () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to t.surface.color().copy(alpha = 0.92f), 1f to t.surface.color()))
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** 出错时的原因（AI 失败、连不上……）：写原因和办法，可以关掉。 */
@Composable
fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    if (text.isEmpty()) return
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.badSoft.color(), RoundedCornerShape(12.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = t.bad.color(), style = TextStyles.small, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(lineIcon("close", IconPaths.CLOSE), "关闭", Modifier.size(16.dp), tint = t.bad.color())
        }
    }
}

/** 正在做什么（.busy-strip），可以取消。 */
@Composable
fun BusyStrip(label: String?, onCancel: () -> Unit) {
    if (label == null) return
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label…", color = t.muted.color(), style = TextStyles.small, modifier = Modifier.weight(1f))
        Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text("取消", color = t.accent.color(), style = TextStyles.small.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** 顶栏（.topbar）：返回、标题（衬线，知识本身）、副标题、右侧操作。 */
@Composable
fun TopBar(title: String, sub: String? = null, onBack: (() -> Unit)? = null, right: @Composable RowScope.() -> Unit = {}) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(lineIcon("back", IconPaths.BACK), "返回", tint = t.ink.color())
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                title,
                style = TextStyles.title,
                color = t.ink.color(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            if (sub != null) Text(sub, style = TextStyles.small.copy(fontSize = 12.sp), color = t.muted.color(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        right()
    }
}

/** 和 CSS 的 color-mix(in srgb, this [part], other) 一样：按比例混两种颜色。 */
fun Color.mix(part: Float, other: Color) = Color(
    red = red * part + other.red * (1 - part),
    green = green * part + other.green * (1 - part),
    blue = blue * part + other.blue * (1 - part),
    alpha = alpha * part + other.alpha * (1 - part),
)

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
)
