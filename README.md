# Ambient Volume — Adaptive Volume Engine

> Automatically adjusts your Android media volume based on real-time ambient noise levels, so you always hear clearly without ever touching the volume buttons.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
![Platform](https://img.shields.io/badge/Platform-Android%209%2B-brightgreen.svg)
![Version](https://img.shields.io/badge/Version-1.10.0-orange.svg)
![Build](https://img.shields.io/badge/Build-Kotlin%20DSL-purple.svg)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [How It Works](#how-it-works)
- [Usage](#usage)
- [Configuration](#configuration)
- [Permissions](#permissions)
- [OEM Compatibility](#oem-compatibility)
- [Architecture](#architecture)
- [Building from Source](#building-from-source)
- [Version History](#version-history)
- [License](#license)

---

## Overview

**Ambient Volume** is a privacy-conscious Android app that listens to your environment and intelligently scales your media volume to match. Whether you are in a quiet library, commuting on a noisy bus, or walking down a busy street, Ambient Volume keeps your audio at the right level automatically.

The engine runs as a battery-optimised foreground service using a **duty-cycled microphone sampling** loop — it records for exactly 1 second, processes the RMS level, then sleeps for 5 seconds before the next cycle. This design avoids the continuous CPU drain of traditional ambient-monitoring apps.

---

## Features

### Adaptive Volume Engine
- Continuously monitors ambient noise using the device microphone.
- Maps dB levels (50 dB to 30% volume, 90 dB to 100% volume) to system media volume.
- Applies a configurable **dB offset** so your preferred loudness profile is always respected.
- Supports wired headphones and **Bluetooth A2DP/SCO** output (with 500 ms write buffer for wireless latency).

### Intelligent Silence Gating
- Uses `AudioManager.isMusicActive()` to skip microphone activation entirely when nothing is playing.
- Enters a deeper 20-second sleep between checks when no media is active, preserving battery.

### Volume Profiles

| Profile | dB Offset | Max Volume Cap | Best For |
|---|---|---|---|
| **Library** | +5 dB | 40% | Quiet indoor spaces |
| **Standard** | +10 dB | 80% | General everyday use |
| **Commute** | +20 dB | 100% | Trains, buses, busy streets |
| **Custom (Auto-Learned)** | User-defined | 100% | Personalised preference |

### Auto-Learning Custom Profile
- Detects when the user manually changes volume while the engine is running.
- Automatically back-calculates and saves the implied dB offset as a **Custom profile**.
- Auto-override lock releases when the ambient environment shifts by 5 dB or more.

### Smoothing and Hysteresis
- Rolling mean filter (configurable 5–20 seconds) smooths out transient noise spikes.
- A **3 dB hysteresis band** prevents unnecessary micro-adjustments.
- A **1.5-second peak filter** discards brief loud events (e.g. a single clap or door slam).

### 60/60 Hearing Safety Rule
- Tracks cumulative time spent at above 60% volume per day (persisted across reboots via DataStore).
- Displays a dismissible warning card in the UI after 60 minutes of high-volume listening.
- Resets automatically each calendar day. Can be toggled off in Settings.

### Battery-Optimised Design
- **Duty-cycled sampling:** mic open for 1,000 ms then immediate hardware release then 5,000 ms sleep.
- **Playback gating:** microphone never opens when music is inactive.
- **Low-overhead audio pipeline:** 16 kHz mono PCM-16 (auto-fallback to 8 kHz).
- **Step-size throttle:** volume only changes when the target differs by 1 or more full system volume steps.
- **Debug stripping:** all visualiser data, RMS logs, and diagnostic streams are wrapped in `BuildConfig.DEBUG` guards and are absent from release APKs.

### Boot and Self-Healing Auto-Start
- `BootReceiver` listens for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` to restart the engine automatically after a reboot or app update.
- Service self-healing on resume: if the OS kills the service silently, the app detects this on `onResume` and restarts it without user interaction.
- `onTaskRemoved` schedules an `AlarmManager` restart 1 second after the app is swiped away (critical for aggressive OEM task-killers like MIUI).

### Haptic Feedback
- Optional subtle 10 ms haptic tick on every automated volume step.
- Uses `VibrationEffect` API on Android 8.0+ with a legacy `vibrate()` fallback for older devices.

### Acoustic Echo Cancellation (AEC)
- Attaches hardware `AcousticEchoCanceler` to the `AudioRecord` session when available.
- Falls back to a software RMS reduction model calibrated for speaker-to-mic coupling when hardware AEC is absent.

### Three-Tab Material 3 UI
- **Monitor tab** — Real-time dB gauge and current volume display.
- **Engine tab** — Start/Stop control, active profile selector, and smoothing interval slider.
- **Settings tab** — Theme toggle (Material You dynamic colour on Android 12+), step size, haptic toggle, hearing safety, OEM battery optimisation deep-link, and debug log export.

---

## Requirements

| Item | Minimum |
|---|---|
| Android OS | **9.0 (API 28)** |
| Target SDK | 34 |
| Compile SDK | 36 |
| Architecture | ARM, ARM64, x86\_64 |

**Tested on:**
- Xiaomi Redmi Note 9 Pro — Android 12 / MIUI 14
- Doogee S Series — Android 9

---

## Installation

### Pre-built APK

1. Download the latest release APK from the [Releases](../../releases) page.
2. On your Android device, enable **Install from Unknown Sources** in *Settings > Security*.
3. Open the downloaded APK and follow the on-screen prompts.
4. Grant the requested permissions (microphone, notifications).
5. For MIUI / HyperOS: follow the in-app **OEM Setup** card to whitelist the app in Autostart settings.

### Building from Source

See [Building from Source](#building-from-source) below.

---

## How It Works

```
Microphone (1 s sample @ 16 kHz mono PCM-16)
    |
    v
RMS to dB conversion
    |
    v
Software / Hardware AEC   ---- (attenuates speaker playback bleed-through)
    |
    v
1.5 s Peak Filter         ---- (discards transient spikes)
    |
    v
Rolling Mean (5-20 s)     ---- (configurable smoothing window)
    |
    v
Hysteresis Gate (+-3 dB)  ---- (skips adjustment if within band)
    |
    v
Volume Mapping
  50 dB  ->  30% volume (floor)
  90 dB  -> 100% volume (profile cap)
  in-between -> linear interpolation
    |
    v
Profile dB Offset applied
    |
    v
Step-size throttle (>= 1 full system step required)
    |
    v
AudioManager.setStreamVolume()
    |
    +---> 5 s sleep -> next duty cycle
```

---

## Usage

1. **Open the app** and grant microphone and notification permissions when prompted.
2. On the **Engine** tab, tap **Start Engine**.
3. The persistent notification confirms the service is active.
4. Switch profiles from the **Engine** tab (Library / Standard / Commute / Custom).
5. Manually adjust volume at any time — the app will auto-learn your preference and switch to the **Custom** profile.
6. Stop the engine from either the **Engine** tab or the notification **Stop** button.

---

## Configuration

All settings are persisted across reboots using **Jetpack DataStore (Preferences)**.

| Setting | Default | Range | Description |
|---|---|---|---|
| Active Profile | Standard | Library / Standard / Commute / Custom | Volume sensitivity preset |
| Step Size | 3 dB | 3 / 5 / 10 dB | How aggressively volume adjusts per cycle |
| Smoothing Interval | 7 s | 5 / 7 / 10 / 15 / 20 s | Rolling mean window for noise averaging |
| Haptic Feedback | Off | On / Off | Vibrate on automated volume change |
| Hearing Safety | On | On / Off | 60/60 rule daily high-volume tracking |
| Dynamic Theme | On | On / Off | Follow system wallpaper colour (Android 12+) |

---

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Microphone access for ambient noise sampling |
| `MODIFY_AUDIO_SETTINGS` | Set system media stream volume |
| `BLUETOOTH_CONNECT` | Detect active Bluetooth audio output (Android 12+) |
| `BLUETOOTH_SCAN` | Scan for connected Bluetooth devices (Android 12+, never for location) |
| `FOREGROUND_SERVICE` | Required to run background volume engine |
| `FOREGROUND_SERVICE_MICROPHONE` | Foreground service type for mic access |
| `POST_NOTIFICATIONS` | Show persistent engine status notification |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Deep-link to battery optimisation settings |
| `VIBRATE` | Haptic feedback on volume step change |
| `RECEIVE_BOOT_COMPLETED` | Auto-start engine after device reboot |

> **Privacy note:** The microphone is only opened for 1-second sampling bursts while music is actively playing. Audio data is processed entirely on-device and is never recorded, stored, or transmitted.

---

## OEM Compatibility

Aggressive battery management on certain Android skins can kill background services. The app includes a built-in OEM Setup assistant that deep-links directly to the correct settings screen for your device:

| Manufacturer | Settings Target |
|---|---|
| **Xiaomi / MIUI / HyperOS** | Security > Autostart |
| **Samsung** | Device Care > Battery |
| **Huawei / Honor** | App Launch > Manage Manually |
| **OPPO / Realme** | Startup Manager |
| **Vivo** | Speed Up > Whitelist |

MediaTek DuraSpeed detection is also included for MTK-based devices.

---

## Architecture

```
com.nukie.ambientvolume
|-- MainActivity.kt              # Compose entry point; 3-tab navigation (Monitor, Engine, Settings)
|-- BootReceiver.kt              # BOOT_COMPLETED / MY_PACKAGE_REPLACED auto-start receiver
|-- service/
|   |-- VolumeControlService.kt  # Foreground service; duty-cycled audio loop; volume mapping engine
|   |-- ProfileManager.kt        # DataStore read/write for all user preferences and profiles
|   |-- AudioStateRepository.kt  # Singleton StateFlow hub; bridges service <-> Compose UI
|   |-- MovingAverage.kt         # Configurable rolling mean filter
|   +-- OEMManager.kt           # OEM detection and battery settings deep-link intents
|-- ui/
|   |-- PermissionsWrapper.kt    # Compose-based runtime permission request flow
|   +-- theme/                   # Material 3 colour tokens, typography, dynamic theming
+-- util/
    +-- DebugLogger.kt           # Debug-only file logger (stripped from release builds)
```

**Key libraries:**

- [Jetpack Compose](https://developer.android.com/compose) + Material 3
- [Jetpack DataStore (Preferences)](https://developer.android.com/topic/libraries/architecture/datastore)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [AndroidX Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle)

---

## Building from Source

### Prerequisites

- Android Studio Meerkat 2024.3.2 Feature Drop (or later)
- JDK 11
- Android SDK with API 36 platform tools installed

### Steps

```bash
# Clone the repository
git clone https://github.com/nukie-git/AmbientVolumeApp.git
cd AmbientVolumeApp

# Build a debug APK
./gradlew assembleDebug

# Build a release APK (uses debug signing config by default)
./gradlew assembleRelease
```

The output APK will be located at:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

> **Note for MIUI users:** After installing a debug build, navigate to *Security > Autostart* and enable the app to prevent the engine from being killed in the background.
---

## Version History

### v1.10.0 — Stability and Engine Performance Update *(Current)*
`versionCode 32`

- **[FIXED]** Critical threading issues in `ProfileManager` preventing ANRs on startup.
- **[FIXED]** Syntax and build errors in `VolumeControlService` and `PermissionsWrapper`.
- **[IMPROVED]** Duty-cycle loop frequency increased to 5 seconds for more responsive volume tracking.
- **[IMPROVED]** Hearing safety persistence strategy to prevent data loss.
- **[UPDATED]** Compile SDK to 36 and dependencies for better platform compatibility.

### v1.9.0 — Battery Optimisation Overhaul
`versionCode 31`

- **[NEW]** Duty-cycled microphone sampling: 1 s record then 5 s sleep (replaces continuous audio stream).
- **[NEW]** Playback-state gating via `AudioManager.isMusicActive()`: microphone skipped entirely when no music is playing; 20 s extended sleep between checks.
- **[NEW]** Downsampled audio pipeline: 16 kHz mono PCM-16 with automatic fallback to 8 kHz.
- **[NEW]** Volume step throttle: automated adjustments only fire when the delta is 1 or more full system volume steps (eliminates micro-step spam).
- **[NEW]** UI data emission now lifecycle-scoped — all visualiser/dB flows halt when the app is backgrounded or the screen is off.
- **[IMPROVED]** Notification updated to `PRIORITY_LOW` to reduce status bar clutter on some ROMs.
- **[IMPROVED]** `AcousticEchoCanceler` now attaches per duty cycle and is fully released on each loop exit.
- **[REMOVED]** 2 dB step size option deprecated and removed from the UI.

---

### v1.8.x — Stability and Hearing Safety

- **[NEW]** 60/60 Hearing Safety Rule: cumulative daily high-volume tracking with dismissible warning card.
- **[NEW]** DataStore persistence for safety timer across reboots and configuration changes.
- **[NEW]** `BootReceiver` auto-start on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- **[NEW]** Self-healing service restart on `onResume` via DataStore active-state flag.
- **[NEW]** `onTaskRemoved` AlarmManager restart for aggressive OEM task-killers.
- **[IMPROVED]** OEM Setup assistant with deep-link intents for Xiaomi, Samsung, Huawei, OPPO, and Vivo.
- **[IMPROVED]** `PendingIntent` flags updated to `FLAG_IMMUTABLE` throughout for Android 12 compatibility.

---

### v1.5.x — Auto-Learning and Bluetooth

- **[NEW]** Auto-Learning Custom profile: detects manual volume changes and back-calculates the preferred dB offset.
- **[NEW]** Manual override lock with 5 dB ambient shift threshold to re-engage automatic control.
- **[NEW]** Bluetooth A2DP/SCO output detection with 500 ms write buffer for wireless latency compensation.
- **[NEW]** Startup protection dialog: prompts user before lowering volume significantly on first engine start.
- **[IMPROVED]** Rolling mean engine replaces exponential smoothing filter.
- **[IMPROVED]** Hysteresis band expanded to +-3 dB.

---

### v1.3.1 — Initial Public Release

- **[NEW]** Core adaptive volume engine with foreground service.
- **[NEW]** Volume mapping: 50 dB to 30%, 90 dB to 100% with linear interpolation.
- **[NEW]** Library, Standard, and Commute profiles.
- **[NEW]** Material 3 / Jetpack Compose UI with dynamic colour support (Android 12+).
- **[NEW]** DataStore-backed preference persistence.
- **[NEW]** Haptic feedback on volume changes (VibrationEffect API 26+ with legacy fallback).
- **[NEW]** Software AEC fallback for devices without hardware echo cancellation.
- **[NEW]** 1.5-second peak filter to discard transient noise spikes.
- **[NEW]** Debug log export via Android Storage Access Framework (SAF).
- **[NEW]** Edge-to-edge layout with proper system bar insets.
- **[NEW]** Conditional permission flow: `BLUETOOTH_CONNECT` requested only on Android 12+.

---

## License

This project is licensed under the **GNU General Public License v3.0**.  
See the [LICENSE](LICENSE) file for the full licence text.

```
Copyright (C) 2026 @nukie-git

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
