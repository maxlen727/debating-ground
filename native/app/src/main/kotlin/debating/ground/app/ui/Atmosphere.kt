package debating.ground.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.Tokens
import debating.ground.app.ui.theme.color
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

// 背景氛围：几团很大、边缘渐隐的色块，像隔着磨砂玻璃看到的光，再叠一层很淡的噪点。
// 和网页版 styles.css 的“背景氛围”一节是同一套参数（位置、半径、颜色、浓度、周期、漂移距离）。
// 不做实时模糊（旧手机耗电、安卓 12 以下不支持）：径向渐变渐隐到透明，看起来就是模糊过的样子。

/** 一团色块。位置是屏幕宽高的比例，半径和漂移距离是长边的比例；周期是走一个来回的秒数。 */
data class Glow(val x: Float, val y: Float, val r: Float, val color: (Tokens) -> Long, val alpha: Float, val periodSeconds: Int, val dx: Float, val dy: Float)

val Glows = listOf(
    Glow(0.78f, 0.12f, 0.75f, { it.accentSoft }, 0.85f, 97, -0.04f, 0.03f), // 右上：暖
    Glow(0.10f, 0.42f, 0.70f, { it.lit }, 0.70f, 113, 0.05f, -0.03f), // 左中：绿
    Glow(0.85f, 0.70f, 0.65f, { it.cBg }, 0.55f, 83, -0.03f, -0.04f), // 右下：冷
    Glow(0.30f, 0.95f, 0.60f, { it.bBg }, 0.50f, 127, 0.04f, -0.02f), // 底部：暖
)

/** 漂到哪了：0 到 1，在两端放慢（和网页版的 ease-in-out、alternate 一样）。 */
fun driftPhase(elapsedMillis: Long, periodSeconds: Int): Float {
    val cycle = elapsedMillis % (periodSeconds * 2000L) / (periodSeconds * 2000f)
    return ((1 - cos(cycle * 2 * PI)) / 2).toFloat()
}

/** 能不能动：系统“移除动画”时为 false，截图测试里也设成 false，画面才固定。 */
val LocalMotion = staticCompositionLocalOf { true }

@Composable
fun Atmosphere(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val grain = remember { grainTile() }
    val elapsed = remember { mutableLongStateOf(0L) }
    if (LocalMotion.current) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            // 一秒只挪不到一个像素，每秒 15 帧就足够平滑；App 在后台时帧时钟暂停，这里也跟着停
            while (true) {
                elapsed.longValue = withFrameMillis { it } - start
                delay(66)
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        // 在绘制阶段读时间：只重画这一层，不触发重组
        val now = elapsed.longValue
        drawRect(t.surface.color())
        val base = maxOf(size.width, size.height)
        for (g in Glows) {
            val c = g.color(t).color()
            val p = driftPhase(now, g.periodSeconds)
            drawRect(
                Brush.radialGradient(
                    colors = listOf(c.copy(alpha = g.alpha), c.copy(alpha = g.alpha * 0.4f), Color.Transparent),
                    center = Offset(size.width * g.x + base * g.dx * p, size.height * g.y + base * g.dy * p),
                    radius = base * g.r,
                ),
            )
        }
        drawRect(ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)))
    }
}

// 噪点：固定种子生成一小块再平铺，每次都一样。每个像素是透明度很低的黑或白：近看有纸面的颗粒，远看看不出
private fun grainTile(size: Int = 128): ImageBitmap {
    val rnd = Random(20261117)
    val alpha = 14 shl 24
    val px = IntArray(size * size) { if (rnd.nextBoolean()) alpha or 0xFFFFFF else alpha }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
