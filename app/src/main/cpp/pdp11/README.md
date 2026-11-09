# PDP-11 native engine staging

`kek/` contains the top-level source and header files from the MIT-licensed
`kek` PDP-11/70 engine vendored by the `vpdp1170` ESP32 project. The license
is preserved in `kek/LICENSE`.

This directory intentionally excludes the donor's platform projects, generated
build output, sample disk images, and host executables. The CPU, MMU, memory,
bus, RK05, and minimal console/device subset now compile as the `pdp11core`
Android NDK static target. `compat/` supplies Android-safe console, logging,
and file-descriptor helpers in place of the Windows staging harness.

`pdp11core` is linked into the app's native library, but its JNI adapter is not
yet connected to the existing Kotlin emulator API. That replacement is the
next Phase 2 increment.

Do not pass Android filesystem paths into this engine. The Android host opens
media and supplies file descriptors through the emulator drive interface.
