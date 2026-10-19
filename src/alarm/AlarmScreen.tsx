import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  AppState,
  FlatList,
  PermissionsAndroid,
  Platform,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import {
  SNOOZE_MINUTES,
  checkPermissions,
  ensurePermissions,
  formatTime,
  getRingingAlarm,
  migrateToNativeScheduler,
  openExactAlarmSettings,
  openFullScreenSettings,
  openNotificationSettings,
  removeAlarm,
  requestIgnoreBatteryOptimizations,
  saveAlarms,
  scheduleAlarm,
  scheduleTestAlarm,
  snoozeAlarm,
  stopAlarm,
  loadAlarms,
  type Alarm,
  type PermissionStatus,
  type Ringing,
} from './core';

const DAY_LETTERS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const C = {
  bg: '#F2F4F7',
  card: '#FFFFFF',
  ink: '#101828',
  muted: '#667085',
  line: '#D0D5DD',
  accent: '#3B4CCA',
  accentSoft: '#E6E9FB',
  danger: '#B42318',
  warnBg: '#FFF4E5',
  warnInk: '#7A4100',
};

const ALL_OK: PermissionStatus = {
  notifications: true,
  exactAlarms: true,
  fullScreen: true,
  batteryUnrestricted: true,
};

function describeDays(days: number[]) {
  if (days.length === 0) return 'One time';
  if (days.length === 7) return 'Every day';
  return days.map((d) => DAY_NAMES[d]).join(', ');
}

function pickTime(initial: Date, onPicked: (hour: number, minute: number) => void) {
  DateTimePickerAndroid.open({
    value: initial,
    mode: 'time',
    is24Hour: false,
    onChange: (event, date) => {
      if (event.type === 'set' && date) onPicked(date.getHours(), date.getMinutes());
    },
  });
}

