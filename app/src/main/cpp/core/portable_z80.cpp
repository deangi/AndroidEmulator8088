#include "portable_z80.h"

#include <algorithm>
#include <cstring>
#include <unistd.h>

namespace vz80 {

PortableZ80::PortableZ80() { configureCpu(); }

PortableZ80::~PortableZ80() { for (int fd : disks_) if (fd >= 0) close(fd); }

bool PortableZ80::bootDisks(const std::array<int, 4>& fds, uint8_t hddMask, int bootDrive) {
    std::lock_guard lock(mutex_);
    if (bootDrive < 0 || bootDrive >= 4 || fds[bootDrive] < 0) return false;
    std::array<uint8_t, 128> boot{};
    if (pread(fds[bootDrive], boot.data(), boot.size(), 0) != boot.size()) return false;
    std::array<int, 4> next{{-1, -1, -1, -1}};
    for (size_t i = 0; i < fds.size(); ++i) {
        if (fds[i] >= 0 && (next[i] = dup(fds[i])) < 0) {
            for (int fd : next) if (fd >= 0) close(fd);
            return false;
        }
    }
    for (int fd : disks_) if (fd >= 0) close(fd);
    disks_ = next;
    hddMask_ = hddMask; hddPatched_ = false; printerPatched_ = false; dphBase_ = 0;
    diskError_.clear();
    ram_.fill(0);
    input_.clear(); consoleOutput_.clear(); printerOutput_.clear();
    diskDrive_ = 0; diskTrack_ = 0; diskSector_ = 1; diskDma_ = 0x80;
    for (uint8_t i = 0; i < 6; ++i) {
        const auto addr = 0xF02B + 3 * i;
        ram_[addr] = 0xD3; ram_[addr + 1] = 0xC0 + i; ram_[addr + 2] = 0xC9;
    }
    std::copy(boot.begin(), boot.end(), ram_.begin() + 0x80);
    configureCpu(); cpu_.pc = 0x80;
    return true;
}

bool PortableZ80::replaceDisks(const std::array<int, 4>& fds, uint8_t hddMask) {
    std::lock_guard lock(mutex_);
    if (!hddPatched_) {
        diskError_ = "CP/M disk tables are not ready for a live change";
        return false;
    }
    std::array<int, 4> next{{-1, -1, -1, -1}};
    for (size_t i = 0; i < fds.size(); ++i) {
        if (fds[i] >= 0 && (next[i] = dup(fds[i])) < 0) {
            for (int fd : next) if (fd >= 0) close(fd);
            diskError_ = "Could not open replacement disk handles";
            return false;
        }
    }
    for (int fd : disks_) if (fd >= 0) close(fd);
    disks_ = next;
    const uint8_t geometryChanged = hddMask_ ^ hddMask;
    hddMask_ = hddMask;
    applyDiskDpbs(geometryChanged);
    diskError_.clear();
    return true;
}

void PortableZ80::configureCpu() {
    z80_init(&cpu_);
    cpu_.userdata = this;
    cpu_.read_byte = &PortableZ80::readByte;
    cpu_.write_byte = &PortableZ80::writeByte;
    cpu_.port_in = &PortableZ80::portIn;
    cpu_.port_out = &PortableZ80::portOut;
}

void PortableZ80::reset(uint16_t entryPc) {
    std::lock_guard lock(mutex_);
    configureCpu();
    cpu_.pc = entryPc;
}

void PortableZ80::load(uint16_t address, const uint8_t* bytes, size_t length) {
    std::lock_guard lock(mutex_);
    const auto available = kRamSize - address;
    std::memcpy(ram_.data() + address, bytes, std::min(length, available));
}

unsigned long PortableZ80::runCycles(unsigned long minCycles) {
    std::lock_guard lock(mutex_);
    const auto start = cpu_.cyc;
    while (cpu_.cyc - start < minCycles) z80_step(&cpu_);
    return cpu_.cyc - start;
}

unsigned long PortableZ80::runInstructions(unsigned long instructionCount) {
    std::lock_guard lock(mutex_);
    const auto start = cpu_.cyc;
    for (unsigned long instruction = 0; instruction < instructionCount; ++instruction) z80_step(&cpu_);
    if (!hddPatched_) hddPatched_ = installHddDpbs();
    if (!printerPatched_) printerPatched_ = installPrinterBios();
    return cpu_.cyc - start;
}

// The donor routes CP/M LIST/LISTST through the 88-LPC ports 03/02.
bool PortableZ80::installPrinterBios() {
    if (!hddPatched_ || ram_[0] != 0xC3) return false;
    const uint16_t wboot = ram_[1] | (ram_[2] << 8);
    if (wboot < 0x8003 || wboot > 0xF000) return false;
    const auto bios = wboot - 3;
    if (ram_[bios + 15] != 0xC3 || ram_[bios + 45] != 0xC3) return false;
    const uint8_t listst[] = {0xDB,0x02,0xE6,0x02,0xC8,0x3E,0xFF,0xC9};
    const uint8_t list[] = {0xCD,0x40,0xF0,0xCA,0x48,0xF0,0x79,0xE6,0x7F,0xD3,0x03,0xC9};
    std::copy(std::begin(listst), std::end(listst), ram_.begin() + 0xF040);
    std::copy(std::begin(list), std::end(list), ram_.begin() + 0xF048);
    ram_[bios + 16] = 0x48; ram_[bios + 17] = 0xF0;
    ram_[bios + 46] = 0x40; ram_[bios + 47] = 0xF0;
    ram_[3] = (ram_[3] & 0x3F) | 0x80;
    return true;
}

// Mirrors the donor's CP/M 2.2 DPH/DPB and sector translation patch.
// Wait until CP/M has installed its page-zero warm-boot vector and BIOS.
bool PortableZ80::installHddDpbs() {
    if (ram_[0] != 0xC3) return false;
    auto rd16 = [this](unsigned a) { return uint16_t(ram_[a] | (ram_[a + 1] << 8)); };
    auto wr16 = [this](unsigned a, uint16_t v) { ram_[a] = uint8_t(v); ram_[a + 1] = uint8_t(v >> 8); };
    const uint16_t wboot = rd16(1);
    if (wboot < 0x8003 || wboot > 0xF000) return false;
    int dph0 = -1;
    for (int a = 0x8000; a <= 0xF000; ++a) {
        if (ram_[a] == 0x21 && ram_[a+3] == 0x5F && ram_[a+4] == 0x16 &&
            ram_[a+5] == 0 && ram_[a+6] == 0x19 && ram_[a+7] == 0xC9) {
            auto candidate = rd16(a+1);
            if (candidate >= 0x8000 && candidate <= 0xF000) { dph0 = candidate; break; }
        }
    }
    if (dph0 < 0) return false;
    dphBase_ = static_cast<uint16_t>(dph0);
    for (int d = 0; d < 4; ++d)
        std::copy_n(ram_.begin() + dphBase_ + d * 16, 16, originalDph_[d].begin());
    constexpr uint16_t xlt = 0xF110, dpb = 0xF130, alv = 0xF140, sectran = 0xF100;
    const uint8_t hddDpb[] = {0x20,0,5,0x1F,1,0xFD,7,0xFF,1,0xF0,0,0,0,2,0};
    for (int i = 0; i < 32; ++i) ram_[xlt+i] = i+1;
    std::copy(std::begin(hddDpb), std::end(hddDpb), ram_.begin()+dpb);
    const uint16_t jump = wboot - 3 + 48;
    if (ram_[jump] != 0xC3) return false;
    const auto original = rd16(jump+1);
    const uint8_t stub[] = {0x7A,0xFE,0xF1,0x20,0x0A,0x7B,0xFE,0x10,0x20,0x05,0x26,0,0x69,0x2C,0xC9,0xC3,0,0};
    std::copy(std::begin(stub), std::end(stub), ram_.begin()+sectran);
    wr16(sectran+16, original);
    wr16(jump+1, sectran);
    applyDiskDpbs(0x0F);
    return true;
}

void PortableZ80::applyDiskDpbs(uint8_t changedMask) {
    constexpr uint16_t xlt = 0xF110, dpb = 0xF130, alv = 0xF140;
    auto wr16 = [this](unsigned a, uint16_t v) { ram_[a] = uint8_t(v); ram_[a + 1] = uint8_t(v >> 8); };
    for (int d = 0; d < 4; ++d) {
        if (!(changedMask & (1u << d))) continue;
        const auto dph = dphBase_ + d*16;
        std::copy(originalDph_[d].begin(), originalDph_[d].end(), ram_.begin() + dph);
        if (!(hddMask_ & (1u << d))) continue;
        std::fill(ram_.begin()+alv+d*256, ram_.begin()+alv+(d+1)*256, 0);
        wr16(dph, xlt); wr16(dph+10, dpb); wr16(dph+12, 0); wr16(dph+14, alv+d*256);
    }
}

void PortableZ80::enqueueInput(uint8_t byte) {
    std::lock_guard lock(mutex_);
    if (input_.size() < kInputCapacity) input_.push_back(byte);
}

bool PortableZ80::hasPendingConsoleInput() {
    std::lock_guard lock(mutex_);
    return !input_.empty();
}

std::vector<uint8_t> PortableZ80::drain(std::deque<uint8_t>& queue) {
    std::vector<uint8_t> result(queue.begin(), queue.end());
    queue.clear();
    return result;
}

std::vector<uint8_t> PortableZ80::drainConsole() {
    std::lock_guard lock(mutex_);
    return drain(consoleOutput_);
}

std::vector<uint8_t> PortableZ80::drainPrinter() {
    std::lock_guard lock(mutex_);
    return drain(printerOutput_);
}

std::string PortableZ80::takeDiskError() {
    std::lock_guard lock(mutex_);
    auto result = diskError_;
    diskError_.clear();
    return result;
}

uint8_t PortableZ80::readByte(void* userdata, uint16_t address) {
    return static_cast<PortableZ80*>(userdata)->ram_[address];
}

void PortableZ80::writeByte(void* userdata, uint16_t address, uint8_t value) {
    static_cast<PortableZ80*>(userdata)->ram_[address] = value;
}

uint8_t PortableZ80::portIn(z80* cpu, uint8_t port) {
    auto* self = static_cast<PortableZ80*>(cpu->userdata);
    switch (port) {
        case 0x00: { // 88-SIO status: ready bits are inverted.
            uint8_t status = self->consoleOutput_.size() < kOutputCapacity ? 0x7F : 0xFF;
            if (!self->input_.empty()) status &= static_cast<uint8_t>(~0x01);
            return status;
        }
        case 0x01:
            if (self->input_.empty()) return 0;
            { const auto value = self->input_.front(); self->input_.pop_front(); return value; }
        case 0x02: // 88-LPC printer ready.
            return self->printerOutput_.size() < kOutputCapacity ? 0x02 : 0x00;
        case 0x10: { // 88-2SIO status: ready bits are non-inverted.
            uint8_t status = self->consoleOutput_.size() < kOutputCapacity ? 0x02 : 0x00;
            if (!self->input_.empty()) status |= 0x01;
            return status;
        }
        case 0x11:
            if (self->input_.empty()) return 0;
            { const auto value = self->input_.front(); self->input_.pop_front(); return value; }
        default:
            return 0xFF;
    }
}

void PortableZ80::portOut(z80* cpu, uint8_t port, uint8_t value) {
    auto* self = static_cast<PortableZ80*>(cpu->userdata);
    switch (port) {
        case 0xC0: self->diskDrive_ = cpu->c; return;
        case 0xC1: self->diskTrack_ = (self->diskDrive_ < 4 && (self->hddMask_ & (1u << self->diskDrive_))) ? ((cpu->b << 8) | cpu->c) : cpu->c; return;
        case 0xC2: self->diskSector_ = cpu->c; return;
        case 0xC3: self->diskDma_ = (cpu->b << 8) | cpu->c; return;
        case 0xC4:
        case 0xC5: {
            const bool hdd = self->diskDrive_ < 4 && (self->hddMask_ & (1u << self->diskDrive_));
            const unsigned tracks = hdd ? 2048 : 77, sectors = hdd ? 32 : 26;
            bool ok = self->diskDrive_ < 4 && self->disks_[self->diskDrive_] >= 0 &&
                self->diskTrack_ < tracks && self->diskSector_ >= 1 && self->diskSector_ <= sectors &&
                (!hdd || self->hddPatched_);
            std::array<uint8_t, 128> sector{};
            if (ok) {
                const auto offset = (self->diskTrack_ * sectors + self->diskSector_ - 1) * 128;
                const int fd = self->disks_[self->diskDrive_];
                if (port == 0xC4) {
                    ok = pread(fd, sector.data(), sector.size(), offset) == sector.size();
                    if (ok) for (int i = 0; i < 128; ++i) self->ram_[static_cast<uint16_t>(self->diskDma_ + i)] = sector[i];
                } else {
                    for (int i = 0; i < 128; ++i) sector[i] = self->ram_[static_cast<uint16_t>(self->diskDma_ + i)];
                    ok = pwrite(fd, sector.data(), sector.size(), offset) == sector.size();
                    if (ok) ok = fdatasync(fd) == 0;
                }
            }
            if (!ok && self->diskDrive_ < 4 && self->disks_[self->diskDrive_] >= 0) {
                self->diskError_ = std::string(port == 0xC4 ? "Read" : "Write") + " failed on " +
                    char('A' + self->diskDrive_) + ": track " + std::to_string(self->diskTrack_) +
                    ", sector " + std::to_string(self->diskSector_) +
                    (hdd && !self->hddPatched_ ? " (CP/M HDD support not initialized)" : "");
            }
            cpu->a = ok ? 0 : 1; cpu->zf = ok;
            return;
        }
    }
    if ((port == 0x01 || port == 0x11) && self->consoleOutput_.size() < kOutputCapacity) {
        self->consoleOutput_.push_back(value);
    } else if (port == 0x03 && self->printerOutput_.size() < kOutputCapacity) {
        self->printerOutput_.push_back(value & 0x7F);
    }
}

}  // namespace vz80
