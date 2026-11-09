# AndroidEmulatorPdp11

Android re-host of the `vpdp1170` DEC PDP-11/70 emulator, initially targeting
RT-11 V5.4F on an RK05 (`RK0`) disk.

## Status

Phase 1 is in progress. This repository is an independently named Android
Studio project created from the Android VZ80 host framework. Its Android
terminal, foreground-service, Telnet, and document-storage scaffolding are
being retained; the inherited Z80 CPU and CP/M-specific disk implementation
are temporary and will be replaced in the PDP-11 engine phases.

The PDP-11 target workspace is `vPDP11` under the document tree selected by
the user. Disk images are deliberately not included in the repository or APK.

## First supported configuration

The first end-to-end configuration will be:

```ini
[system]
title = PDP-11 RT-11 V5.4F
mem_size_kw = 124

[disks]
rk0 = /rt11-5.dsk
boot = rk0
```

The corresponding user-provided files will live in `vPDP11`:

```text
vPDP11/
  pdpconfig-rt11-5.ini
  rt11-5.dsk
  printer/
```

## Architecture

The Android host will target a common `EmulatorCore` contract that exposes
CPU identity, memory control, instruction slices, console I/O, drive mounting,
line-printer output, and an optional front panel. Native PDP-11 code receives
open Android file descriptors rather than host paths.

The source engine is the vendored `kek` PDP-11/70 core used by the ESP32
`vpdp1170` project. The initial Android device set is KL11 console, RK05,
and the minimum clock/front-panel support necessary to boot RT-11.

## Build

Open this directory in Android Studio, or run:

```text
./gradlew :app:assembleDebug
```

The initial build validates the Android host baseline; it does not yet claim
PDP-11 guest boot support.
