# PDP-11 native engine staging

`kek/` contains the top-level source and header files from the MIT-licensed
`kek` PDP-11/70 engine vendored by the `vpdp1170` ESP32 project. The license
is preserved in `kek/LICENSE`.

This directory intentionally excludes the donor's platform projects, generated
build output, sample disk images, and host executables. The next porting step
is to compile the CPU, MMU, memory, bus, and minimal console/device subset
against Android's NDK, using `compat/` only for code that was specific to the
Windows staging harness.

Do not pass Android filesystem paths into this engine. The Android host opens
media and supplies file descriptors through the emulator drive interface.
