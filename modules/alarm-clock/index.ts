import { requireOptionalNativeModule } from 'expo';

export type AlarmTrigger = {
  /** Unique per trigger, e.g. `${alarmId}__once`, `${alarmId}__3`, `${alarmId}__snooze`. */
  key: string;
  alarmId: string;
  label: string;
  timeText: string;
  /** 0 = Sunday ... 6 = Saturday for weekly triggers, -1 for one-time. */
  weekday: number;
  hour: number;
  minute: number;
  /** Epoch ms of the next ring. */
  timestamp: number;
  snoozeMinutes: number;
};

export type AlarmStatus = {
  /** Notifications allowed (needed for the full-screen alarm and Stop/Snooze buttons). */
  notifications: boolean;
  /** "Alarms & reminders" (exact alarms). */
  exactAlarms: boolean;
  /** Android 14+: allowed to show full-screen over the lock screen. */
  fullScreen: boolean;
  /** Excluded from battery optimization (protects against aggressive OEM battery savers). */
  batteryUnrestricted: boolean;
};

type AlarmClockNative = {
  schedule(trigger: AlarmTrigger): void;
  cancel(keys: string[]): void;
  getRinging(): AlarmTrigger | null;
  stop(): void;
  snooze(): void;
  getStatus(): AlarmStatus;
  openNotificationSettings(): void;
  openExactAlarmSettings(): void;
  openFullScreenSettings(): void;
  requestIgnoreBatteryOptimizations(): void;
};

const native = requireOptionalNativeModule<AlarmClockNative>('AlarmClock');

/** The native module. Throws on iOS / web / Expo Go, where it doesn't exist. */
export function getAlarmClock(): AlarmClockNative {
  if (!native) {
    throw new Error(
      'AlarmClock native module is missing. It is Android-only and needs a development build (not Expo Go).'
    );
  }
  return native;
}