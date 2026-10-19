package expo.modules.alarmclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that does the actual ringing, the same way stock clock apps do:
 * - holds a wake lock so the CPU can't go back to sleep mid-ring,
 * - plays a looping sound on the ALARM audio stream (not muted by silent / vibrate mode),
 * - vibrates,
 * - posts a full-screen-intent notification that turns the screen on and opens the app
 *   over the lock screen (or shows a heads-up with Stop / Snooze if the phone is in use).
 */
class AlarmService : Service() {

  companion object {
    private const val TAG = "AlarmClock"
    const val ACTION_START = "expo.modules.alarmclock.START"
    const val ACTION_STOP = "expo.modules.alarmclock.STOP"
    const val ACTION_SNOOZE = "expo.modules.alarmclock.SNOOZE"
    private const val EXTRA_TRIGGER = "expo.modules.alarmclock.TRIGGER"

    const val CHANNEL_ID = "alarm-ringing-v1"
    private const val NOTIFICATION_ID = 0x0A1A
    private const val AUTO_SILENCE_MS = 10 * 60_000L // stop ringing after 10 minutes, like stock clocks

    /** The alarm that is ringing right now, or null. Read by the JS module. */
    @Volatile
    var current: AlarmTrigger? = null
      private set

    fun start(ctx: Context, t: AlarmTrigger) {
      val intent = Intent(ctx, AlarmService::class.java)
        .setAction(ACTION_START)
        .putExtra(EXTRA_TRIGGER, t.toJson().toString())
      try {
        ContextCompat.startForegroundService(ctx, intent)
      } catch (e: Exception) {
        Log.e(TAG, "Could not start the alarm service", e)
      }
    }

    fun stop(ctx: Context) = send(ctx, ACTION_STOP)

    fun snooze(ctx: Context) = send(ctx, ACTION_SNOOZE)

    private fun send(ctx: Context, action: String) {
      if (current == null) return
      try {
        ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
      } catch (e: Exception) {
        Log.e(TAG, "Could not send $action to the alarm service", e)
      }
    }

    fun ensureChannel(ctx: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val nm = ctx.getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(CHANNEL_ID) != null) return
      val channel = NotificationChannel(CHANNEL_ID, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Shown while an alarm is ringing"
        setSound(null, null) // the service plays the sound itself, on the alarm stream
        enableVibration(false) // the service vibrates itself
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
      }
      nm.createNotificationChannel(channel)
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private val autoSilence = Runnable { finish() }
  private var player: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var focusRequest: AudioFocusRequest? = null

  private val alarmAudio: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val t = AlarmTrigger.fromJsonString(intent.getStringExtra(EXTRA_TRIGGER))
        if (t != null) startRinging(t) else finish()
      }
      ACTION_SNOOZE -> {
        current?.let { AlarmScheduler.snooze(this, it) }
        finish()
      }
      else -> finish()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    releaseResources()
    current = null
    super.onDestroy()
  }

  /* ------------------------------------------------------------------ */

  private fun startRinging(t: AlarmTrigger) {
    releaseResources() // another alarm may already be ringing; replace it
    current = t
    ensureChannel(this)
    goForeground(buildNotification(t))
    acquireWakeLock()
    requestAudioFocus()
    startSound()
    startVibration()
    handler.postDelayed(autoSilence, AUTO_SILENCE_MS)
  }

  private fun finish() {
    releaseResources()
    current = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun releaseResources() {
    handler.removeCallbacks(autoSilence)
    player?.let { p -> runCatching { p.stop() }; p.release() }
    player = null
    vibrator?.cancel()
    vibrator = null
    abandonAudioFocus()
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
  }

  private fun goForeground(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      try {
        // Android 14+: the type reserved for apps that hold the exact-alarm permission.
        ServiceCompat.startForeground(
          this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        )
        return
      } catch (e: Exception) {
        Log.w(TAG, "systemExempted foreground service refused, using mediaPlayback", e)
      }
    }
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun buildNotification(t: AlarmTrigger): Notification {
    val open = PendingIntent.getActivity(
      this, 1, AlarmScheduler.launchIntent(this),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(smallIcon())
      .setContentTitle(t.label.ifBlank { "Alarm" })
      .setContentText(t.timeText)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setAutoCancel(false)
      .setShowWhen(false)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setContentIntent(open)
      // Screen off / locked: turns the screen on and opens the app over the lock screen.
      // Phone in use: shows as a heads-up notification instead.
      .setFullScreenIntent(open, true)
      .addAction(0, "Snooze ${t.snoozeMinutes} min", serviceIntent(ACTION_SNOOZE, 2))
      .addAction(0, "Stop", serviceIntent(ACTION_STOP, 3))
      .build()
  }

  private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
    PendingIntent.getService(
      this, requestCode, Intent(this, AlarmService::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

  private fun smallIcon(): Int {
    val custom = resources.getIdentifier("notification_icon", "drawable", packageName)
    return if (custom != 0) custom else android.R.drawable.ic_lock_idle_alarm
  }

  /* ------------------------------------------------------------------ */

  private fun acquireWakeLock() {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClock:ringing").apply {
      setReferenceCounted(false)
      acquire(AUTO_SILENCE_MS + 60_000L)
    }
  }

  /** res/raw/alarm (copied in by the config plugin) if present, else the phone's alarm tone. */
  private fun soundCandidates(): List<Uri> {
    val list = mutableListOf<Uri>()
    val raw = resources.getIdentifier("alarm", "raw", packageName)
    if (raw != 0) list += Uri.parse("android.resource://$packageName/$raw")
    listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
      .mapNotNullTo(list) { RingtoneManager.getDefaultUri(it) }
    return list
  }

  private fun startSound() {
    for (uri in soundCandidates()) {
      val mp = MediaPlayer()
      try {
        mp.setAudioAttributes(alarmAudio)
        mp.setDataSource(this, uri)
        mp.isLooping = true
        mp.prepare()
        mp.start()
        player = mp
        return
      } catch (e: Exception) {
        Log.w(TAG, "Could not play $uri", e)
        mp.release()
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun startVibration() {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
      getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (!v.hasVibrator()) return
    val pattern = longArrayOf(0, 800, 800)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      v.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAudio)
    } else {
      v.vibrate(pattern, 0)
    }
    vibrator = v
  }

  /** Pauses music / videos while the alarm rings. */
  private fun requestAudioFocus() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      .setAudioAttributes(alarmAudio)
      .build()
    am.requestAudioFocus(req)
    focusRequest = req
  }

  private fun abandonAudioFocus() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val req = focusRequest ?: return
    (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
    focusRequest = null
  }
}
