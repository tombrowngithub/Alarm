package expo.modules.alarmclock

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class TriggerRecord : Record {
  @Field var key: String = ""
  @Field var alarmId: String = ""
  @Field var label: String = "Alarm"
  @Field var timeText: String = ""
  @Field var weekday: Int = -1
  @Field var hour: Int = 0
  @Field var minute: Int = 0
  @Field var timestamp: Double = 0.0
  @Field var snoozeMinutes: Int = 9

  fun toTrigger() = AlarmTrigger(
    key, alarmId, label, timeText, weekday, hour, minute, timestamp.toLong(), snoozeMinutes
  )
}

class AlarmClockModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("AlarmClock")

    Function("schedule") { trigger: TriggerRecord ->
      AlarmScheduler.schedule(context, trigger.toTrigger())
    }

    Function("cancel") { keys: List<String> ->
      keys.forEach { AlarmScheduler.cancel(context, it) }
    }

    Function("getRinging") {
      AlarmService.current?.toMap()
    }

    Function("stop") {
      AlarmService.stop(context)
      leaveLockScreen()
    }

    Function("snooze") {
      AlarmService.snooze(context)
      leaveLockScreen()
    }

    Function("getStatus") {
      val ctx = context
      mapOf(
        "notifications" to NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
        "exactAlarms" to AlarmScheduler.canScheduleExact(ctx),
        "fullScreen" to canUseFullScreenIntent(ctx),
        "batteryUnrestricted" to isIgnoringBatteryOptimizations(ctx),
      )
    }

    Function("openNotificationSettings") {
      open(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      )
    }

    Function("openExactAlarmSettings") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
      }
    }

    Function("openFullScreenSettings") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
      }
    }

    Function("requestIgnoreBatteryOptimizations") {
      open(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
        fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
      )
    }
  }

  private fun packageUri() = Uri.parse("package:${context.packageName}")

  private fun canUseFullScreenIntent(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
      ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

  private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .isIgnoringBatteryOptimizations(ctx.packageName)

  private fun open(intent: Intent, fallback: Intent? = null) {
    val activity = appContext.currentActivity
    val launcher: Context = activity ?: context
    if (activity == null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
      launcher.startActivity(intent)
    } catch (e: Exception) {
      val alt = fallback ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
        .also { if (activity == null) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      runCatching { launcher.startActivity(alt) }
    }
  }

  /** After Stop / Snooze over the lock screen, drop back to the lock screen like a real alarm. */
  private fun leaveLockScreen() {
    val activity = appContext.currentActivity ?: return
    val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (keyguard.isKeyguardLocked) {
      activity.runOnUiThread { activity.moveTaskToBack(true) }
    }
  }
}
