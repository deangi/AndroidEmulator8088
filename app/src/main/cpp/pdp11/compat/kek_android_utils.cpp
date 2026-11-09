#include "utils.h"

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

void setBit(uint16_t& value, const int bit, const bool set)
{
    if (set)
        value |= uint16_t(1u << bit);
    else
        value &= uint16_t(~(1u << bit));
}

int parity(int value)
{
    value ^= value >> 8;
    value ^= value >> 4;
    value &= 0x0f;
    return (0x6996 >> value) & 1;
}

std::string format(const char* const fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    va_list copy;
    va_copy(copy, ap);
    const int length = std::vsnprintf(nullptr, 0, fmt, copy);
    va_end(copy);
    if (length < 0) {
        va_end(ap);
        return {};
    }
    std::string output(size_t(length), '\0');
    std::vsnprintf(output.data(), output.size() + 1, fmt, ap);
    va_end(ap);
    return output;
}

std::string to_hex(const uint8_t* const data, const size_t size)
{
    static constexpr char hex[] = "0123456789abcdef";
    std::string output;
    output.reserve(size * 2);
    for (size_t index = 0; index < size; ++index) {
        output.push_back(hex[data[index] >> 4]);
        output.push_back(hex[data[index] & 0x0f]);
    }
    return output;
}

std::vector<std::string> split(std::string input, const std::string& delimiter)
{
    std::vector<std::string> output;
    if (delimiter.empty()) {
        output.push_back(std::move(input));
        return output;
    }
    size_t offset = 0;
    for (;;) {
        const size_t next = input.find(delimiter, offset);
        if (next == std::string::npos) {
            output.push_back(input.substr(offset));
            return output;
        }
        output.push_back(input.substr(offset, next - offset));
        offset = next + delimiter.size();
    }
}

uint64_t get_us()
{
    using clock = std::chrono::steady_clock;
    return uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(
        clock::now().time_since_epoch()).count());
}

uint64_t get_ms() { return get_us() / 1000; }

void myusleep(const uint64_t microseconds)
{
    std::this_thread::sleep_for(std::chrono::microseconds(microseconds));
}

std::string get_thread_name() { return "android-pdp11"; }
void set_thread_name(std::string) {}

ssize_t WRITE(const int descriptor, const char* const bytes, const size_t length)
{
    return write(descriptor, bytes, length);
}

ssize_t READ(const int descriptor, char* const bytes, const size_t length)
{
    return read(descriptor, bytes, length);
}

void update_word(uint16_t* const word, const bool most_significant, const uint8_t value)
{
    if (most_significant)
        *word = uint16_t((*word & 0x00ff) | (uint16_t(value) << 8));
    else
        *word = uint16_t((*word & 0xff00) | value);
}

void set_nodelay(const int) {}
std::string get_endpoint_name(const int descriptor) { return format("fd:%d", descriptor); }
std::string get_configuration_string(const std::string&, const std::string& fallback) { return fallback; }
uint32_t get_configuration_uint32(const std::string&, const uint32_t fallback) { return fallback; }
bool put_configuration_uint32(const std::string&, const uint32_t) { return false; }
bool put_configuration_string(const std::string&, const std::string&) { return false; }

bool file_exists(const std::string& filename)
{
    std::ifstream input(filename, std::ios::binary);
    return input.good();
}
