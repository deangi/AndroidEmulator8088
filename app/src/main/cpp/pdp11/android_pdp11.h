#pragma once

#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "bus.h"
#include "console.h"
#include "cpu.h"
#include "gen.h"

namespace pdp11android {

class AndroidConsole final : public console {
public:
    explicit AndroidConsole(kek_event_t* stopEvent) : console(stopEvent) {}
    std::vector<uint8_t> drainOutput();
    void put_string_lf(const std::string& value) override;
    void resize_terminal() override {}
    void refresh_virtual_terminal() override {}
    void panel_update_thread() override {}
    void ui_event_loop() override {}

private:
    int wait_for_char_ll(int) override { return -1; }
    void put_char_ll(char value) override;
    std::mutex outputLock;
    std::deque<uint8_t> output;
};

class Engine final {
public:
    Engine();
    ~Engine() = default;
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    void reset(uint16_t entryPc = 0);
    uint64_t runInstructions(uint64_t count);
    void setMemorySizeBytes(uint32_t bytes);
    uint32_t memorySizeBytes() const;
    void clearMemory(uint8_t value);
    void load(uint32_t address, const uint8_t* bytes, size_t count);
    void enqueueInput(uint8_t value);
    bool hasPendingInput();
    std::vector<uint8_t> drainConsole();
    const char* processorId() const { return "PDP-11/70"; }

private:
    kek_event_t stopEvent { EVENT_NONE };
    AndroidConsole console { &stopEvent };
    bus machine;
};

} // namespace pdp11android
