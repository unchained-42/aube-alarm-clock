# Aube — Science-Based Smart Alarm Clock for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Languages](https://img.shields.io/badge/Languages-10-blue)](#supported-languages)

**Aube** is a free, open-source, science-based smart alarm clock for Android. It's built around a simple idea: most alarm clocks are designed to make noise, not to wake you up well. Aube instead applies well-documented sleep science — no snooze button, a consistent wake time, a gradual sunrise (dawn) simulation, light-sleep-aware wake timing, and a physical QR-code dismiss step — to make waking up less brutal and much harder to sleep through.

If you're searching for a **sunrise alarm clock app**, a **no-snooze alarm for Android**, a **circadian-rhythm-friendly wake up app**, a **smart alarm that detects light sleep**, or an **open-source QR-code alarm clock that's nearly impossible to sleep through**, this is that project.

> ⚠️ Aube is a personal wellness tool, not a certified medical device. It is not intended to diagnose, treat, cure, or prevent any sleep disorder. If you struggle with chronic sleep problems, please talk to a doctor or a licensed sleep specialist.

---

## Table of Contents

- [Why Aube Exists](#why-aube-exists)
- [Features](#features)
- [The Science Behind Aube](#the-science-behind-aube)
  - [1. No Snooze Button](#1-no-snooze-button)
  - [2. The Same Wake Time, Every Day](#2-the-same-wake-time-every-day)
  - [3. Dawn Simulation (Gradual Sunrise Light)](#3-dawn-simulation-gradual-sunrise-light)
  - [4. Light-Sleep-Aware Wake Timing](#4-light-sleep-aware-wake-timing)
  - [5. A QR Code to Scan, Not a Button to Tap](#5-a-qr-code-to-scan-not-a-button-to-tap)
- [How It Works](#how-it-works)
- [Getting Started](#getting-started)
- [Permissions Explained](#permissions-explained)
- [Supported Languages](#supported-languages)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Sources & Further Reading](#sources--further-reading)

---

## Why Aube Exists

The average smartphone alarm clock hasn't changed in twenty years: it plays a loud sound at a fixed time, and offers a snooze button that trades a few extra minutes of low-quality sleep for a groggier, more fragmented wake-up. Sleep science has a lot to say about why that's a bad trade — and about what actually helps. Aube tries to put that research directly into the product, instead of just adding another ringtone picker.

## Features

- 🌅 **Dawn simulation** — the screen ramps from near-black to full daylight brightness before the alarm sounds, simulating a natural sunrise.
- 🔊 **Synchronized volume ramp** — sound climbs from barely audible to full volume across the same window as the light, instead of blasting at full volume from the first second.
- 🚫 **No snooze, ever** — there is no snooze button anywhere in the ringing flow. Once you're in the wake-up window, there's no low-effort way to buy a few more minutes.
- 📆 **Consistent daily wake time** — one wake-by time per day (with an optional earlier "light sleep" window), designed around circadian regularity research rather than a different alarm for every day of the week.
- 🛌 **Light-sleep detection** — an accelerometer-based movement tracker can wake you a little earlier, inside a bounded window, if it detects you're already stirring in light sleep rather than deep sleep.
- 📱 **QR-code dismiss** — the alarm only stops when you scan a physical QR/barcode you've placed somewhere you have to get out of bed to reach. Any barcode works, or use the one Aube generates for you.
- 🔐 **Genuinely hard to bypass** — screen pinning, a full-screen "still ringing" overlay, hardware volume-key blocking, a foreground service independent of the ringing activity, and boot-resume logic all combine to close the usual escape hatches (swiping the alarm away, muting it, killing the app from recents, even rebooting the phone).
- 🆘 **Emergency fallback** — no phone/no code? A deliberately long, randomly-generated character challenge lets you dismiss the alarm without a QR code — tedious on purpose, so it's a genuine last resort, not a shortcut.
- ⏰ **Bounded auto-stop** — if nobody's around to dismiss it, the alarm rings for an hour, then pulses on and off for a few more hours, then gives up for the day instead of ringing forever and draining the battery.
- ☀️ **Post-wake routine reminders** — optional, fully customizable nudges (water, natural light, breakfast) after you're up.
- 🌍 **10 languages** out of the box, automatically following your phone's system language.
- 🆓 **100% free and open source**, no ads, no tracking, no account required.

## The Science Behind Aube

Every one of Aube's "annoying" design choices maps to a specific, documented finding in sleep research. Here's the reasoning, with sources.

### 1. No Snooze Button

Waking up, dozing off again, and being woken a second time fragments the last stretch of sleep and can extend **sleep inertia** — the grogginess, disorientation, and impaired cognitive performance that follows waking. A 2023 study on snooze-alarm use found that the sleep stage at the moment of awakening and the way you're woken are both factors that influence how intense and how long sleep inertia lasts. The evidence on snoozing itself is genuinely mixed — some research finds it roughly neutral for mood and cortisol response, while a lot of habitual snoozers report using it mainly to manage anxiety about oversleeping rather than because it measurably helps. Aube's position is to remove the trade-off entirely: no snooze button means no fragmented, repeated awakenings to begin with.

### 2. The Same Wake Time, Every Day

A large and growing body of research on **sleep regularity** finds that a consistent wake time is associated with better mental, physical, and cognitive health outcomes — in some studies, more strongly than total sleep duration. Irregular sleep timing has been linked to higher depression and anxiety symptoms, cardiometabolic risk, and even long-term outcomes like dementia risk and all-cause mortality in large cohort studies. Aube is built around one wake-by time per day (with separate weekday/weekend schedules if you want them) rather than a different alarm for every day of the week.

### 3. Dawn Simulation (Gradual Sunrise Light)

Light is the strongest cue your circadian clock listens to, and it acts on your body well before you consciously notice it: gradually increasing light exposure before wake time is understood to signal a reduction in melatonin, easing the transition out of sleep. Research on **dawn simulation** — a light that ramps up over 30–90 minutes before wake time — has found it reduces time-to-full-wakefulness and improves mood scores compared to an abrupt audible alarm, and dawn-simulation devices have long been used as a light-therapy tool. Aube simulates this using your phone's own screen, ramping brightness (and volume, in sync) across a configurable window before the alarm.

### 4. Light-Sleep-Aware Wake Timing

Sleep cycles through light and deep stages roughly every 90 minutes. Being woken from **deep, slow-wave sleep** tends to produce far more grogginess and can measurably impair cognitive performance for a while afterward, compared to being woken during **light sleep**. This is the same principle "smart alarm" wearables and sleep-tracking apps use. Aube uses your phone's accelerometer (no wearable required) to look for a stirring/movement signal inside a bounded early window, and — only then — can start the wake-up sequence up to 45 minutes earlier than the hard deadline, on the theory that you're already surfacing from light sleep on your own.

### 5. A QR Code to Scan, Not a Button to Tap

This part is less about a specific published study and more about a well-known behavioral principle: a groggy, half-asleep brain running on autopilot can dismiss a notification or slide a button without ever becoming meaningfully awake. Requiring a genuine physical action — getting up, walking to a QR code stuck somewhere across the room, and scanning it with the camera — forces enough motor planning and physical movement to break that autopilot loop. It's the same reasoning behind "get out of bed" alarm apps in general, just implemented with a plain barcode instead of a proprietary gadget.

## How It Works

Aube is a native Android app written in **Kotlin** with **Jetpack Compose**, with no third-party backend — everything runs locally on your device.

- **Scheduling**: `AlarmManager` exact alarms schedule both a tracking-service start time and a hard safety-net ring time for the day's wake window.
- **Sleep tracking**: a foreground `Service` samples the accelerometer and scores movement to detect light-sleep stirring, deciding when to start the dawn sequence.
- **Ringing**: a dedicated foreground `Service` owns sound and vibration independently of the on-screen activity, so closing the alarm screen from the recents list can't silently kill the alarm.
- **Hardening**: screen pinning (`startLockTask`), a `SYSTEM_ALERT_WINDOW` "still ringing" overlay, hardware volume-key interception, an `AudioManager`-level volume watchdog, and a boot-completed receiver that resumes ringing if the phone reboots while the alarm is active — all closing common ways an alarm app can be silently defeated.
- **QR/barcode decoding**: [ZXing](https://github.com/zxing/zxing) for both generating and scanning the dismiss code, with a CameraX-based scanner and auto-torch for scanning in the dark.
- **Storage**: Jetpack DataStore Preferences — no cloud sync, no account, nothing leaves your device.

## Getting Started

Aube isn't published on the Play Store yet — build it from source:

```bash
git clone https://github.com/unchained-42/aube-alarm-clock.git
cd aube-alarm-clock
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android Studio (or the command-line SDK tools) and a device or emulator running **Android 8.0 (API 26) or newer**.

## Permissions Explained

Aube asks for several permissions that are easy to misread as excessive for "just an alarm app" — here's why each one exists:

| Permission | Why Aube needs it |
|---|---|
| Exact alarms | So the alarm fires at the exact minute you set, not "sometime around then." |
| Notifications | To show the ringing/full-screen alarm notification and post-wake reminders. |
| Ignore battery optimizations | So Android doesn't kill the sleep-tracking or ringing service overnight. |
| Full-screen intent | So the alarm can wake the screen and show over the lock screen, like any alarm clock. |
| Display over other apps | Powers the "still ringing" overlay that stays visible even if you manage to open another app while it's ringing. |
| Camera | To scan the QR/barcode dismiss code. |
| Body sensors / activity (accelerometer) | To detect light-sleep movement for the smart early-wake feature. Purely on-device, never transmitted anywhere. |

## Supported Languages

Aube automatically follows your phone's system language, or you can pin one manually in Settings:

French · English · German · Spanish · Chinese (Simplified) · Portuguese · Russian · Japanese · Arabic · Hindi

## Roadmap

- [ ] Play Store release
- [ ] Wear OS companion for light-sleep detection without keeping the phone on the nightstand
- [ ] More granular per-day schedules
- [ ] Additional languages

Contributions and suggestions for the roadmap are welcome — see below.

## Contributing

Issues and pull requests are welcome. If you're proposing a UX or "how strict should this be" change, it helps to explain the reasoning (ideally with a source) the same way the rest of this README tries to — Aube's whole design philosophy is "annoying on purpose, but never arbitrarily."

## License

MIT — see [LICENSE](LICENSE). Free to use, modify, and redistribute.

## Sources & Further Reading

- [Effects of using a snooze alarm on sleep inertia after morning awakening](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9804954/) — PMC / peer-reviewed
- [Sleep Inertia: What to Do About Morning Grogginess](https://sleepdoctor.com/pages/health/sleep-inertia) — Sleep Doctor
- [Sleep regularity as an important component of sleep hygiene: a systematic review](https://www.sciencedirect.com/science/article/abs/pii/S108707922500156X) — ScienceDirect
- [Sleep timing, sleep consistency, and health in adults: a systematic review](https://cdnsciencepub.com/doi/10.1139/apnm-2020-0032) — Applied Physiology, Nutrition, and Metabolism
- [Irregular sleep/wake patterns are associated with poorer academic performance and delayed circadian and sleep/wake timing](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5468315/) — PMC / peer-reviewed
- [Dawn simulation](https://en.wikipedia.org/wiki/Dawn_simulation) — overview and history of dawn-simulation light therapy
- [Shedding Light on Dawn Simulation](https://sleepreviewmag.com/sleep-treatments/therapy-devices/light-therapy/light-dawn-simulation/) — Sleep Review
- [Effects of dawn simulation on markers of sleep inertia and post-waking performance in humans](https://www.researchgate.net/publication/260130874_Effects_of_dawn_simulation_on_markers_of_sleep_inertia_and_post-waking_performance_in_humans) — ResearchGate

---

<sub>Aube means "dawn" in French.</sub>
