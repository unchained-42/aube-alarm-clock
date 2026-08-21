# Aube: Science-Based Smart Alarm Clock for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Languages](https://img.shields.io/badge/Languages-10-blue)](#languages)

<p align="center">
  <img src="docs/screenshots/banner.png" alt="Aube screenshots: onboarding, home screen, QR dismiss code, wake time editor" width="100%">
</p>

**Aube** is a free, open-source, science-based smart alarm clock for Android. No snooze button, a consistent wake time, gradual sunrise (dawn) simulation, light-sleep-aware wake timing, and a physical QR-code dismiss step. Each choice is backed by sleep research, not just UX taste.

> ⚠️ Wellness tool, not a medical device. Not intended to diagnose, treat, or prevent any sleep disorder.

## Features

- 🌅 Dawn simulation: screen ramps from black to full brightness before the alarm sounds
- 🔊 Volume ramp synced to the same window, instead of full blast on trigger
- 🚫 No snooze button, anywhere, ever
- 🔒 Sleep-duration lock: set how much sleep you want during onboarding, and the alarm switch/wake time can't be touched once you're inside that window before the deadline
- 📆 One consistent wake-by time per day (optional separate weekday/weekend)
- 🛌 Accelerometer-based light-sleep detection: can wake you up to 45 min early if you're already stirring
- 📱 QR/barcode dismiss: scan a code placed somewhere you have to get out of bed to reach
- 🔐 Hard to bypass: screen pinning, full-screen overlay, volume-key lock, foreground service independent of the UI, boot-resume
- 🆘 Emergency fallback: long randomized-string challenge if you don't have the code, restarts from scratch on the first wrong character
- 📵 Accountability contacts: texts someone you trust if the phone is powered off through the deadline, the one loophole no on-device fix can close
- ⏰ Bounded auto-stop: 1h ring, then pulses for a few hours, then gives up instead of running forever
- ☀️ Optional post-wake reminders (water, light, breakfast)
- 🌍 10 languages, follows system locale
- 🆓 Free, open source, no ads, no tracking, no account

## The Science

| Design choice | Why |
|---|---|
| **No snooze** | Repeated fragmented awakenings extend sleep inertia (grogginess/impaired cognition after waking). [PMC study](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9804954/) · [Sleep Doctor](https://sleepdoctor.com/pages/health/sleep-inertia) |
| **Same wake time daily** | Sleep regularity correlates with better mental/physical/cognitive health outcomes, often more than total duration. [Systematic review](https://www.sciencedirect.com/science/article/abs/pii/S108707922500156X) · [Review](https://cdnsciencepub.com/doi/10.1139/apnm-2020-0032) · [PMC study](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5468315/) |
| **Dawn simulation** | Gradual light exposure before wake time signals reduced melatonin, easing the transition; shown to reduce time-to-wakefulness vs. an abrupt alarm. [Wikipedia](https://en.wikipedia.org/wiki/Dawn_simulation) · [Sleep Review](https://sleepreviewmag.com/sleep-treatments/therapy-devices/light-therapy/light-dawn-simulation/) · [Study](https://www.researchgate.net/publication/260130874_Effects_of_dawn_simulation_on_markers_of_sleep_inertia_and_post-waking_performance_in_humans) |
| **Light-sleep wake window** | Waking from deep/slow-wave sleep produces more grogginess than waking from light sleep. Same principle behind wearable "smart alarms." |
| **QR code, not a button** | A groggy brain can dismiss a notification on autopilot; getting up and scanning a code forces real motor planning. Behavioral reasoning, not a specific study. |
| **Sleep-duration lock** | The alarm's own settings are as bypassable as anything else at 3am, half-asleep. Locking edits during the sleep window you set removes that decision entirely, a commitment-device pattern rather than a specific sleep study. |

Snooze research is genuinely mixed: some studies find it near-neutral. Aube's position is to remove the trade-off, not relitigate it every morning.

### Why accountability contacts exist

Powering the phone off defeats every on-device protection at once: no code runs while a device is off, and no third-party app can force it back on or block the power menu (Android reserves both to the OS, and OEM alarm apps that briefly exploited accessibility-service workarounds for this had the technique closed as of Android 12). So the one loophole software can't close, Aube hands to a person instead: if the deadline passes with the phone off, or the alarm rings for hours with no dismiss, it texts your configured contacts. Off by default, requires the standard Android SMS permission, numbers stay on-device.

## How It Works

Kotlin + Jetpack Compose, no backend, everything on-device.

- **Scheduling**: `AlarmManager` exact alarms (tracking start + hard safety-net ring)
- **Sleep tracking**: foreground `Service`, accelerometer, movement scoring
- **Ringing**: separate foreground `Service` owns sound/vibration, independent of the UI activity
- **Hardening**: screen pinning, `SYSTEM_ALERT_WINDOW` overlay, volume-key interception, volume watchdog, boot-resume receiver
- **Accountability**: `SmsManager`, triggered from the boot receiver (missed deadline) and the ringing service (auto-stop after hours of no dismiss)
- **QR/barcode**: [ZXing](https://github.com/zxing/zxing) + CameraX, auto-torch for dark rooms
- **Storage**: Jetpack DataStore, local only

## Getting Started

```bash
git clone https://github.com/unchained-42/aube-alarm-clock.git
cd aube-alarm-clock
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 8.0 (API 26)+.

## Permissions

| Permission | Why |
|---|---|
| Exact alarms | Fires at the exact minute set |
| Notifications | Ringing + reminder notifications |
| Ignore battery optimizations | Prevents Android killing the tracking/ringing service overnight |
| Full-screen intent | Shows over the lock screen |
| Display over other apps | Powers the "still ringing" overlay |
| Camera | Scans the dismiss code |
| Accelerometer | Light-sleep detection, on-device only, never transmitted |
| SMS | Optional: texts your accountability contacts if the deadline is missed entirely |

## Languages

French · English · German · Spanish · Chinese (Simplified) · Portuguese · Russian · Japanese · Arabic · Hindi

## Contributing

Issues and PRs welcome. UX/strictness changes should come with reasoning, ideally a source.

## License

MIT, see [LICENSE](LICENSE).

---

<sub>Aube means "dawn" in French.</sub>
