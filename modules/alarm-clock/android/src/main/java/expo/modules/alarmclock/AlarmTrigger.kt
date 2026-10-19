package expo.modules.alarmclock

import android.content.Context
import org.json.JSONObject

/**
 * One scheduled ring. Repeating alarms have one trigger per weekday
 * (weekday 0 = Sunday ... 6 = Saturday). One-time and snooze triggers use weekday = -1.
 */
data class AlarmTrigger(
  val key: String,
  val alarmId: String,
  val label: String,
  val timeText: String,
  val weekday: Int,
  val hour: Int,
  val minute: Int,
  val timestamp: Long,
  val snoozeMinutes: Int,
) {
  val repeats: Boolean get() = weekday in 0..6

  fun toJson(): JSONObject = JSONObject()
    .put("key", key)
    .put("alarmId", alarmId)
    .put("label", label)
    .put("timeText", timeText)
    .put("weekday", weekday)
    .put("hour", hour)
    .put("minute", minute)
    .put("timestamp", timestamp)
    .put("snoozeMinutes", snoozeMinutes)

  fun toMap(): Map<String, Any> = mapOf(
    "key" to key,
    "alarmId" to alarmId,
    "label" to label,
    "timeText" to timeText,
    "weekday" to weekday,
    "hour" to hour,
    "minute" to minute,
    "timestamp" to timestamp.toDouble(),
    "snoozeMinutes" to snoozeMinutes,
  )

  companion object {
    fun fromJson(o: JSONObject) = AlarmTrigger(
      key = o.getString("key"),
      alarmId = o.optString("alarmId", ""),
      label = o.optString("label", "Alarm"),
      timeText = o.optString("timeText", ""),
      weekday = o.optInt("weekday", -1),
      hour = o.optInt("hour", 0),
      minute = o.optInt("minute", 0),
      timestamp = o.optLong("timestamp", 0L),
      snoozeMinutes = o.optInt("snoozeMinutes", 9),
    )

    fun fromJsonString(s: String?): AlarmTrigger? =
      s?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
  }
}

/**
 * Persists armed triggers natively so they can be re-armed after a reboot,
 * a time-zone change or an app update, without starting React Native.
 */
object AlarmStore {
  private const val PREFS = "expo.modules.alarmclock.triggers"

  private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun all(ctx: Context): List<AlarmTrigger> =
    prefs(ctx).all.values.mapNotNull { AlarmTrigger.fromJsonString(it as? String) }

  fun get(ctx: Context, key: String): AlarmTrigger? =
    AlarmTrigger.fromJsonString(prefs(ctx).getString(key, null))

  fun put(ctx: Context, t: AlarmTrigger) {
    prefs(ctx).edit().putString(t.key, t.toJson().toString()).commit()
  }

  fun remove(ctx: Context, key: String) {
    prefs(ctx).edit().remove(key).commit()
  }
}
