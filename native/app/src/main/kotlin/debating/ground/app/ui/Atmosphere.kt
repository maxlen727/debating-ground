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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import debating.ground.app.R
import debating.ground.app.ui.theme.DarkTokens
import debating.ground.app.ui.theme.LocalTokens
import debating.ground.app.ui.theme.color
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

// 背景氛围：几团很大、边缘渐隐的色块，像隔着磨砂玻璃看到的光，再叠一层很淡的噪点。
// 色块是 scripts/atmosphere.py 离线渲染好的高斯模糊图（和网页版同一组图，颜色取自令牌），
// 实时画径向渐变会有折痕和色带。整张图比屏幕每边大 6%，沿着和网页版 @keyframes drift 一样的路线极慢地漂。

/** 图片比屏幕每边多出的比例，和 src/assets/atmosphere/atmosphere.json 的 overscan 一致。 */
const val OVERSCAN = 0.06f

/** 漂移路线：从原位出发依次经过这几个点（图片宽高的比例），走完一程 [DRIFT_SECONDS] 秒，再原路返回。 */
val DriftPath = listOf(0f to 0f, -0.03f to 0.02f, 0.01f to 0.04f, 0.03f to -0.02f)
const val DRIFT_SECONDS = 200

/** 漂到哪了：按经过的时间在路线上线性插值（和网页版一样，往返走）。 */
fun driftAt(elapsedMillis: Long): Pair<Float, Float> {
    val leg = DRIFT_SECONDS * 1000L
    val cycle = elapsedMillis % (leg * 2)
    val p = (if (cycle < leg) cycle else leg * 2 - cycle).toFloat() / leg // 0 → 1 → 0
    val segs = DriftPath.size - 1
    val i = minOf((p * segs).toInt(), segs - 1)
    val f = p * segs - i
    val (ax, ay) = DriftPath[i]
    val (bx, by) = DriftPath[i + 1]
    return (ax + (bx - ax) * f) to (ay + (by - ay) * f)
}

/** 能不能动：系统“移除动画”时为 false，截图测试里也设成 false，画面才固定。 */
val LocalMotion = staticCompositionLocalOf { true }

@Composable
fun Atmosphere(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val dark = t == DarkTokens
    val portrait = ImageBitmap.imageResource(if (dark) R.drawable.atmosphere_dark_portrait else R.drawable.atmosphere_light_portrait)
    val landscape = ImageBitmap.imageResource(if (dark) R.drawable.atmosphere_dark_landscape else R.drawable.atmosphere_light_landscape)
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
        drawRect(t.surface.color())
        // 在绘制阶段读时间：只重画这一层，不触发重组
        val (dx, dy) = driftAt(elapsed.longValue)
        val img = if (size.width > size.height) landscape else portrait
        val w = size.width * (1 + 2 * OVERSCAN)
        val h = size.height * (1 + 2 * OVERSCAN)
        drawImage(
            img,
            dstOffset = IntOffset((-size.width * OVERSCAN + dx * w).roundToInt(), (-size.height * OVERSCAN + dy * h).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low, // 双线性放大，图本身是糊的，看不出像素
        )
        drawRect(ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)))
    }
}

// 噪点：固定种子生成一小块再平铺，每次都一样。每个像素是很淡的一点亮或暗，强弱随机（多数几乎看不见）：
// 近看有纸面的颗粒，远看看不出
private fun grainTile(size: Int = 128): ImageBitmap {
    val rnd = Random(20261117)
    val px = IntArray(size * size) {
        val v = rnd.nextFloat() - rnd.nextFloat()
        val a = (abs(v) * 18).toInt()
        (a shl 24) or (if (v > 0) 0xFFFFFF else 0)
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
