import AsyncStorage from '@react-native-async-storage/async-storage';
import { PermissionsAndroid, Platform } from 'react-native';
import {
  getAlarmClock,
  type AlarmStatus,
  type AlarmTrigger,
} from '../../modules/alarm-clock';

/* ------------------------------------------------------------------ */
/* Config                                                              */
/* ------------------------------------------------------------------ */

// Custom ringtone: drop a file at assets/sounds/alarm.mp3 and rebuild (see the
// config plugin). Otherwise the phone's default alarm tone is used.
export const SNOOZE_MINUTES = 9;

const STORAGE_KEY = 'alarms.v1';
// Set once alarms created by the old notification-based version are re-armed natively.
const NATIVE_MIGRATION_KEY = 'alarms.native.v1';

/* ------------------------------------------------------------------ */
/* Types + helpers                                                     */
/* ------------------------------------------------------------------ */

export type Alarm = {
  id: string;
  hour: number; // 0-23
  minute: number; // 0-59
  label: string;
  days: number[]; // 0 = Sunday ... 6 = Saturday. Empty = one-time alarm
  enabled: boolean;
  firesAt?: number; // one-time alarms only: the timestamp it is scheduled for
};

/** The alarm that is ringing right now. */
export type Ringing = AlarmTrigger;
export type PermissionStatus = AlarmStatus;

export function formatTime(hour: number, minute: number) {
  const suffix = hour >= 12 ? 'PM' : 'AM';
  const h = hour % 12 === 0 ? 12 : hour % 12;
  return `${h}:${String(minute).padStart(2, '0')} ${suffix}`;
}

function nextOccurrence(hour: number, minute: number, weekday?: number): number {
  const now = new Date();
  const d = new Date(now);
  d.setHours(hour, minute, 0, 0);
  if (weekday === undefined) {
    if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1);
  } else {
    d.setDate(d.getDate() + ((weekday - d.getDay() + 7) % 7));
    if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 7);
  }
  return d.getTime();
}

/* ------------------------------------------------------------------ */
/* Storage                                                             */
/* ------------------------------------------------------------------ */

export async function saveAlarms(alarms: Alarm[]) {
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(alarms));
}

export async function loadAlarms(): Promise<Alarm[]> {
  let list: Alarm[] = [];
  try {
    const raw = await AsyncStorage.getItem(STORAGE_KEY);
    list = raw ? JSON.parse(raw) : [];
  } catch {
    list = [];
  }
  // One-time alarms that already rang turn themselves off.
  const now = Date.now();
  let changed = false;
  const fixed = list.map((a) => {
    if (a.enabled && a.days.length === 0 && a.firesAt && a.firesAt <= now) {
      changed = true;
      return { ...a, enabled: false, firesAt: undefined };
    }
    return a;
  });
  if (changed) await saveAlarms(fixed);
  return fixed;
}

/**
 * Alarms saved by the previous (notification-based) version aren't known to the
 * native scheduler yet. Re-arm them once.
 */
export async function migrateToNativeScheduler(): Promise<Alarm[]> {
  const list = await loadAlarms();
  if (await AsyncStorage.getItem(NATIVE_MIGRATION_KEY)) return list;
  const next: Alarm[] = [];
  for (const alarm of list) next.push(await scheduleAlarm(alarm));
  await saveAlarms(next);
  await AsyncStorage.setItem(NATIVE_MIGRATION_KEY, '1');
  return next;
}

/* ------------------------------------------------------------------ */
/* Permissions                                                         */
/* ------------------------------------------------------------------ */

export async function ensurePermissions() {
  if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
    await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }
  return checkPermissions();
}

export function checkPermissions(): PermissionStatus {
  return getAlarmClock().getStatus();
}

export const openNotificationSettings = () => getAlarmClock().openNotificationSettings();
export const openExactAlarmSettings = () => getAlarmClock().openExactAlarmSettings();
export const openFullScreenSettings = () => getAlarmClock().openFullScreenSettings();
export const requestIgnoreBatteryOptimizations = () =>
  getAlarmClock().requestIgnoreBatteryOptimizations();

/* ------------------------------------------------------------------ */
/* Scheduling                                                          */
/* ------------------------------------------------------------------ */

const triggerKeys = (alarmId: string) => [
  ...[0, 1, 2, 3, 4, 5, 6].map((d) => `${alarmId}__${d}`),
  `${alarmId}__once`,
  `${alarmId}__snooze`,
];

export function cancelAlarmTriggers(alarmId: string) {
  getAlarmClock().cancel(triggerKeys(alarmId));
}

/** (Re)schedules one alarm. If it is disabled, it just cancels everything. */
export async function scheduleAlarm(alarm: Alarm): Promise<Alarm> {
  const clock = getAlarmClock();
  cancelAlarmTriggers(alarm.id);
  if (!alarm.enabled) return { ...alarm, firesAt: undefined };

  const base = {
    alarmId: alarm.id,
    label: alarm.label || 'Alarm',
    timeText: formatTime(alarm.hour, alarm.minute),
    hour: alarm.hour,
    minute: alarm.minute,
    snoozeMinutes: SNOOZE_MINUTES,
  };

  if (alarm.days.length === 0) {
    const ts = nextOccurrence(alarm.hour, alarm.minute);
    clock.schedule({ ...base, key: `${alarm.id}__once`, weekday: -1, timestamp: ts });
    return { ...alarm, firesAt: ts };
  }

  // One trigger per selected weekday; the native side re-arms each for the next week.
  for (const day of alarm.days) {
    clock.schedule({
      ...base,
      key: `${alarm.id}__${day}`,
      weekday: day,
      timestamp: nextOccurrence(alarm.hour, alarm.minute, day),
    });
  }
  return { ...alarm, firesAt: undefined };
}

export async function removeAlarm(alarmId: string) {
  cancelAlarmTriggers(alarmId);
}

/** Rings N seconds from now. For testing. */
export async function scheduleTestAlarm(seconds = 15) {
  const ts = Date.now() + seconds * 1000;
  const d = new Date(ts);
  getAlarmClock().schedule({
    key: 'test__once',
    alarmId: 'test',
    label: 'Test alarm',
    timeText: formatTime(d.getHours(), d.getMinutes()),
    weekday: -1,
    hour: d.getHours(),
    minute: d.getMinutes(),
    timestamp: ts,
    snoozeMinutes: SNOOZE_MINUTES,
  });
  return ts;
}

/* ------------------------------------------------------------------ */
/* Ringing: detect, stop, snooze                                       */
/* ------------------------------------------------------------------ */

export function getRingingAlarm(): Ringing | null {
  return getAlarmClock().getRinging();
}

export function stopAlarm() {
  getAlarmClock().stop();
}

export function snoozeAlarm() {
  getAlarmClock().snooze();
}
