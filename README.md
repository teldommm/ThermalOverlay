# ThermalOverlay

A floating performance HUD for rooted Android devices, plus a screen for
tuning CPU cluster and GPU frequencies. Built with Kotlin and a small native
JNI helper for hot-path sysfs reads.

## Features

- **Floating HUD** — CPU frequency (per cluster), CPU load, CPU temperature,
  GPU frequency/load, DDR frequency, FPS, battery status, swap/ZRAM usage.
  Drag to reposition, tap to cycle between compact and detailed layouts.
- **Frequency control** — adjust governor, min/max frequency, and boost
  settings for each CPU cluster (little/big/prime, auto-detected) and for the
  GPU (governor, min/max clock, Adreno Boost, power level, throttling), plus
  CPU input-boost tunables.
- **Quick Settings tile** — start or stop the overlay in one tap.
- **Dual-battery support** — optionally doubles the reported current draw for
  devices that only report one of two battery cells.

## Requirements

- A rooted Android device, API 26 (Android 8.0) or newer.
- Frequency control targets Qualcomm/Snapdragon sysfs paths
  (`cpufreq/policy0/3/4/6/7`, `kgsl-3d0`); the HUD's other metrics are more
  broadly portable (Adreno/Mali/MediaTek GPUs are all handled), but frequency
  control itself is Qualcomm-only.

## Building

```bash
./gradlew assembleDebug     # unsigned debug build
./gradlew assembleRelease   # signed release build
```

The release build type reads a signing config from `gradle.properties`
(`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`). Provide your own keystore before building a release
APK, or supply these as CI secrets instead of committing real credentials.

A GitHub Actions workflow (`.github/workflows/build.yml`) builds both
variants on every push to `main`/`master` and uploads them as artifacts.

## Project layout

```
app/src/main/java/com/thermaloverlay/overlay/
├── MainActivity.kt          # start/stop overlay, permissions, root check
├── OverlayService.kt        # foreground service keeping the HUD alive
├── OverlayTileService.kt    # Quick Settings tile
├── freqcontrol/             # CPU/GPU frequency control screen
├── metrics/                 # CPU/GPU/battery/FPS/swap readers
├── shell/                   # root shell wrapper + sysfs read/write helpers
├── ui/                      # the floating HUD window and its custom views
└── utils/                   # small platform-compat helpers
```

## Permissions

- `SYSTEM_ALERT_WINDOW` — draw the floating HUD over other apps.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` — keep the HUD
  running in the background.
- `POST_NOTIFICATIONS` — required for the foreground service's notification
  on Android 13+.
- Root (`su`) — required for reading/writing most of the sysfs nodes this
  app uses.

## Disclaimer

This app reads and writes low-level kernel interfaces on a rooted device.
Incorrect frequency/governor values can cause instability, so use the
frequency control screen with care. The authors take no responsibility for
any damage to your device.

## License

MIT — see [LICENSE](LICENSE).
