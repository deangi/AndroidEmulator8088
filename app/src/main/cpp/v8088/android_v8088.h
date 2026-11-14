#pragma once

#include <array>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace v8088android {

/** Android host adapter for the v8088/8086tiny machine.  Its public surface
 * deliberately mirrors the PDP-11 Engine JNI boundary. */
class Engine final {
public:
    Engine();
    ~Engine();
    void reset(uint16_t unusedEntryPc = 0);
    bool config(const std::string& parameter);
    void setProgramCounter(uint16_t pc);
    uint16_t lastProgramCounter() const;
    uint64_t runInstructions(uint64_t count);
    void setMemorySizeBytes(uint32_t) {}
    uint32_t memorySizeBytes() const;
    void zeroMemory();
    void clearMemory(uint8_t value);
    void load(uint32_t address, const uint8_t* bytes, size_t count);
    void clearProfileDevices();
    bool mountDrive(const std::string& name, int descriptor);
    bool dismountDrive(const std::string& name);
    std::string enumerateDrives() const;
    std::string diagnostics() const;
    void requestDiagnostics() {}
    std::string takeDiagnostics() { return {}; }
    void enqueueInput(uint8_t value);
    bool hasPendingInput() const;
    std::vector<uint8_t> drainConsole();
    std::vector<uint8_t> drainPrinter();
    const char* processorId() const { return "Intel 8088"; }

    static Engine* active();
    int diskRead(int slot, uint32_t lba, void* target, uint32_t bytes);
    int diskWrite(int slot, uint32_t lba, const void* source, uint32_t bytes);
    uint32_t diskSectors(int slot) const;
    void putchar(uint8_t value);
    int keyboard(uint8_t* value);
private:
    std::vector<uint8_t> ram;
    std::array<int, 4> disks { -1, -1, -1, -1 };
    std::deque<uint8_t> input, output, printer;
    mutable std::mutex lock;
    uint64_t executed { 0 };
    bool smokeTest { false };
    uint8_t bootDrive { 0x00 };
    void installConsoleSmokeTest();
    static Engine* current;
    static int driveIndex(const std::string& name);
    static int hostDiskIndex(int slot);
};
} // namespace v8088android
