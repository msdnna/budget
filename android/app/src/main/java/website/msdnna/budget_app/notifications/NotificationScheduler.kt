package website.msdnna.budget_app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object NotificationScheduler {
    const val ACTION_EXPENSES = "website.msdnna.budget_app.NOTIFY_EXPENSES"
    const val ACTION_INCOME   = "website.msdnna.budget_app.NOTIFY_INCOME"
    private const val REQ_EXPENSES = 1001
    private const val REQ_INCOME   = 1002

    fun applyPrefs(context: Context, prefs: NotificationPrefs) {
        if (prefs.expensesEnabled) {
            scheduleExpenses(context, prefs.expensesHour, prefs.expensesMinute)
        } else {
            cancelExpenses(context)
        }
        if (prefs.incomeEnabled) {
            scheduleIncome(context, prefs.incomeHour, prefs.incomeMinute, prefs.incomeDay)
        } else {
            cancelIncome(context)
        }
    }

    fun scheduleExpenses(context: Context, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, REQ_EXPENSES, ACTION_EXPENSES, hour, minute, -1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDaily(hour, minute), pi)
    }

    fun scheduleIncome(context: Context, hour: Int, minute: Int, day: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, REQ_INCOME, ACTION_INCOME, hour, minute, day)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMonthly(hour, minute, day), pi)
    }

    fun cancelExpenses(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQ_EXPENSES,
            Intent(context, NotificationReceiver::class.java).apply { action = ACTION_EXPENSES },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    fun cancelIncome(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQ_INCOME,
            Intent(context, NotificationReceiver::class.java).apply { action = ACTION_INCOME },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(
        context: Context, reqCode: Int, action: String,
        hour: Int, minute: Int, day: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            this.action = action
            putExtra("hour", hour)
            putExtra("minute", minute)
            if (day >= 0) putExtra("day", day)
        }
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun nextDaily(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    fun nextMonthly(hour: Int, minute: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.MONTH, 1)
                set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
        }.timeInMillis
}
