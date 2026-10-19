const { withAndroidManifest, withDangerousMod } = require('expo/config-plugins');
const fs = require('fs');
const path = require('path');

// Optional custom ringtone. Put a file here (lowercase name, letters/digits/underscore)
// and rebuild. The native alarm service (modules/alarm-clock) plays res/raw/alarm on the
// alarm stream. If the file doesn't exist, the phone's default alarm tone is used.
const SOUND_FILE = 'assets/sounds/alarm.mp3';

// Lets the full-screen alarm launch MainActivity on top of the lock screen
// and wake the display.
function withLockScreenActivity(config) {
  return withAndroidManifest(config, (cfg) => {
    const app = cfg.modResults.manifest.application?.[0];
    const main = (app?.activity ?? []).find(
      (a) => a.$['android:name'] === '.MainActivity'
    );
    if (main) {
      main.$['android:showWhenLocked'] = 'true';
      main.$['android:turnScreenOn'] = 'true';
    } else {
      console.warn('[withAlarmAndroid] .MainActivity not found in AndroidManifest');
    }
    return cfg;
  });
}

// Copies the sound into android/app/src/main/res/raw so the alarm service can play it.
function withAlarmSound(config) {
  return withDangerousMod(config, [
    'android',
    async (cfg) => {
      const src = path.join(cfg.modRequest.projectRoot, SOUND_FILE);
      if (!fs.existsSync(src)) return cfg;
      const dir = path.join(
        cfg.modRequest.platformProjectRoot,
        'app/src/main/res/raw'
      );
      fs.mkdirSync(dir, { recursive: true });
      fs.copyFileSync(src, path.join(dir, path.basename(src).toLowerCase()));
      return cfg;
    },
  ]);
}

module.exports = (config) => withAlarmSound(withLockScreenActivity(config));
