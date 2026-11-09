cmake_minimum_required(VERSION 3.22.1)
project("androidpdp11")

add_library(androidpdp11 SHARED
    native-lib.cpp
    core/portable_z80.cpp
    core/z80.c
)

# Phase 2 porting boundary.  This is deliberately separate from the existing
# JNI library while the Kotlin host still uses the inherited Z80 API.
add_library(pdp11core STATIC
    pdp11/compat/kek_vs_console.cpp
    pdp11/compat/kek_vs_log.cpp
    pdp11/compat/kek_android_utils.cpp
    pdp11/kek/breakpoint.cpp
    pdp11/kek/breakpoint_and.cpp
    pdp11/kek/breakpoint_instruction.cpp
    pdp11/kek/breakpoint_memory.cpp
    pdp11/kek/breakpoint_or.cpp
    pdp11/kek/breakpoint_parser.cpp
    pdp11/kek/breakpoint_register.cpp
    pdp11/kek/bus.cpp
    pdp11/kek/cpu.cpp
    pdp11/kek/dc11.cpp
    pdp11/kek/ddp.cpp
    pdp11/kek/deqna.cpp
    pdp11/kek/device.cpp
    pdp11/kek/disk_backend.cpp
    pdp11/kek/disk_backend_file.cpp
    pdp11/kek/dz11.cpp
    pdp11/kek/error.cpp
    pdp11/kek/kw11-l.cpp
    pdp11/kek/loaders.cpp
    pdp11/kek/memory.cpp
    pdp11/kek/mmu.cpp
    pdp11/kek/my_lock.cpp
    pdp11/kek/rk05.cpp
    pdp11/kek/rl02.cpp
    pdp11/kek/rp06.cpp
    pdp11/kek/tm-11.cpp
    pdp11/kek/tty.cpp
    pdp11/kek/uda50.cpp
)

target_include_directories(pdp11core PRIVATE pdp11/kek)
target_compile_definitions(pdp11core PRIVATE
    IS_POSIX=0
)
target_compile_options(pdp11core PRIVATE
    -include ${CMAKE_CURRENT_SOURCE_DIR}/pdp11/compat/kek_android_compat.h
    -Wall -Wextra
)

find_library(log-lib log)
target_link_libraries(androidpdp11 PRIVATE pdp11core ${log-lib})
