package debating.ground.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// 图标和网页版（src/components/ui.tsx 的 PATHS）用同一组线条路径：24 格画布、1.8 线宽、圆头圆角。
// 颜色由使用处的 tint 决定，这里画成黑色。不用 emoji 当图标（AGENTS.md）。
object IconPaths {
    const val BACK = "M15 18l-6-6 6-6"
    const val CHEVRON = "M9 6l6 6-6 6"
    const val CLOSE = "M6 6l12 12M18 6L6 18"
    const val PLUS = "M12 5v14M5 12h14"
    const val SEND = "M5 12h13M12 5l7 7-7 7"
    const val LEARN = "M4 19V6a2 2 0 0 1 2-2h12v15H6a2 2 0 0 0-2 2zM8 8h6"
    const val CHAT = "M5 5h14v10H9l-4 4z"
    const val LIBRARY = "M5 4h4v16H5zM10 4h4v16h-4zM15.5 5l3.5 1-3 14-3.5-1z"
    const val BULB = "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"
    const val NOTE = "M5 4h10l4 4v12H5zM14 4v5h5M8 13h8M8 17h5"
    const val PEOPLE = "M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 20a6 6 0 0 1 12 0M16 4a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 6"
    const val SWAP = "M7 7h11l-3-3M17 17H6l3 3"

    // 路径要和网页版逐字一致（IconPathsTest），不拆行：拆成两段拼接会被 Android Lint 误报为漏写空格
    @Suppress("ktlint:standard:max-line-length")
    const val SETTINGS = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7 7 0 0 0-2.1-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2.1 1.2l-2.3-.9-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-.9a7 7 0 0 0 2.1 1.2L10 21h4l.5-2.6a7 7 0 0 0 2.1-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z"
}

private val cache = HashMap<String, ImageVector>()

fun lineIcon(name: String, path: String): ImageVector = cache.getOrPut(path) {
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
