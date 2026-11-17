# AndroidEmulator8088

Android Studio project for an Intel 8088 / MS-DOS emulator.  It retains the
Android host patterns from AndroidEmulatorPdp11 while packaging the v8088
firmware project's `8086tiny` CPU engine behind the same JNI console-core
interface.

## Current progress

The project has reached its first usable release and is currently at
**version 1.1.0**. Phase 1 established the
Android-native 8088 engine, launcher branding, profile parsing, safe
descriptor-backed media mounting, and BIOS services for A: through D:.

Verified on-device:

- MS-DOS 3.31 boots from the supplied A: image.
- A:, B:, C:, and D: are separately visible to DOS.
- All four supplied media images retain writes across an app reboot.
- `drives` reports mounted media through the native emulator model, including
  floppy and hard-disk image sizes.

Post-1.0 work:

- Validate DOS keyboard input and on-device terminal output under normal use.
- Validate a clean reset back to the selected boot profile without remounting
  or losing media changes.
- Recheck Telnet console input/output against the local terminal.

### Performance status

Version 1.1.0 makes two measured execution-path improvements:

- The signed arm64-v8a release variant builds the native 8088 core with
  CMake `RelWithDebInfo`, including `-O2` and `-DNDEBUG`.
- The default host/native execution budget increased from 1,000 to 8,192
  instructions per slice, reducing coroutine and host-service overhead.

On the development device, FreeDOS boot time improved from 32 seconds in a
debug build to 23 seconds in the optimized release build, then to **10
seconds** with the 8,192-instruction slice. These measurements are device and
disk-image dependent.

Remaining performance work is to verify sustained keyboard and Telnet
responsiveness at the new slice size, capture a repeatable boot benchmark,
profile native CPU versus disk I/O time, and evaluate a dedicated emulator
thread only if profiling shows Kotlin scheduling remains material.

Future builds will cover PC 80x25 rendering and DOS key mappings,
profile/media persistence plus image import, and printer/Telnet parity.

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the staged plan and
release gate.

## Storage

The app uses the user-selected Android document-tree folder
`Emulators/v8088`.  The supplied v8088 configuration and DOS disk images are
not checked into this repository.  The standard profile is `v8088config.ini`,
with A: through D: media entries and an A: boot drive.

The user-facing emulator identity is currently **v8080**. The established
`v8088` media folder and profile filename remain unchanged for compatibility
with the supplied disk set.

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

For a direct-install arm64 release APK:

```text
./gradlew :app:assembleRelease
```

The release configuration is signed with Android's local debug certificate
for direct device distribution. Replace it with a private release keystore
before any app-store publication.
