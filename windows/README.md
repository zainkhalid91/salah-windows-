# Salah for Windows: source

This folder is the Kotlin / Compose Multiplatform project for **Salah for Windows**.

**For users:** download, install, features and troubleshooting are in the main
[README](../README.md#-download).

## Quick start for developers

You need JDK 21. In this folder:

```powershell
.\gradlew :app:run                 # run the app
.\gradlew :core:test               # tests (calculation, config, reminder planner)
.\gradlew :cli:installDist         # the salah CLI → cli\build\install\salah\bin\
.\gradlew :app:packageExe          # installer → app\build\compose\binaries\main\exe\
.\gradlew :app:run --args="--snapshot shots"   # render every screen to PNG
.\gradlew :app:run --args="--selftest"         # start the app, exit after 10 s (CI uses this)
```

To open the project in IntelliJ IDEA or Android Studio, open *this* folder (`windows/`), not the
repository root, and set the Gradle JDK to 21.

## Modules

| Module | Contents |
|---|---|
| `core` | Pure Kotlin, no UI: the adhan-swift 1.4.0 port (`adhan/Adhan.kt`), `SalahConfig` + `ConfigStore` (same JSON as macOS), `ConfigKeys`, schedules and `PrayerClock`, Hijri, exporters, `NotificationPlanner`, `AppInstance` (single instance). |
| `cli` | The `salah` command: argument parsing, box/plain/compact/JSON output, exit codes. |
| `app` | Compose Desktop UI (`ui/`), `AppModel` (state, ticker, reminder loop), Windows integration (`platform/`: registry, PATH, location, updater, toasts, dark title bar), `Snapshot`, `CrashLog`. |

## Notes

- Compose Multiplatform is pinned to 1.8.2, the last release that resolves fully from Maven
  Central. Newer versions also need Google's Maven repository; the build adds it, so upgrading is
  just a version bump in `build.gradle.kts`.
- `kotlinx-coroutines-swing` must stay declared in `app/build.gradle.kts`. Without it the packaged
  app crashes at start with "Module with the Main dispatcher is missing".
- The `createRuntimeImage` hook in `app/build.gradle.kts` puts `java.exe` back into the bundled
  runtime for `salah.cmd`.
