# Ambient Volume — Adaptive Volume Engine

> **An Android application that automatically adjusts your media volume in real-time based on the ambient noise level around you.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
![Android](https://img.shields.io/badge/Android-9%2B%20(API%2028%2B)-brightgreen)
![Version](https://img.shields.io/badge/Version-1.8.1-orange)
![Language](https://img.shields.io/badge/Language-Kotlin-purple)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
  - [Core Engine](#core-engine)
  - [Noise Intelligence & Signal Processing](#noise-intelligence--signal-processing)
  - [User Interface](#user-interface)
  - [OEM Compatibility & Persistence](#oem-compatibility--persistence)
  - [Privacy & Safety](#privacy--safety)
- [Requirements](#requirements)
- [Building from Source](#building-from-source)
- [Permissions](#permissions)
- [Architecture](#architecture)
- [Version History](#version-history)
- [License](#license)

---

## Overview

**Ambient Volume** is a foreground-service Android application that continuously samples your device's microphone to measure the surrounding noise environment, then intelligently adjusts your media volume in real-time. It is designed to work reliably in the background across all major Android OEM firmware variants including Xiaomi MIUI/HyperOS, Samsung One UI, Huawei EMUI, OPPO ColorOS, and Vivo FuntouchOS.

The engine never records or stores audio. It only computes an instantaneous RMS (Root Mean Square) amplitude value to derive a decibel level — no audio data leaves the device.

---

## Features

### Core Engine

- **Real-Time Ambient Sensing** — Uses `AudioRecord` sampling at 44,100 Hz (PCM 16-bit mono) to compute live RMS amplitude and map it to a dB level.
- **Rolling Mean Smoothing** — A configurable rolling average window (3–30 seconds) prevents jarring volume jumps due to momentary loud sounds.
- **Hysteresis Dead Zone** — Volume changes are only triggered when the rolling mean dB shifts by more than ±3 dB from the last adjusted level, preventing constant micro-adjustments.
- **Configurable Step Size** — Volume adjustments are applied in discrete steps (1, 3, 5, 7, 10, or 15 dB) to ensure smooth, natural-feeling transitions.
- **Auto-Restart on Boot** — A `BootReceiver` re-launches the engine automatically after the device reboots or the app is updated, if the service was active at the time of shutdown.
- **Self-Healing on App Resume** — When the app is opened, it checks if the service was unexpectedly killed (e.g., by the OS) and automatically restarts it.
- **Wake Lock** — Holds a `PARTIAL_WAKE_LOCK` to keep the CPU alive for audio processing when the screen is off.

### Noise Intelligence & Signal Processing

- **Startup Volume Protection** — On first activation, if your current volume is significantly higher than the engine would set it, a dialog prompts you to decide before any automatic change is made.
- **Manual Override Auto-Learning (Custom Profile)** — If you manually adjust the volume while the engine is running, it detects this, learns your preference as a custom dB offset, and locks itself to honor your choice until the environment changes significantly (±5 dB shift).
- **Short-Term Peak Filter (Transient Suppression)** — Instantaneous loud sounds (e.g., a door slam, a cough) are discarded if they last less than 1.5 seconds and are more than 10 dB above the current rolling mean. This prevents the engine from raising the volume in response to brief noise bursts.
- **Feedback Loop Prevention** — Monitors the system volume level and ignores audio samples captured within 50ms of a volume change, preventing the engine from reacting to the audio output of the speakers themselves.
- **Acoustic Echo Cancellation (AEC)** — Uses Android's hardware `AcousticEchoCanceler` when available, with a software-based RMS signal subtraction fallback for devices that lack hardware AEC support.
- **Silence & Microphone Seizure Handling** — Detects when another app (e.g., Google Assistant, a phone call) has taken over the microphone. The engine freezes its state and resumes smoothly once mic access is restored, without making abrupt volume changes.
- **Bluetooth Latency Buffer** — Adds a 500ms delay before applying volume changes when a Bluetooth A2DP or SCO audio output device is active to account for wireless transmission latency.
- **SpotMute / Mutify Protection** — The engine will never automatically raise the volume if the system volume is at zero (i.e., the device is manually muted).

### User Interface

The UI is built entirely with **Jetpack Compose** and **Material 3**, organized into three swipe-navigable tabs:

#### 📊 Monitor Tab
- Live circular dB meter showing instantaneous ambient noise level.
- Real-time volume slider reflecting the current system media volume.
- Service start/stop control button.
- A privacy info dialog explaining that no audio is stored or transmitted.
- **(Debug builds only):** A dual-bar audio visualizer showing both the instantaneous dB and the rolling mean dB in real-time.

#### ⚙️ Engine Tab
- **Environmental Profile Selector** — Choose from four presets:
  - **Library** — Quiet environment, caps volume at 40%, low offset.
  - **Standard** — Everyday use, caps volume at 80%, medium offset. *(Default)*
  - **Commute** — Loud environments (transit, gym), allows up to 100% volume.
  - **Custom (Auto-Learned)** — Automatically created when you override the engine manually.
- **Sensitivity (Step Size)** — Select how aggressively the volume adjusts per step (1–15 dB).
- **Averaging Period (Smoothing)** — Select the rolling mean window from 3 to 30 seconds.

#### 🔧 Settings Tab
- **Material You Theme Toggle** — Switch between system dynamic color and a fixed theme.
- **Haptic Feedback Toggle** — Vibrate briefly on every volume step change.
- **Hearing Safety Warning Toggle** — Enable/disable the 60/60 hearing safety rule tracker.
- **Persistence Assistant** — A guided bottom sheet dialog with OEM-specific, deep-linked instructions to whitelist the app from battery optimization on Xiaomi, Samsung, Huawei, OPPO, and Vivo devices. Includes live status checking for battery exemption and notification permissions.
- **(Debug builds only):** Export or clear the in-app debug log file.

### OEM Compatibility & Persistence

The app includes first-class support for aggressive OEM battery management systems:

| OEM / ROM | Strategy |
|---|---|
| **Xiaomi / MIUI / HyperOS** | Deep-links to Autostart management; handles MIUI 14 service stability via `IMPORTANCE_HIGH` notification channel and alarm-based restart on task removal. |
| **Samsung / One UI** | Deep-links to Device Care battery settings. |
| **Huawei / EMUI** | Deep-links to App Launch manager. |
| **OPPO / ColorOS / Realme** | Deep-links to Startup Manager. |
| **Vivo / FuntouchOS** | Deep-links to Speed Up whitelist manager. |
| **MediaTek DuraSpeed** | Detects and reports DuraSpeed presence for targeted guidance. |

### Privacy & Safety

- **No Audio Recording** — The microphone is used exclusively for real-time amplitude measurement. No audio buffers are written to disk or transmitted over a network.
- **60/60 Hearing Safety Rule** — Tracks the cumulative daily duration your volume has been above 60% of maximum. After 60 minutes of high-volume listening, a persistent alert is shown. The daily timer resets at midnight. The running timer is persisted to `DataStore` every 10 seconds so it survives app restarts.
- **All Settings Persisted Locally** — All profiles, preferences, and safety data are stored in `DataStore Preferences` on-device only.

---

## Requirements

- **Android:** 9.0 (Pie, API 28) or higher
- **Target SDK:** Android 14 (API 34)
- **Compile SDK:** Android 15 (API 36) Preview

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/nukie-git/AmbientVolumeApp.git
   ```
2. Open the project in **Android Studio Meerkat** or newer.
3. Sync Gradle dependencies.
4. Build and run on a physical device (API 28+).

> **Note:** A physical device is strongly recommended, as the audio engine requires microphone hardware that is typically unavailable or unreliable on emulators.

---

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Microphone access for ambient noise sampling |
| `MODIFY_AUDIO_SETTINGS` | Adjusting the system media volume stream |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Running the engine as a persistent foreground service |
| `POST_NOTIFICATIONS` | Displaying the persistent engine status notification |
| `RECEIVE_BOOT_COMPLETED` | Auto-restarting the service on device reboot |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | Detecting active Bluetooth audio output for latency adjustment |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Deep-linking directly to the battery optimization exemption settings screen |
| `VIBRATE` | Haptic feedback on volume step changes |

---

## Architecture

```
com.nukie.ambientvolume
├── MainActivity.kt             — Single-Activity host; Compose UI (Monitor, Engine, Settings tabs)
├── BootReceiver.kt             — BroadcastReceiver for BOOT_COMPLETED / MY_PACKAGE_REPLACED
├── service/
│   ├── VolumeControlService.kt — Core foreground service: audio sampling, dB calculation, volume logic
│   ├── AudioStateRepository.kt — Kotlin StateFlow repository; single source of truth for UI state
│   ├── ProfileManager.kt       — DataStore-backed persistence for profiles, settings, and safety data
│   ├── MovingAverage.kt        — Thread-safe rolling mean implementation with dynamic window resizing
│   └── OEMManager.kt          — OEM detection and deep-link intent factory for battery settings
├── ui/
│   ├── PermissionsWrapper.kt   — Compose wrapper for runtime permission handling
│   └── theme/                  — Material 3 color, typography, and theme definitions
└── util/
    └── DebugLogger.kt          — File-based debug logger (debug builds only)
```

---

## Version History

### [1.8.1] — 2026-06-30 *(Current)*

- **Centralized versioning** — All version information (`versionName`, `versionCode`) is now sourced exclusively from `build.gradle.kts` and injected globally via `BuildConfig`. Hardcoded version strings removed from all source files.
- Implemented comprehensive file auditing protocols.
- Resolved `.gitignore` to exclude all sensitive local configuration files (`local.properties`, keystore files, `*.jks`, `*.keystore`).

---

### [1.3.1] — 2026-05-13 *(Initial Public Commit)*

- Initial clean public release of the Adaptive Volume codebase to GitHub.
- Core audio processing pipeline: RMS amplitude → dB conversion, rolling mean smoothing, hysteresis dead zone.
- Foreground service architecture with `START_STICKY` restart policy.
- `BootReceiver` for auto-start after reboot and app update.
- Four environmental profiles: Library, Standard, Commute, Custom (Auto-Learned).
- Manual override detection with auto-learning of custom dB offset.
- Startup volume protection dialog.
- Short-term peak/transient filter (1.5-second suppression window).
- Feedback loop prevention (50ms correlation window).
- Hardware AEC with software RMS-subtraction fallback.
- Bluetooth audio output detection with 500ms latency buffer.
- 60/60 hearing safety timer with persistent DataStore tracking.
- OEM-aware Persistence Assistant with deep-linked battery optimization dialogs for Xiaomi, Samsung, Huawei, OPPO, and Vivo.
- MIUI 14 stability fixes: `IMPORTANCE_HIGH` notification channel, alarm-based `onTaskRemoved` restart.
- Haptic feedback on volume step change.
- SpotMute / zero-volume protection.
- Material 3 Jetpack Compose UI with swipe-navigable Monitor, Engine, and Settings tabs.
- Material You dynamic color theme toggle.
- Debug build: dual-bar live audio visualizer, file-based log export and clear functionality.

---

## License

This project is licensed under the **GNU General Public License v3.0**.
See the [LICENSE](LICENSE) file for the full license text.

```
Copyright (C) 2026 @nukie-git

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

---

*Developed with the assistance of Google Gemini & Google Antigravity. Built using Android Studio Meerkat.*
