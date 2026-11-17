package debating.ground.app.data

import kotlin.math.ceil

// 间隔复习：记得清楚就拉长间隔，模糊就明天再来。和网页版 src/lib/srs.ts 一致
const val DAY = 86_400_000L
val INTERVALS = listOf(1, 3, 7, 14, 30, 60) // 天

data class Scheduled(val level: Int, val due: Long, val days: Int)

fun schedule(level: Int, remembered: Boolean, now: Long): Scheduled {
    val next = if (remembered) minOf(level + 1, INTERVALS.size - 1) else 0
    return Scheduled(next, now + INTERVALS[next] * DAY, INTERVALS[next])
}

fun dueLabel(due: Long, now: Long): String {
    val d = ceil((due - now).toDouble() / DAY).toInt()
    return when {
        d <= 0 -> "今天"
        d == 1 -> "明天"
        else -> "$d 天后"
    }
}
