package expo.modules.alarmclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
  private const val TAG = "AlarmClock"
  const val ACTION_FIRE = "expo.modules.alarmclock.FIRE"
  const val EXTRA_KEY = "expo.modules.alarmclock.KEY"

  private fun alarmManager(ctx: Context) =
    ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  fun canScheduleExact(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(ctx).canScheduleExactAlarms()

  /** Next wall-clock occurrence of [weekday] (0 = Sunday) at hour:minute, strictly after [after]. */
  fun nextWeekly(weekday: Int, hour: Int, minute: Int, after: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance().apply {
      timeInMillis = after
      set(Calendar.HOUR_OF_DAY, hour)
      set(Calendar.MINUTE, minute)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val target = weekday + 1 // Calendar: SUNDAY = 1 ... SATURDAY = 7
    cal.add(Calendar.DAY_OF_YEAR, (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7)
    if (cal.timeInMillis <= after) cal.add(Calendar.DAY_OF_YEAR, 7)
    return cal.timeInMillis
  }

  fun launchIntent(ctx: Context): Intent =
    (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).apply {
      setPackage(ctx.packageName)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

  private fun firePendingIntent(ctx: Context, key: String): PendingIntent {
    val intent = Intent(ctx, AlarmReceiver::class.java)
      .setAction(ACTION_FIRE)
      // A unique data URI gives every trigger its own PendingIntent (no request-code collisions).
      .setData(Uri.parse("alarmclock://trigger/${Uri.encode(key)}"))
      .putExtra(EXTRA_KEY, key)
    return PendingIntent.getBroadcast(
      ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  /** Saves and arms a trigger. Replaces any trigger with the same key. */
  fun schedule(ctx: Context, t: AlarmTrigger) {
    AlarmStore.put(ctx, t)
    arm(ctx, t)
  }

  fun cancel(ctx: Context, key: String) {
    alarmManager(ctx).cancel(firePendingIntent(ctx, key))
    AlarmStore.remove(ctx, key)
  }

  private fun arm(ctx: Context, t: AlarmTrigger) {
    val am = alarmManager(ctx)
    val fire = firePendingIntent(ctx, t.key)
    try {
      if (canScheduleExact(ctx)) {
        // setAlarmClock is what the stock Clock app uses: exact, exempt from Doze and
        // app-standby, and it shows the alarm icon in the status bar.
        val show = PendingIntent.getActivity(
          ctx, 0, launchIntent(ctx),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(t.timestamp, show), fire)
      } else {
        // No exact-alarm permission: best effort, may be a few minutes late.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timestamp, fire)
      }
    } catch (e: SecurityException) {
      Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timestamp, fire)
    }
  }

  /**
   * Called when a trigger fires. Re-arms weekly triggers for next week and forgets
   * one-time ones. Returns the trigger that fired, or null if it was cancelled.
   */
  fun onFired(ctx: Context, key: String): AlarmTrigger? {
    val t = AlarmStore.get(ctx, key) ?: return null
    if (t.repeats) {
      val after = maxOf(System.currentTimeMillis(), t.timestamp)
      schedule(ctx, t.copy(timestamp = nextWeekly(t.weekday, t.hour, t.minute, after)))
    } else {
      AlarmStore.remove(ctx, key)
    }
    return t
  }

  fun snooze(ctx: Context, t: AlarmTrigger) {
    schedule(
      ctx,
      t.copy(
        key = "${t.alarmId}__snooze",
        weekday = -1,
        timestamp = System.currentTimeMillis() + t.snoozeMinutes * 60_000L,
      )
    )
  }

  /** Re-arms everything after reboot / time change / app update. */
  fun rescheduleAll(ctx: Context) {
    val now = System.currentTimeMillis()
    for (t in AlarmStore.all(ctx)) {
      when {
        t.repeats -> schedule(ctx, t.copy(timestamp = nextWeekly(t.weekday, t.hour, t.minute, now)))
        t.timestamp > now -> arm(ctx, t)
        else -> AlarmStore.remove(ctx, t.key) // one-time alarm missed while the phone was off
      }
    }
  }
}
