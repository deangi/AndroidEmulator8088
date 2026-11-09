cmake_minimum_required(VERSION 3.22.1)
project("androidpdp11")

add_library(androidpdp11 SHARED
    native-lib.cpp
    core/portable_z80.cpp
    core/z80.c
)

find_library(log-lib log)
target_link_libraries(androidpdp11 ${log-lib})
