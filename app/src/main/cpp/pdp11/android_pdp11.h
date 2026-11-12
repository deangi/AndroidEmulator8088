#pragma once

#include <cstdint>
#include <atomic>
#include <deque>
#include <mutex>
#include <memory>
#include <string>
#include <vector>

#include "bus.h"
#include "console.h"
#include "cpu.h"
#include "disk_backend.h"
#include "gen.h"
#include "instruction_scheduler.h"

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
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    void reset(uint16_t entryPc = 0);
    bool config(const std::string& parameter);
    void setProgramCounter(uint16_t entryPc);
    uint64_t runInstructions(uint64_t count);
    void setMemorySizeBytes(uint32_t bytes);
    uint32_t memorySizeBytes() const;
    void zeroMemory();
    void clearMemory(uint8_t value);
    void load(uint32_t address, const uint8_t* bytes, size_t count);
    bool bootRk05(int descriptor);
    bool mountRl(int unit, int descriptor);
    bool dismountRl(int unit);
    bool bootRl(int unit);
    bool mountDu0(int descriptor);
    bool bootDu0();
    bool mountDrive(const std::string& name, int descriptor);
    bool dismountDrive(const std::string& name);
    std::string enumerateDrives();
    std::string diagnostics();
    void requestDiagnostics() { diagnosticsRequested.store(true); }
    std::string takeDiagnostics();
    void enqueueInput(uint8_t value);
    bool hasPendingInput();
    std::vector<uint8_t> drainConsole();
    std::vector<uint8_t> drainPrinter();
    const char* processorId() const { return "PDP-11/70"; }

private:
    kek_event_t stopEvent { EVENT_NONE };
    AndroidConsole console { &stopEvent };
    InstructionScheduler scheduler;
    // Retained now so profile configuration is deterministic; the KW11-P
    // device will consume this when its ESP32 implementation is ported.
    bool kwpEnabled { false };
    uint32_t kw11InstructionTicks { 0 };
    std::atomic<bool> diagnosticsRequested { false };
    std::mutex diagnosticsLock;
    std::string pendingDiagnostics;
    std::unique_ptr<disk_backend> du0Media;
    void serviceTimedDevices(uint32_t elapsedInstructions);
    bus machine;
};

} // namespace pdp11android
