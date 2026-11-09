#pragma once

#include <array>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include "z80.h"
}

namespace vz80 {

// Platform-neutral Z80 plus Altair-style console/printer port model. Android
// storage, CP/M disk BIOS, UI, and networking deliberately remain outside it.
class PortableZ80 {
public:
    static constexpr size_t kRamSize = 64 * 1024;

    PortableZ80();
    ~PortableZ80();
    bool bootDisks(const std::array<int, 4>& fds, uint8_t hddMask, int bootDrive);
    bool replaceDisks(const std::array<int, 4>& fds, uint8_t hddMask);
    void reset(uint16_t entryPc = 0x0000);
    void load(uint16_t address, const uint8_t* bytes, size_t length);
    unsigned long runCycles(unsigned long minCycles);
    unsigned long runInstructions(unsigned long instructionCount);
    void enqueueInput(uint8_t byte);
    bool hasPendingConsoleInput();
    std::vector<uint8_t> drainConsole();
    std::vector<uint8_t> drainPrinter();
    std::string takeDiskError();

private:
    static constexpr size_t kInputCapacity = 4096;
    static constexpr size_t kOutputCapacity = 8192;

    std::array<uint8_t, kRamSize> ram_{};
    z80 cpu_{};
    std::mutex mutex_;
    std::deque<uint8_t> input_;
    std::deque<uint8_t> consoleOutput_;
    std::deque<uint8_t> printerOutput_;
    std::array<int, 4> disks_{{-1, -1, -1, -1}};
    uint8_t diskDrive_ = 0, diskSector_ = 1;
    uint16_t diskTrack_ = 0, diskDma_ = 0x80;
    uint8_t hddMask_ = 0;
    bool hddPatched_ = false;
    bool printerPatched_ = false;
    uint16_t dphBase_ = 0;
    std::array<std::array<uint8_t, 16>, 4> originalDph_{};
    std::string diskError_;
    bool installHddDpbs();
    bool installPrinterBios();
    void applyDiskDpbs(uint8_t changedMask);

    static uint8_t readByte(void* userdata, uint16_t address);
    static void writeByte(void* userdata, uint16_t address, uint8_t value);
    static uint8_t portIn(z80* cpu, uint8_t port);
    static void portOut(z80* cpu, uint8_t port, uint8_t value);
    void configureCpu();
    static std::vector<uint8_t> drain(std::deque<uint8_t>& queue);
};

}  // namespace vz80
