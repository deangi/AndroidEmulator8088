# AndroidEmulator8088 implementation plan

## Version policy

Phase 1 begins at **0.1.0**.  Each Phase 1 build increments the patch number:
0.1.1, 0.1.2, and so on.  Phase 2 begins at **0.2.0** and follows the same
rule.  Version **1.0.0** is reserved for the first end-user-usable release.

## Phase 1 — native 8088 foundation (0.1.x)

- **0.1.0:** Project identity, Android native build, `8086tiny` CPU/BIOS
  import, console boundary, and original launcher icon.
- **0.1.1 (complete):** Complete Android host hooks for keyboard, console, timing, and
  diagnostic reporting; add native unit tests for reset and instruction runs.
  Add a small 8088 smoke-test program that prints `Hello from 8088\n` to both
  the on-device console and Telnet, then continuously reads input characters
  and echoes them back to both outputs.
- **0.1.2 (complete):** Descriptor-backed A: through D: disk media with correct floppy
  and hard-disk geometry validation.
- **0.1.3 (complete):** Read the `v8088config*.ini` profiles from `Emulators/v8088` and
  map their disk/boot selections into the engine.
- **0.1.4 (complete):** Basic terminal UX, boot progress/error reporting, and safe media
  mount/eject behavior.

## Phase 2 — usable DOS workflow (0.2.x)

- **0.2.0 (complete):** Boot MS-DOS 3.31 from the supplied images and validate keyboard,
  screen output, A:/C: access, and clean reset.
- **0.2.1 (in progress):** 80x25 PC text-mode renderer and proper DOS keyboard mappings.
- **0.2.2:** Persist selected profile/media and provide image creation/import.
- **0.2.3:** Printer capture, Telnet console parity, and performance tuning.

## Release candidate path (0.3.x–0.9.x)

- Device compatibility testing, storage-permission recovery, corruption-safe
  image handling, user documentation, and regression testing against the
  supplied disk set.

## Version 1.0.0 gate

Release only when a user can install the APK, select `Emulators/v8088`, boot
the supplied MS-DOS image, interact reliably, and preserve disk changes over
an app restart without data loss.
