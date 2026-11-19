package debating.ground.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import debating.ground.app.data.Card
import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Storage
import debating.ground.app.notify.ReminderPrefs
import debating.ground.app.notify.Reminders
import debating.ground.app.notify.nextReminderAt
import debating.ground.app.notify.reminderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

// 复习提醒：下次提醒的时刻、通知文案，以及排闹钟和发通知（有到期的卡片才发）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(h: Int, m: Int, day: Int = 18) = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, h, m)
    }.timeInMillis

    @Test
    fun nextReminderIsTodayIfNotPassedOtherwiseTomorrow() {
        assertEquals(at(20, 0), nextReminderAt(at(9, 15), 20, 0, Calendar.getInstance(zone)))
        assertEquals(at(20, 0, 19), nextReminderAt(at(20, 0), 20, 0, Calendar.getInstance(zone)))
        assertEquals(at(8, 0, 19), nextReminderAt(at(21, 30), 8, 0, Calendar.getInstance(zone)))
    }

    private fun card(id: String, concept: String, due: Long) = Card(id = id, concept = concept, due = due)

    @Test
    fun textListsUpToThreeDueConceptsAndStaysQuietOtherwise() {
        val now = at(20, 0)
        assertNull(reminderText(listOf(card("a", "机会成本", now + 1)), now))
        val four = listOf(
            card("a", "机会成本", now - 4),
            card("b", "沉没成本", now - 3),
            card("c", "边际效用", now - 2),
            card("d", "认知失调", now - 1),
            card(
                "e",
                "没到期",
                now + 99,
            ),
        )
        val (title, body) = reminderText(four, now)!!
        assertEquals("今天有 4 张卡片要复习", title)
        assertEquals("机会成本、沉没成本、边际效用 等。趁记忆还在，花几分钟回顾一下。", body)
    }

    @Test
    fun schedulingAndFiring() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        // 关着：不排闹钟，也不发
        Reminders.save(app, ReminderPrefs(enabled = false))
        assertNull(alarms.nextScheduledAlarm)
        // 打开：排上每天的闹钟
        Reminders.save(app, ReminderPrefs(enabled = true, hour = 8, minute = 0))
        assertEquals(AlarmManager.INTERVAL_DAY, alarms.nextScheduledAlarm!!.interval)
        // 没有到期的卡片：不发
        val now = System.currentTimeMillis()
        val write = { cards: List<Card> -> Storage(app.filesDir).write(SibianJson.encodeToString(SavedState.serializer(), SavedState(library = cards))) }
        write(listOf(card("a", "机会成本", now + 86_400_000)))
        Reminders.fire(app)
        assertTrue(nm.allNotifications.isEmpty())
        // 有到期的：发一条，只写概念名
        write(listOf(card("a", "机会成本", now - 1000)))
        Reminders.fire(app)
        val n = nm.allNotifications.single()
        assertEquals("今天有 1 张卡片要复习", shadowOf(n).contentTitle)
        // 关掉：取消闹钟
        Reminders.save(app, ReminderPrefs(enabled = false))
        assertNull(alarms.nextScheduledAlarm)
    }
}