export default function AlarmScreen() {
  const [alarms, setAlarms] = useState<Alarm[]>([]);
  const [ringing, setRinging] = useState<Ringing | null>(null);
  const [perms, setPerms] = useState<PermissionStatus>(ALL_OK);
  const alarmsRef = useRef<Alarm[]>([]);

  const commit = useCallback(async (next: Alarm[]) => {
    alarmsRef.current = next;
    setAlarms(next);
    await saveAlarms(next);
  }, []);

  const pollRinging = useCallback(() => {
    const next = getRingingAlarm();
    setRinging((prev) => (prev?.key === next?.key ? prev : next));
  }, []);

  const refresh = useCallback(async () => {
    const list = await loadAlarms();
    alarmsRef.current = list;
    setAlarms(list);
    setPerms(checkPermissions());
    pollRinging();
  }, [pollRinging]);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    (async () => {
      pollRinging(); // show the ringing screen ASAP when launched over the lock screen
      await ensurePermissions();
      await migrateToNativeScheduler();
      await refresh();
    })();

    // The native service rings on its own; this just keeps the ringing view in sync
    // (alarm fired while open, or stopped from the notification).
    const timer = setInterval(pollRinging, 1000);
    const appState = AppState.addEventListener('change', (s) => {
      if (s === 'active') refresh();
    });
    return () => {
      clearInterval(timer);
      appState.remove();
    };
  }, [pollRinging, refresh]);

  /* ---------------- actions ---------------- */

  const patch = async (id: string, changes: Partial<Alarm>) => {
    try {
      const current = alarmsRef.current.find((a) => a.id === id);
      if (!current) return;
      const updated = await scheduleAlarm({ ...current, ...changes });
      await commit(alarmsRef.current.map((a) => (a.id === id ? updated : a)));
    } catch (e) {
      Alert.alert('Could not update the alarm', String(e));
    }
  };

  const addAlarm = () =>
    pickTime(new Date(), async (hour, minute) => {
      try {
        const alarm = await scheduleAlarm({
          id: String(Date.now()),
          hour,
          minute,
          label: 'Alarm',
          days: [],
          enabled: true,
        });
        await commit([...alarmsRef.current, alarm]);
      } catch (e) {
        Alert.alert('Could not add the alarm', String(e));
      }
    });

  const editTime = (alarm: Alarm) => {
    const d = new Date();
    d.setHours(alarm.hour, alarm.minute, 0, 0);
    pickTime(d, (hour, minute) => patch(alarm.id, { hour, minute, enabled: true }));
  };

  const toggleDay = (alarm: Alarm, day: number) =>
    patch(alarm.id, {
      days: alarm.days.includes(day)
        ? alarm.days.filter((d) => d !== day)
        : [...alarm.days, day].sort(),
    });

  const remove = async (alarm: Alarm) => {
    await removeAlarm(alarm.id);
    await commit(alarmsRef.current.filter((a) => a.id !== alarm.id));
  };

  const test = async () => {
    try {
      await scheduleTestAlarm(15);
      Alert.alert(
        'Test alarm set for 15 seconds',
        'Lock your phone now (power button) and wait. You can also swipe the app away first.'
      );
    } catch (e) {
      Alert.alert('Could not schedule the test alarm', String(e));
    }
  };

  const stop = async () => {
    stopAlarm();
    setRinging(null);
    await refresh();
  };

  const snooze = async () => {
    snoozeAlarm();
    setRinging(null);
    await refresh();
  };

  const fixNotifications = async () => {
    if (Number(Platform.Version) >= 33) {
      const r = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
      if (r === PermissionsAndroid.RESULTS.GRANTED) return refresh();
    }
    openNotificationSettings();
  };

  /* ---------------- render ---------------- */

  if (Platform.OS !== 'android') {
    return (
      <SafeAreaView style={s.screen}>
        <Text style={s.title}>Android only</Text>
        <Text style={s.sub}>
          This alarm uses Android's alarm clock, full-screen and alarm-sound features.
        </Text>
      </SafeAreaView>
    );
  }

  if (ringing) {
    return (
      <View style={s.ringScreen}>
        <SafeAreaView style={s.ringInner}>
          <View style={s.ringTop}>
            <Text style={s.ringLabel}>{ringing.label || 'Alarm'}</Text>
            <Text style={s.ringTime}>{ringing.timeText}</Text>
          </View>
          <View style={s.ringButtons}>
            <Pressable style={s.snoozeBtn} onPress={snooze}>
              <Text style={s.snoozeText}>Snooze {ringing.snoozeMinutes || SNOOZE_MINUTES} min</Text>
            </Pressable>
            <Pressable style={s.stopBtn} onPress={stop}>
              <Text style={s.stopText}>Stop</Text>
            </Pressable>
          </View>
        </SafeAreaView>
      </View>
    );
  }

  const sorted = [...alarms].sort((a, b) => a.hour * 60 + a.minute - (b.hour * 60 + b.minute));
  const needsSetup =
    !perms.notifications || !perms.exactAlarms || !perms.fullScreen || !perms.batteryUnrestricted;

  return (
    <SafeAreaView style={s.screen}>
      <View style={s.header}>
        <Text style={s.title}>Alarms</Text>
        <Pressable style={s.addBtn} onPress={addAlarm}>
          <Text style={s.addText}>Add alarm</Text>
        </Pressable>
      </View>

      {needsSetup && (
        <View style={s.warn}>
          <Text style={s.warnTitle}>Finish setup so alarms ring and wake the phone</Text>
          {!perms.notifications && (
            <Pressable style={s.warnBtn} onPress={fixNotifications}>
              <Text style={s.warnBtnText}>Allow notifications</Text>
            </Pressable>
          )}
          {!perms.exactAlarms && (
            <Pressable style={s.warnBtn} onPress={openExactAlarmSettings}>
              <Text style={s.warnBtnText}>Allow alarms and reminders</Text>
            </Pressable>
          )}
          {!perms.fullScreen && (
            <Pressable style={s.warnBtn} onPress={openFullScreenSettings}>
              <Text style={s.warnBtnText}>Allow full-screen alarms</Text>
            </Pressable>
          )}
          {!perms.batteryUnrestricted && (
            <Pressable style={s.warnBtn} onPress={requestIgnoreBatteryOptimizations}>
              <Text style={s.warnBtnText}>Don't optimize battery for this app</Text>
            </Pressable>
          )}
        </View>
      )}

      <FlatList
        data={sorted}
        keyExtractor={(a) => a.id}
        contentContainerStyle={s.list}
        ListEmptyComponent={
          <Text style={s.empty}>No alarms yet. Tap Add alarm to set a time.</Text>
        }
        renderItem={({ item }) => (
          <View style={s.card}>
            <View style={s.rowTop}>
              <Pressable onPress={() => editTime(item)}>
                <Text style={[s.time, !item.enabled && s.dim]}>
                  {formatTime(item.hour, item.minute)}
                </Text>
              </Pressable>
              <Switch
                value={item.enabled}
                onValueChange={(v) => patch(item.id, { enabled: v })}
                trackColor={{ false: C.line, true: C.accent }}
                thumbColor="#FFFFFF"
              />
            </View>
            <View style={s.days}>
              {DAY_LETTERS.map((letter, day) => {
                const on = item.days.includes(day);
                return (
                  <Pressable
                    key={day}
                    onPress={() => toggleDay(item, day)}
                    style={[s.day, on && s.dayOn]}
                  >
                    <Text style={[s.dayText, on && s.dayTextOn]}>{letter}</Text>
                  </Pressable>
                );
              })}
            </View>
            <View style={s.rowBottom}>
              <Text style={s.sub}>{describeDays(item.days)}</Text>
              <Pressable onPress={() => remove(item)} hitSlop={10}>
                <Text style={s.delete}>Delete</Text>
              </Pressable>
            </View>
          </View>
        )}
        ListFooterComponent={
          <View style={s.footer}>
            <Pressable style={s.testBtn} onPress={test}>
              <Text style={s.testText}>Test: ring in 15 seconds</Text>
            </Pressable>
          </View>
        }
      />
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg, paddingHorizontal: 16 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
  },
  title: { fontSize: 30, fontWeight: '700', color: C.ink },
  addBtn: {
    backgroundColor: C.accent,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 999,
  },
  addText: { color: '#FFFFFF', fontWeight: '600', fontSize: 15 },
  list: { paddingBottom: 40, gap: 12 },
  empty: { color: C.muted, fontSize: 16, textAlign: 'center', marginTop: 48 },

  card: { backgroundColor: C.card, borderRadius: 16, padding: 16, gap: 12 },
  rowTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  time: { fontSize: 40, fontWeight: '300', color: C.ink, fontVariant: ['tabular-nums'] },
  dim: { color: C.line },
  days: { flexDirection: 'row', justifyContent: 'space-between' },
  day: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: C.bg,
  },
  dayOn: { backgroundColor: C.accent },
  dayText: { color: C.muted, fontWeight: '600' },
  dayTextOn: { color: '#FFFFFF' },
  rowBottom: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  sub: { color: C.muted, fontSize: 14 },
  delete: { color: C.danger, fontSize: 14, fontWeight: '600' },

  warn: { backgroundColor: C.warnBg, borderRadius: 12, padding: 14, gap: 10, marginBottom: 12 },
  warnTitle: { color: C.warnInk, fontWeight: '600' },
  warnBtn: {
    backgroundColor: C.warnInk,
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
  warnBtnText: { color: '#FFFFFF', fontWeight: '600' },

  footer: { marginTop: 8, gap: 16, alignItems: 'center' },
  testBtn: {
    borderWidth: 1.5,
    borderColor: C.accent,
    borderRadius: 999,
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  testText: { color: C.accent, fontWeight: '600' },

  // Ringing view: the one bold moment in the app.
  ringScreen: { flex: 1, backgroundColor: C.accent },
  ringInner: { flex: 1, justifyContent: 'space-between', padding: 24 },
  ringTop: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 8 },
  ringLabel: { color: '#DDE1FF', fontSize: 22, fontWeight: '500' },
  ringTime: {
    color: '#FFFFFF',
    fontSize: 64,
    fontWeight: '200',
    fontVariant: ['tabular-nums'],
    textAlign: 'center',
  },
  ringButtons: { gap: 12 },
  snoozeBtn: {
    borderWidth: 2,
    borderColor: '#FFFFFF',
    borderRadius: 16,
    paddingVertical: 18,
    alignItems: 'center',
  },
  snoozeText: { color: '#FFFFFF', fontSize: 18, fontWeight: '600' },
  stopBtn: {
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    paddingVertical: 22,
    alignItems: 'center',
  },
  stopText: { color: C.accent, fontSize: 22, fontWeight: '700' },
});
