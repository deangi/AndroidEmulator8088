#pragma once

// Android is POSIX, but the upstream engine's POSIX profile also enables its
// desktop JSON/configuration paths.  Keep that profile off while the Android
// host supplies configuration and media through JNI.
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <sys/types.h>

using namespace std::chrono_literals;
