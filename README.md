# AndroidEmulator8088

Android Studio project for an Intel 8088 / MS-DOS emulator.  It retains the
Android host patterns from AndroidEmulatorPdp11 while packaging the v8088
firmware project's `8086tiny` CPU engine behind the same JNI console-core
interface.

## Current phase

Phase 1 starts at version **0.1.0**.  Each build in this phase increments the
patch version (0.1.1, 0.1.2, …).  Phase 2 begins at 0.2.0.  Version 1.0.0 is
reserved for the first usable end-user release.

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the staged plan and
release gate.

## Storage

The app uses the user-selected Android document-tree folder
`Emulators/v8088`.  The supplied v8088 configuration and DOS disk images are
not checked into this repository.  The standard profile is `v8088config.ini`,
with A: through D: media entries and an A: boot drive.

## Engine

The native layer vendors the v8088 project's `8086tiny.c` CPU core and BIOS
blob.  Android-specific host hooks provide console input/output, timing, and
descriptor-based disk I/O; ESP32 display, Wi-Fi, and PSRAM dependencies are
not carried into the Android project.

## Build

Open the project in Android Studio or run:

```text
./gradlew :app:assembleDebug
```
