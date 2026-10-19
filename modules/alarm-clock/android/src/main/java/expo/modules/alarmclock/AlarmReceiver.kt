package expo.modules.alarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when an alarm goes off and starts the ringing service. Runs natively, so it
 * works with the app swiped away, the screen off, or the phone in Doze.
 * Also re-arms all alarms after reboot, app update and clock / time-zone changes.
 */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    when (intent.action) {
      AlarmScheduler.ACTION_FIRE -> {
        val key = intent.getStringExtra(AlarmScheduler.EXTRA_KEY) ?: return
        val trigger = AlarmScheduler.onFired(ctx, key) ?: return
        AlarmService.start(ctx, trigger)
      }
      else -> AlarmScheduler.rescheduleAll(ctx)
    }
  }
}
