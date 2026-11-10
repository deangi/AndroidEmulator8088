# AndroidEmulatorPdp11

Android re-host of the `vpdp1170` DEC PDP-11/70 emulator, initially targeting
RT-11 V5.4F on an RK05 (`RK0`) disk.

## Status

Phase 3 is in progress. This repository is an independently named Android
Studio project created from the Android VZ80 host framework. Its Android
terminal, foreground-service, Telnet, and document-storage scaffolding are
being retained; the inherited Z80 CPU and CP/M-specific disk implementation
are temporary and will be replaced in the PDP-11 engine phases.

The current Phase 3 build is `0.3.9` (version code 309), dated 2026-11-09.
Phase numbers use the minor version (`0.1.x`, `0.2.x`, and so on); patch
numbers advance for each build within a phase. Version `1.0.0` is reserved
for a near-working prototype.

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

The PDP-11 console defaults to an 80×25 VT100 personality. Set
`[console] terminal = adm3a` in a profile only when running software that
requires the older ADM-3A parser.

## Phase 3 task list

- [x] Boot RT-11 from a selected RK05 disk image through an Android file descriptor.
- [x] Report PDP-11 drive names (such as `RK0` and `RL0`) in system status.
- [ ] Implement a PDP-11 LP11 line-printer device and connect it to Android
  printer capture in `vPDP11/printers`. Implement LPS at `177514` (octal),
  LPB at `177516` (octal), and interrupt vector `200` (octal), following the
  [LP11 section of the 1976 PDP-11 Peripherals Handbook](https://bitsavers.trailing-edge.com/pdf/dec/pdp11/handbooks/PDP-11_PeripheralsHbk_1976.pdf).
  This will implement the standard `EmulatorCore.linePrinter: LinePrinterPort?`
  contract already shared by emulator engines, so printer bytes remain separate
  from the console/Telnet stream.
- [ ] Add PDP-11 drive mount/dismount controls using native PDP device names.
- [ ] Add Telnet-shell blank-image creation through `DrivePort.createBlankImage()`;
  support `create <image-type> <file-name> [size-bytes]`, where an omitted size
  passes `0` and selects the type's default geometry.
- [ ] Add front-panel support and additional PDP-11 disk controllers.

## Build

Open this directory in Android Studio, or run:

```text
./gradlew :app:assembleDebug
```

Phase 3 removes the temporary console diagnostic and begins the RT-11 boot
path: selected `vPDP11` storage is parsed for `pdpconfig*.ini`, and the
configured `rk0` disk is opened by descriptor and attached to the RK05
controller. Drive editing, other PDP-11 controllers, and printer output are
still staged work.
