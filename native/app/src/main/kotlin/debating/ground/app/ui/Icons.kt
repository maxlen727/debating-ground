package debating.ground.app.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 图标和网页版（src/components/ui.tsx 的 PATHS）用同一组线条路径：24 格画布、1.8 线宽、圆头圆角。
// 颜色由使用处的 tint 决定，这里画成黑色。不用 emoji 当图标（AGENTS.md）。
object IconPaths {
    const val back = "M15 18l-6-6 6-6"
    const val chevron = "M9 6l6 6-6 6"
    const val learn = "M4 19V6a2 2 0 0 1 2-2h12v15H6a2 2 0 0 0-2 2zM8 8h6"
    const val chat = "M5 5h14v10H9l-4 4z"
    const val library = "M5 4h4v16H5zM10 4h4v16h-4zM15.5 5l3.5 1-3 14-3.5-1z"
    const val settings = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7 7 0 0 0-2.1-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2.1 1.2l-2.3-.9-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-.9a7 7 0 0 0 2.1 1.2L10 21h4l.5-2.6a7 7 0 0 0 2.1-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z"
}

private val cache = HashMap<String, ImageVector>()

fun lineIcon(name: String, path: String): ImageVector = cache.getOrPut(path) {
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
