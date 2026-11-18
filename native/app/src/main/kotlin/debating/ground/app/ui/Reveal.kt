package debating.ground.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.color
import kotlin.math.max
import kotlin.math.min

// 逐字显示 AI 正在生成的文字，照网页版 src/components/Reveal.tsx：
// 收到的文字一次可能来一大段，按“积压越多越快”的速度追赶，最新的几个字淡入，末尾跟一个光标。
// 生成中的气泡和完成后的正式发言用同一个 id 记进度，所以衔接时不会从头再写一遍。
object RevealProgress {
    private val shown = HashMap<String, Float>()

    fun get(id: String): Float? = shown[id]

    fun set(id: String, n: Float) {
        shown[id] = n
    }

    fun done(id: String) {
        shown.remove(id)
    }
}

private const val FADE = 18 // 最新的这么多个字单独淡入

@Composable
fun RevealText(id: String, text: String, live: Boolean, style: TextStyle, color: Color, modifier: Modifier = Modifier, final: AnnotatedString? = null) {
    val motion = LocalMotion.current
    // 没开动效，或者这段文字从来没在生成中显示过（打开旧讨论），直接显示全文
    var finished by remember(id) { mutableStateOf(!motion || (!live && RevealProgress.get(id) == null)) }
    if (finished) {
        if (final != null) Text(final, style = style, color = color, modifier = modifier) else Text(text, style = style, color = color, modifier = modifier)
        return
    }
    val target by rememberUpdatedState(text)
    val still by rememberUpdatedState(live)
    var n by remember(id) { mutableFloatStateOf(RevealProgress.get(id) ?: 0f) }
    LaunchedEffect(id) {
        if (RevealProgress.get(id) == null) RevealProgress.set(id, 0f)
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = min(0.1f, (now - last) / 1e9f)
            last = now
            val len = target.length
            val backlog = len - n
            if (backlog <= 0f) {
                if (!still) {
                    RevealProgress.done(id)
                    finished = true
                    return@LaunchedEffect
                }
            } else {
                // 积压少时慢慢写，积压多时加速追上，避免最后一口气吐出一大段
                val speed = max(24f, backlog * 4.5f)
                n = min(len.toFloat(), n + speed * dt)
                RevealProgress.set(id, n)
            }
        }
    }
    val shownCount = min(text.length, n.toInt())
    val cut = max(0, shownCount - FADE)
    val caret = LocalTokens.current.brand.color()
    val annotated = buildAnnotatedString {
        append(text.substring(0, cut))
        for (i in cut until shownCount) {
            // 越新的字越淡，像墨水刚落下
            val a = 1f - (i - cut + 1).toFloat() / (FADE + 1) * 0.75f
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * a))) { append(text[i]) }
        }
        withStyle(SpanStyle(color = caret)) { append("▍") }
    }
    Text(annotated, style = style, color = color, modifier = modifier)
}
