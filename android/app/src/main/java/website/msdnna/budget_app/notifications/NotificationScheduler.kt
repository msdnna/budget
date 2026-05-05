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

    const val EXTRA_FREQUENCY    = "frequency"
    const val EXTRA_HOUR         = "hour"
    const val EXTRA_MINUTE       = "minute"
    const val EXTRA_DAY_OF_WEEK  = "dayOfWeek"
    const val EXTRA_DAY_OF_MONTH = "dayOfMonth"

    fun applyPrefs(context: Context, prefs: NotificationPrefs) {
        if (prefs.expensesEnabled) {
            scheduleExpenses(
                context, prefs.expensesFrequency,
                prefs.expensesHour, prefs.expensesMinute,
                prefs.expensesDayOfWeek, prefs.expensesDayOfMonth,
            )
        } else cancelExpenses(context)

        if (prefs.incomeEnabled) {
            scheduleIncome(
                context, prefs.incomeFrequency,
                prefs.incomeHour, prefs.incomeMinute,
                prefs.incomeDayOfWeek, prefs.incomeDayOfMonth,
            )
        } else cancelIncome(context)
    }

    fun scheduleExpenses(
        context: Context, frequency: NotificationFrequency,
        hour: Int, minute: Int, dayOfWeek: Int, dayOfMonth: Int,
    ) = schedule(
        context, REQ_EXPENSES, ACTION_EXPENSES,
        frequency, hour, minute, dayOfWeek, dayOfMonth,
    )

    fun scheduleIncome(
        context: Context, frequency: NotificationFrequency,
        hour: Int, minute: Int, dayOfWeek: Int, dayOfMonth: Int,
    ) = schedule(
        context, REQ_INCOME, ACTION_INCOME,
        frequency, hour, minute, dayOfWeek, dayOfMonth,
    )

    private fun schedule(
        context: Context, reqCode: Int, action: String,
        frequency: NotificationFrequency,
        hour: Int, minute: Int, dayOfWeek: Int, dayOfMonth: Int,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(
            context, reqCode, action,
            frequency, hour, minute, dayOfWeek, dayOfMonth,
        )
        val triggerAt = nextTrigger(frequency, hour, minute, dayOfWeek, dayOfMonth)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    fun cancelExpenses(context: Context) = cancel(context, REQ_EXPENSES, ACTION_EXPENSES)
    fun cancelIncome(context: Context)   = cancel(context, REQ_INCOME, ACTION_INCOME)

    private fun cancel(context: Context, reqCode: Int, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, reqCode,
            Intent(context, NotificationReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(
        context: Context, reqCode: Int, action: String,
        frequency: NotificationFrequency,
        hour: Int, minute: Int, dayOfWeek: Int, dayOfMonth: Int,
    ): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_FREQUENCY, frequency.name)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_DAY_OF_WEEK, dayOfWeek)
            putExtra(EXTRA_DAY_OF_MONTH, dayOfMonth)
        }
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun nextTrigger(
        frequency: NotificationFrequency,
        hour: Int, minute: Int, dayOfWeek: Int, dayOfMonth: Int,
    ): Long = when (frequency) {
        NotificationFrequency.DAILY     -> nextDaily(hour, minute)
        NotificationFrequency.WEEKLY    -> nextWeekly(hour, minute, dayOfWeek)
        NotificationFrequency.MONTHLY   -> nextMonthly(hour, minute, dayOfMonth, monthsStep = 1)
        NotificationFrequency.QUARTERLY -> nextMonthly(hour, minute, dayOfMonth, monthsStep = 3)
    }

    private fun nextDaily(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    private fun nextWeekly(hour: Int, minute: Int, dayOfWeek: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // Calendar.DAY_OF_WEEK uses 1..7 with SUNDAY=1.
            val current = get(Calendar.DAY_OF_WEEK)
            var diff = ((dayOfWeek - current) + 7) % 7
            if (diff == 0 && timeInMillis <= System.currentTimeMillis()) diff = 7
            add(Calendar.DAY_OF_YEAR, diff)
        }.timeInMillis

    private fun nextMonthly(hour: Int, minute: Int, day: Int, monthsStep: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.MONTH, monthsStep)
                set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
        }.timeInMillis
}
