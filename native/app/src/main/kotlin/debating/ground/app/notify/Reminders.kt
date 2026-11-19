package debating.ground.app.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import debating.ground.app.MainActivity
import debating.ground.app.R
import debating.ground.app.data.Card
import debating.ground.app.data.SavedState
import debating.ground.app.data.Storage
import debating.ground.app.data.loadState
import java.util.Calendar

// 复习提醒：每天在选好的时间看一眼知识库，有到期的卡片才发一条通知，没有就不打扰。
// 用系统的不精确每日闹钟（省电，不要精确闹钟权限），重启后由 ReminderReceiver 重新排上。
// 设置是这台设备自己的（SharedPreferences），不进和网页版共用的存档。通知里只有概念名，没有讨论内容。

/** 可以选的提醒时间（小时、分钟、说法）。 */
val REMINDER_TIMES = listOf(Triple(8, 0, "早上 8 点"), Triple(12, 30, "中午 12 点半"), Triple(20, 0, "晚上 8 点"), Triple(22, 0, "晚上 10 点"))

data class ReminderPrefs(val enabled: Boolean = false, val hour: Int = 20, val minute: Int = 0)

/** 下一次提醒的时刻：今天这个时间还没到就是今天，过了就是明天。 */
fun nextReminderAt(now: Long, hour: Int, minute: Int, calendar: Calendar = Calendar.getInstance()): Long {
    calendar.timeInMillis = now
    calendar.set(Calendar.HOUR_OF_DAY, hour)
    calendar.set(Calendar.MINUTE, minute)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
}

/** 通知的标题和正文；没有到期的卡片时返回 null（不发）。最多列三个概念名。 */
fun reminderText(library: List<Card>, now: Long): Pair<String, String>? {
    val due = library.filter { it.due <= now }.sortedBy { it.due }
    if (due.isEmpty()) return null
    val names = due.take(3).joinToString("、") { it.concept } + if (due.size > 3) " 等" else ""
    return "今天有 ${due.size} 张卡片要复习" to "$names。趁记忆还在，花几分钟回顾一下。"
}

object Reminders {
    private const val PREFS = "reminders"
    private const val CHANNEL = "review"
    private const val NOTIFY_ID = 1

    /** 点通知打开 App 时带的标记：直接进知识库。 */
    const val OPEN_LIBRARY = "debating.ground.app.OPEN_LIBRARY"

    fun prefs(context: Context): ReminderPrefs {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReminderPrefs(p.getBoolean("enabled", false), p.getInt("hour", 20), p.getInt("minute", 0))
    }

    fun save(context: Context, prefs: ReminderPrefs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", prefs.enabled)
            putInt("hour", prefs.hour)
            putInt("minute", prefs.minute)
        }
        schedule(context)
    }

    /** Android 13 起发通知要权限。 */
    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 按当前设置排好（或取消）每天的闹钟。 */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        val p = prefs(context)
        if (!p.enabled) return
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextReminderAt(System.currentTimeMillis(), p.hour, p.minute), AlarmManager.INTERVAL_DAY, pi)
    }

    /** 闹钟到了：读存档，有到期的卡片才发通知。 */
    fun fire(context: Context) {
        if (!prefs(context).enabled || !canNotify(context)) return
        val now = System.currentTimeMillis()
        val saved = runCatching { loadState(Storage(context.filesDir).read(), null, now) }.getOrElse { SavedState() }
        val (title, body) = reminderText(saved.library, now) ?: return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "复习提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(OPEN_LIBRARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // 权限可能在两次提醒之间被用户收回：收回了就不发
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
        }
    }
}

/** 闹钟到点、手机重启、App 更新后：发提醒或重新排上闹钟。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FIRE -> Reminders.fire(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.schedule(context)
        }
    }

    companion object {
        const val FIRE = "debating.ground.app.REMIND"
    }
}
