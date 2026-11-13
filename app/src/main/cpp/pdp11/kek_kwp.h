#pragma once
#include <cstdint>
namespace kek_kwp {
constexpr uint16_t CSR_ADDR = 0172540, CSB_ADDR = 0172542, CNTR_ADDR = 0172544, END_ADDR = 0172550;
constexpr uint16_t VECTOR = 0104; constexpr uint8_t BR_LEVEL = 6;
void set_enabled(bool enabled); bool contains(uint16_t address); void reset(uint64_t now = 0);
void tick(uint64_t now); bool take_interrupt(); uint16_t read_word(uint16_t address, uint64_t now);
uint8_t read_byte(uint16_t address, uint64_t now); void write_word(uint16_t address, uint16_t value, uint64_t now);
void write_byte(uint16_t address, uint8_t value, uint64_t now);
}
