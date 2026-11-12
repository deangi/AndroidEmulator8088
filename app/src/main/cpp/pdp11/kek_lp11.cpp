#include "kek_lp11.h"
#include <deque>
#include <mutex>

namespace kek_lp11 {
namespace { constexpr uint16_t DONE = 0000200, IE = 0000100; constexpr uint64_t DELAY = 100;
std::mutex lock; std::deque<uint8_t> output; uint16_t lps = DONE, lpb = 0; uint64_t readyAt = 0; bool irq = false;
void updateIrq() { if ((lps & (DONE | IE)) == (DONE | IE)) irq = true; }
}
bool contains(uint16_t a) { a &= ~1u; return a == CSR_ADDR || a == DB_ADDR; }
void reset() { std::lock_guard guard(lock); lps = DONE; lpb = 0; readyAt = 0; irq = false; output.clear(); }
void tick(uint64_t now) { std::lock_guard guard(lock); if (!(lps & DONE) && now >= readyAt) { lps |= DONE; readyAt = 0; updateIrq(); } }
bool take_interrupt() { std::lock_guard guard(lock); const bool result = irq; irq = false; return result; }
uint16_t read_word(uint16_t a) { std::lock_guard guard(lock); return (a & ~1u) == CSR_ADDR ? lps : 0; }
uint8_t read_byte(uint16_t a) { const uint16_t v = read_word(a & ~1u); return (a & 1) ? uint8_t(v >> 8) : uint8_t(v); }
void write_word(uint16_t a, uint16_t v, uint64_t now) { std::lock_guard guard(lock); switch (a & ~1u) { case CSR_ADDR: { const bool was = lps & IE; lps = uint16_t((lps & ~IE) | (v & IE)); if (!(lps & IE)) irq = false; else if (!was) updateIrq(); break; } case DB_ADDR: lpb = v & 0177; output.push_back(uint8_t(lpb)); lps &= ~DONE; irq = false; readyAt = now + DELAY; break; } }
void write_byte(uint16_t a, uint8_t v, uint64_t now) { const uint16_t old = read_word(a & ~1u); write_word(a & ~1u, (a & 1) ? uint16_t((old & 0377) | (uint16_t(v) << 8)) : uint16_t((old & 0177400) | v), now); }
std::vector<uint8_t> drain_output() { std::lock_guard guard(lock); std::vector<uint8_t> result(output.begin(), output.end()); output.clear(); return result; }
}
