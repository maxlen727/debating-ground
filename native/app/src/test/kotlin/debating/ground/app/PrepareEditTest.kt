package debating.ground.app

import debating.ground.app.ai.Ai
import debating.ground.app.data.Plan
import debating.ground.app.data.SeatConfig
import debating.ground.app.data.Stage
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import org.junit.Assert.assertEquals
import org.junit.Test

// 准备页的辩论设置：人数和比赛流程。和网页版 Prepare.tsx 里 Seats、Stages 的规则一致
class PrepareEditTest {
    private fun flow(plan: Plan? = null) = Learning(AppState(plan = plan), Ai(demoTypingMs = 0))

    @Test fun newSeatsTakeTheGeneratedBackgroundOrAGenericOne() {
        val l = flow(Plan("debate", "机会成本", "辩题", debaters = mapOf("A" to listOf("经济学", "社会学", "心理学"))))
        l.editConfig { it.copy(seats = it.seats + ("A" to listOf(SeatConfig("human", ""), SeatConfig("ai", "")))) }
        l.setTeamCount("A", 4)
        val a = l.state.config.seats.getValue("A")
        assertEquals(4, l.state.config.counts["A"])
        // 空着的背景补上生成的，新席位先用生成的，没有了才用通用的一句
        assertEquals(listOf("经济学", "社会学", "心理学", "从实际案例出发，关注论证的适用条件。"), a.map { it.bg })
        assertEquals("human", a[0].type)
        l.setTeamCount("A", 1)
        assertEquals(1, l.state.config.counts["A"])
        assertEquals(4, l.state.config.seats.getValue("A").size) // 减人数不丢已经写好的背景
    }

    @Test fun stagesStayBetweenOneAndSix() {
        val l = flow()
        val start = l.state.config.stages
        l.editStages { it + Stage("自由辩论", "free", 1) }
        assertEquals(start.size + 1, l.state.config.stages.size)
        repeat(10) { l.editStages { it + Stage("自由辩论", "free", 1) } }
        assertEquals(6, l.state.config.stages.size)
        repeat(10) { l.editStages { it.drop(1) } }
        assertEquals(1, l.state.config.stages.size)
    }
}
