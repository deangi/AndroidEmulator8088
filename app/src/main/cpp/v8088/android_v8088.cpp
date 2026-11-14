#include "android_v8088.h"
#include "bios_blob.h"
#include "cpu8086.h"
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
void v8088_set_mem(unsigned char*); void v8088_init(unsigned int); void v8088_reset(void);
unsigned int v8088_run(unsigned int); unsigned short v8088_get_ip(void); unsigned short v8088_get_reg16(int);
void v8088_set_boot_drive(unsigned char);
extern unsigned short reg_ip;
}
namespace v8088android {
Engine* Engine::current = nullptr;
Engine::Engine() : ram(V8088_RAM_SIZE) {
    current = this;

    // v8088_init builds its decoder tables from the reset BIOS at F010:0000.
    // The ESP32 implementation preloads this image before initializing the core;
    // without it, the engine sees an all-zero BIOS and cannot execute the smoke
    // program (or a configured boot image) correctly.
    std::memcpy(ram.data() + 0xF0100, bios_blob, bios_blob_len);

    v8088_set_mem(ram.data());
    v8088_init(0);
    reset();
}
Engine::~Engine() { clearProfileDevices(); if (current == this) current = nullptr; }
Engine* Engine::active() { return current; }
void Engine::reset(uint16_t) {
    v8088_reset();
    v8088_set_boot_drive(bootDrive);
    if (smokeTest) installConsoleSmokeTest();
    executed = 0;
}
bool Engine::config(const std::string& parameter) {
    if (parameter == "smoke_test=true") { smokeTest = true; reset(); return true; }
    if (parameter == "smoke_test=false") { smokeTest = false; reset(); return true; }
    constexpr char prefix[] = "boot_drive=";
    if (parameter.rfind(prefix, 0) == 0 && parameter.size() == sizeof(prefix)) {
        const auto drive = static_cast<char>(std::toupper(static_cast<unsigned char>(parameter.back())));
        // The 8086tiny BIOS supports its floppy (A:) and hard-disk (C:)
        // devices. B: and D: remain mounted as future data-drive targets.
        if (drive != 'A' && drive != 'C') return false;
        bootDrive = drive == 'A' ? 0x00 : 0x80;
        smokeTest = false;
        reset();
        return true;
    }
    return false;
}
void Engine::installConsoleSmokeTest() {
    // 8088 program at reset vector F000:0100.  INT F0 is our test-only host
    // service: AH=0 writes AL, AH=1 polls one input byte and echoes it.
    static constexpr uint8_t program[] = {
        0xB4,0x00, 0xB0,'H',0xCD,0xF0, 0xB0,'e',0xCD,0xF0,
        0xB0,'l',0xCD,0xF0, 0xB0,'l',0xCD,0xF0, 0xB0,'o',0xCD,0xF0,
        0xB0,' ',0xCD,0xF0, 0xB0,'f',0xCD,0xF0, 0xB0,'r',0xCD,0xF0,
        0xB0,'o',0xCD,0xF0, 0xB0,'m',0xCD,0xF0, 0xB0,' ',0xCD,0xF0,
        0xB0,'8',0xCD,0xF0, 0xB0,'0',0xCD,0xF0, 0xB0,'8',0xCD,0xF0,
        0xB0,'8',0xCD,0xF0, 0xB0,'\n',0xCD,0xF0,
        0xB4,0x01, 0xCD,0xF0, 0xEB,0xFC,
    };
    std::memcpy(ram.data() + 0xF0100, program, sizeof(program));
}
void Engine::setProgramCounter(uint16_t pc) { reg_ip = pc; }
uint16_t Engine::lastProgramCounter() const { return v8088_get_ip(); }
uint64_t Engine::runInstructions(uint64_t count) { uint64_t done=0; while (done<count) { const auto n=v8088_run(static_cast<unsigned int>(std::min<uint64_t>(count-done, 16384))); done+=n; if (!n) break; } executed+=done; return done; }
uint32_t Engine::memorySizeBytes() const { return static_cast<uint32_t>(ram.size()); }
void Engine::zeroMemory() { std::fill(ram.begin(), ram.end(), 0); }
void Engine::clearMemory(uint8_t value) { std::fill(ram.begin(), ram.end(), value); }
void Engine::load(uint32_t address,const uint8_t* bytes,size_t count) { if (address < ram.size()) std::memcpy(ram.data()+address,bytes,std::min(count,ram.size()-address)); }
void Engine::clearProfileDevices() { for (auto& fd:disks) { if(fd>=0) close(fd); fd=-1; } }
int Engine::driveIndex(const std::string& raw) { std::string n=raw; std::transform(n.begin(),n.end(),n.begin(),::toupper); if(n.size()==1 && n[0]>='A'&&n[0]<='D') return n[0]-'A'; if(n.size()==3 && n[0]=='D'&&n[1]=='R'&&n[2]>='0'&&n[2]<='3') return n[2]-'0'; return -1; }
int Engine::hostDiskIndex(int slot) { return slot == 0 ? 2 : slot == 1 ? 0 : -1; }
int Engine::biosDriveIndex(uint8_t drive) { return drive < 2 ? drive : drive >= 0x80 && drive <= 0x81 ? drive - 0x80 + 2 : -1; }
uint64_t Engine::mountedDriveBytes(int index) const { struct stat info {}; return index >= 0 && index < static_cast<int>(disks.size()) && disks[index] >= 0 && fstat(disks[index], &info) == 0 && info.st_size > 0 ? static_cast<uint64_t>(info.st_size) : 0; }
uint32_t Engine::diskSectors(int slot) const { return static_cast<uint32_t>(mountedDriveBytes(hostDiskIndex(slot)) / 512); }
bool Engine::mountDrive(const std::string& name,int source) { int slot=driveIndex(name); if(slot<0||source<0)return false; struct stat info {}; if(fstat(source,&info) || info.st_size<=0 || info.st_size%512)return false; if(slot<2 && info.st_size!=1474560)return false; int copy=dup(source); if(copy<0)return false; if(disks[slot]>=0)close(disks[slot]); disks[slot]=copy; return true; }
bool Engine::dismountDrive(const std::string& name) { int slot=driveIndex(name); if(slot<0)return false; if(disks[slot]>=0)close(disks[slot]); disks[slot]=-1; return true; }
std::string Engine::enumerateDrives() const {
    std::ostringstream out;
    for (int i = 0; i < 4; i++) {
        out << char('A' + i) << " 8088 " << (i < 2 ? "floppy" : "harddisk")
            << " state=" << (disks[i] >= 0 ? "mounted" : "empty");
        if (const auto bytes = mountedDriveBytes(i); bytes > 0) out << " size=" << bytes;
        out << "\r\n";
    }
    return out.str();
}
std::string Engine::diagnostics() const { std::ostringstream out; out<<"8088 diagnostics\r\nIP="<<std::hex<<v8088_get_ip()<<" CS="<<v8088_get_reg16(V8088_CS)<<" instructions="<<std::dec<<executed<<"\r\n"; return out.str(); }
void Engine::enqueueInput(uint8_t value) { std::lock_guard<std::mutex> g(lock); input.push_back(value); }
bool Engine::hasPendingInput() const { std::lock_guard<std::mutex> g(lock); return !input.empty(); }
std::vector<uint8_t> Engine::drainConsole() { std::lock_guard<std::mutex> g(lock); std::vector<uint8_t> r(output.begin(),output.end());output.clear();return r; }
std::vector<uint8_t> Engine::drainPrinter() { std::lock_guard<std::mutex> g(lock); std::vector<uint8_t> r(printer.begin(),printer.end());printer.clear();return r; }
void Engine::putchar(uint8_t v) { std::lock_guard<std::mutex> g(lock); output.push_back(v); }
int Engine::keyboard(uint8_t* v) { std::lock_guard<std::mutex> g(lock); if(input.empty())return 0;*v=input.front();input.pop_front();return 1; }
int Engine::diskRead(int slot,uint32_t lba,void* target,uint32_t bytes) { int d=hostDiskIndex(slot); if(d<0||disks[d]<0||bytes==0||bytes%512||lba>diskSectors(slot)||bytes/512>diskSectors(slot)-lba)return -1; return pread(disks[d],target,bytes,static_cast<off_t>(lba)*512u)==static_cast<ssize_t>(bytes)?static_cast<int>(bytes):-1; }
int Engine::diskWrite(int slot,uint32_t lba,const void* source,uint32_t bytes) { int d=hostDiskIndex(slot); if(d<0||disks[d]<0||bytes==0||bytes%512||lba>diskSectors(slot)||bytes/512>diskSectors(slot)-lba)return -1; return pwrite(disks[d],source,bytes,static_cast<off_t>(lba)*512u)==static_cast<ssize_t>(bytes)?static_cast<int>(bytes):-1; }
bool Engine::biosDriveMounted(uint8_t biosDrive) const { const auto index=biosDriveIndex(biosDrive); return index >= 0 && disks[index] >= 0; }
uint8_t Engine::mountedBiosDrives(bool hardDisks) const { const int first=hardDisks ? 2 : 0; return static_cast<uint8_t>((disks[first] >= 0) + (disks[first + 1] >= 0)); }
int Engine::biosDiskRead(uint8_t biosDrive,uint32_t lba,void* target,uint32_t bytes) { const auto index=biosDriveIndex(biosDrive); if(index<0||disks[index]<0||bytes==0||bytes%512)return -1; struct stat info {}; if(fstat(disks[index],&info)||lba>static_cast<uint64_t>(info.st_size/512)||bytes/512>static_cast<uint64_t>(info.st_size/512)-lba)return -1; return pread(disks[index],target,bytes,static_cast<off_t>(lba)*512u)==static_cast<ssize_t>(bytes)?static_cast<int>(bytes):-1; }
int Engine::biosDiskWrite(uint8_t biosDrive,uint32_t lba,const void* source,uint32_t bytes) { const auto index=biosDriveIndex(biosDrive); if(index<0||disks[index]<0||bytes==0||bytes%512)return -1; struct stat info {}; if(fstat(disks[index],&info)||lba>static_cast<uint64_t>(info.st_size/512)||bytes/512>static_cast<uint64_t>(info.st_size/512)-lba)return -1; return pwrite(disks[index],source,bytes,static_cast<off_t>(lba)*512u)==static_cast<ssize_t>(bytes)?static_cast<int>(bytes):-1; }
}
extern "C" {
extern unsigned char* regs8;
extern unsigned short* regs16;
extern unsigned char* mem;
void v8088_host_putchar(unsigned char c){if(auto* e=v8088android::Engine::active())e->putchar(c);} int v8088_host_keyboard_poll(unsigned char* c){auto*e=v8088android::Engine::active();return e?e->keyboard(c):0;}
unsigned int v8088_host_disk_sectors(int s){auto*e=v8088android::Engine::active();return e?e->diskSectors(s):0;} int v8088_host_disk_read(int s,unsigned int l,void*b,unsigned int n){auto*e=v8088android::Engine::active();return e?e->diskRead(s,l,b,n):-1;} int v8088_host_disk_write(int s,unsigned int l,const void*b,unsigned int n){auto*e=v8088android::Engine::active();return e?e->diskWrite(s,l,b,n):-1;}
void v8088_host_get_rtc(unsigned char* d){std::memset(d,0,36);} unsigned int v8088_host_micros(){return static_cast<unsigned int>(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());} unsigned char v8088_host_cmos_read(unsigned char){return 0;} void v8088_host_cmos_write(unsigned char,unsigned char){} unsigned char v8088_host_lpt_in(unsigned){return 0xff;} void v8088_host_lpt_out(unsigned,unsigned char){}
int v8088_host_intercept(unsigned char number) {
    auto* engine = v8088android::Engine::active();
    if (!engine) return 0;
    if (number == 0xF0) {
        if (regs8[1] == 0) engine->putchar(regs8[0]);
        else if (regs8[1] == 1) { unsigned char value; if (engine->keyboard(&value)) engine->putchar(value); }
        return 1;
    }
    if (number == 0x11) {
        // DOS asks INT 11h during startup to determine how many floppy
        // drive letters to create. Without this, B: aliases A: even though
        // the INT 13h handler below has a separate mounted image for it.
        const auto floppyCount = std::max<uint8_t>(1, engine->mountedBiosDrives(false));
        regs16[0] = static_cast<uint16_t>(0x0021 | ((floppyCount - 1) << 6));
        regs8[40] = 0;
        return 1;
    }
    if (number != 0x13) return 0;

    // The stock 8086tiny BIOS only describes one floppy and one fixed disk.
    // Supply the conventional INT 13h interface here so DOS can discover all
    // four profile drives (A:, B:, C:, and D:).
    constexpr int AL = 0, AH = 1, CL = 2, CH = 3, DL = 4, DH = 5, BL = 6;
    constexpr int AX = 0, CX = 1, DX = 2, BX = 3, ES = 8, CF = 40;
    const auto drive = regs8[DL];
    const auto hardDisk = drive >= 0x80;
    const auto success = [] { regs8[AH] = 0; regs8[CF] = 0; };
    const auto failure = [](uint8_t status) { regs8[AH] = status; regs8[CF] = 1; };

    switch (regs8[AH]) {
        case 0x00: case 0x0C: case 0x10: case 0x11:
            if (engine->biosDriveMounted(drive)) success(); else failure(0x80);
            break;
        case 0x01:
            regs8[AL] = 0; success();
            break;
        case 0x02: case 0x03: {
            if (!engine->biosDriveMounted(drive)) { failure(0x80); break; }
            const uint16_t cylinder = regs8[CH] | (static_cast<uint16_t>(regs8[CL] & 0xC0) << 2);
            const uint8_t sector = regs8[CL] & 0x3F;
            if (sector == 0) { failure(0x04); break; }
            const uint32_t heads = hardDisk ? 1 : 2;
            const uint32_t sectorsPerTrack = hardDisk ? 63 : 18;
            const uint32_t lba = (static_cast<uint32_t>(cylinder) * heads + regs8[DH]) * sectorsPerTrack + sector - 1;
            const uint32_t bytes = static_cast<uint32_t>(regs8[AL]) * 512;
            void* buffer = mem + static_cast<uint32_t>(regs16[ES]) * 16 + regs16[BX];
            const int read = regs8[AH] == 0x02;
            const int result = read ? engine->biosDiskRead(drive, lba, buffer, bytes) : engine->biosDiskWrite(drive, lba, buffer, bytes);
            if (result == static_cast<int>(bytes)) success(); else { regs8[AL] = 0; failure(read ? 0x04 : 0x03); }
            break;
        }
        case 0x08: {
            if (!engine->biosDriveMounted(drive)) { failure(0x80); break; }
            const uint16_t maximumCylinder = hardDisk ? 1023 : 79;
            const uint8_t sectorsPerTrack = hardDisk ? 63 : 18;
            regs8[CH] = maximumCylinder & 0xFF;
            regs8[CL] = ((maximumCylinder >> 2) & 0xC0) | sectorsPerTrack;
            regs8[DH] = hardDisk ? 0 : 1;
            regs8[DL] = engine->mountedBiosDrives(hardDisk);
            if (!hardDisk) regs8[BL] = 0x04;
            success();
            break;
        }
        case 0x15:
            if (!engine->biosDriveMounted(drive)) { regs8[AH] = 0; regs8[CF] = 0; break; }
            regs8[AH] = hardDisk ? 0x03 : 0x02;
            regs8[CF] = 0;
            break;
        case 0x04: case 0x05: case 0x17: case 0x18:
            success();
            break;
        default:
            failure(0x01);
            break;
    }
    return 1;
}
}
