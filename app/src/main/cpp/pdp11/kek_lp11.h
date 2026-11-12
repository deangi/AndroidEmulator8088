#pragma once

#include <cstdint>
#include <vector>

namespace kek_lp11 {
constexpr uint16_t CSR_ADDR = 0177514, DB_ADDR = 0177516, VECTOR = 0200;
constexpr uint8_t BR_LEVEL = 4;
bool contains(uint16_t address);
void reset();
void tick(uint64_t instructionNow);
bool take_interrupt();
uint16_t read_word(uint16_t address);
uint8_t read_byte(uint16_t address);
void write_word(uint16_t address, uint16_t value, uint64_t instructionNow);
void write_byte(uint16_t address, uint8_t value, uint64_t instructionNow);
std::vector<uint8_t> drain_output();
}
