package debating.ground.app.flow

import debating.ground.app.data.Config
import debating.ground.app.data.Seat
import debating.ground.app.data.Turn

// 正反辩论的赛制：席位与发言顺序。和网页版 src/lib/debate.ts 一致

fun slots(c: Config): List<Seat> {
    val counts = { t: String -> c.counts[t] ?: 0 }
    val rows = maxOf(counts("A"), counts("B"))
    return (0 until rows).flatMap { i ->
        listOf("A", "B").filter { i < counts(it) }.map { t ->
            val sc = c.seats[t]?.getOrNull(i)
            Seat(id = t + (i + 1), team = t, index = i, type = sc?.type ?: "ai", bg = sc?.bg ?: "")
        }
    }
}

fun debateTurns(c: Config): List<Turn> {
    val all = slots(c)
    fun team(t: String) = all.filter { it.team == t }
    fun at(t: String, i: Int) = team(t)[minOf(i, team(t).size - 1)]
    return c.stages.flatMapIndexed { stage, s ->
        (0 until s.rounds).flatMap { round ->
            val turns: List<Triple<Seat, String, String?>> = when (s.kind) {
                "opening" -> listOf(Triple(at("A", 0), "立论", null), Triple(at("B", 0), "立论", null))

                "cross" -> listOf(
                    Triple(at("A", 1), "质询", at("B", 2).id),
                    Triple(at("B", 2), "答辩", at("A", 1).id),
                    Triple(at("B", 1), "质询", at("A", 2).id),
                    Triple(at("A", 2), "答辩", at("B", 1).id),
                )

                "closing" -> listOf(Triple(team("B").last(), "总结陈词", null), Triple(team("A").last(), "总结陈词", null))

                else -> listOf(
                    Triple(team("A")[round % team("A").size], "自由辩论", null),
                    Triple(team("B")[round % team("B").size], "自由辩论", null),
                )
            }
            turns.map { (role, action, target) -> Turn(role, action, target, stage, round) }
        }
    }
}

fun seatName(team: String, index: Int) = "${if (team == "A") "正" else "反"}方 ${index + 1} 辩"

fun seatName(s: Seat) = seatName(s.team, s.index)
