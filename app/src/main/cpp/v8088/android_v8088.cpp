#include "android_v8088.h"
#include "cpu8086.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <sstream>
#include <unistd.h>

extern "C" {
void v8088_set_mem(unsigned char*); void v8088_init(unsigned int); void v8088_reset(void);
unsigned int v8088_run(unsigned int); unsigned short v8088_get_ip(void); unsigned short v8088_get_reg16(int);
}
namespace v8088android {
Engine* Engine::current = nullptr;
Engine::Engine() : ram(V8088_RAM_SIZE) { current = this; v8088_set_mem(ram.data()); v8088_init(0); reset(); }
Engine::~Engine() { clearProfileDevices(); if (current == this) current = nullptr; }
Engine* Engine::active() { return current; }
void Engine::reset(uint16_t) { v8088_reset(); executed = 0; }
bool Engine::config(const std::string&) { return true; }
void Engine::setProgramCounter(uint16_t pc) { cpu_set_cs_ip(0, pc); }
uint16_t Engine::lastProgramCounter() const { return v8088_get_ip(); }
uint64_t Engine::runInstructions(uint64_t count) { uint64_t done=0; while (done<count) { const auto n=v8088_run(static_cast<unsigned int>(std::min<uint64_t>(count-done, 16384))); done+=n; if (!n) break; } executed+=done; return done; }
uint32_t Engine::memorySizeBytes() const { return static_cast<uint32_t>(ram.size()); }
void Engine::zeroMemory() { std::fill(ram.begin(), ram.end(), 0); }
void Engine::clearMemory(uint8_t value) { std::fill(ram.begin(), ram.end(), value); }
void Engine::load(uint32_t address,const uint8_t* bytes,size_t count) { if (address < ram.size()) std::memcpy(ram.data()+address,bytes,std::min(count,ram.size()-address)); }
void Engine::clearProfileDevices() { for (auto& fd:disks) { if(fd>=0) close(fd); fd=-1; } }
int Engine::driveIndex(const std::string& raw) { std::string n=raw; std::transform(n.begin(),n.end(),n.begin(),::toupper); if(n.size()==1 && n[0]>='A'&&n[0]<='D') return n[0]-'A'; if(n.size()==3 && n[0]=='D'&&n[1]=='R'&&n[2]>='0'&&n[2]<='3') return n[2]-'0'; return -1; }
bool Engine::mountDrive(const std::string& name,int source) { int slot=driveIndex(name); if(slot<0||source<0)return false; int copy=dup(source); if(copy<0)return false; if(disks[slot]>=0)close(disks[slot]); disks[slot]=copy; return true; }
bool Engine::dismountDrive(const std::string& name) { int slot=driveIndex(name); if(slot<0)return false; if(disks[slot]>=0)close(disks[slot]); disks[slot]=-1; return true; }
std::string Engine::enumerateDrives() const { std::ostringstream out; for(int i=0;i<4;i++) out<<char('A'+i)<<" 8088 "<<(i<2?"floppy=1474560":"harddisk")<<" state="<<(disks[i]>=0?"mounted":"empty")<<"\r\n"; return out.str(); }
std::string Engine::diagnostics() const { std::ostringstream out; out<<"8088 diagnostics\r\nIP="<<std::hex<<v8088_get_ip()<<" CS="<<v8088_get_reg16(V8088_CS)<<" instructions="<<std::dec<<executed<<"\r\n"; return out.str(); }
void Engine::enqueueInput(uint8_t value) { std::lock_guard<std::mutex> g(lock); input.push_back(value); }
bool Engine::hasPendingInput() const { std::lock_guard<std::mutex> g(lock); return !input.empty(); }
std::vector<uint8_t> Engine::drainConsole() { std::lock_guard<std::mutex> g(lock); std::vector<uint8_t> r(output.begin(),output.end());output.clear();return r; }
std::vector<uint8_t> Engine::drainPrinter() { std::lock_guard<std::mutex> g(lock); std::vector<uint8_t> r(printer.begin(),printer.end());printer.clear();return r; }
void Engine::putchar(uint8_t v) { std::lock_guard<std::mutex> g(lock); output.push_back(v); }
int Engine::keyboard(uint8_t* v) { std::lock_guard<std::mutex> g(lock); if(input.empty())return 0;*v=input.front();input.pop_front();return 1; }
int Engine::diskRead(int slot,uint32_t lba,void* target,uint32_t bytes) { int d=(slot==0?2:0); if(d<0||disks[d]<0)return -1; return pread(disks[d],target,bytes,static_cast<off_t>(lba)*512u)==static_cast<ssize_t>(bytes)?static_cast<int>(bytes):-1; }
int Engine::diskWrite(int slot,uint32_t lba,const void* source,uint32_t bytes) { int d=(slot==0?2:0); if(d<0||disks[d]<0)return -1; return pwrite(disks[d],source,bytes,static_cast<off_t>(lba)*512u)==static_cast<ssize_t>(bytes)?static_cast<int>(bytes):-1; }
}
extern "C" {
void v8088_host_putchar(unsigned char c){if(auto* e=v8088android::Engine::active())e->putchar(c);} int v8088_host_keyboard_poll(unsigned char* c){auto*e=v8088android::Engine::active();return e?e->keyboard(c):0;}
unsigned int v8088_host_disk_sectors(int){return 0;} int v8088_host_disk_read(int s,unsigned int l,void*b,unsigned int n){auto*e=v8088android::Engine::active();return e?e->diskRead(s,l,b,n):-1;} int v8088_host_disk_write(int s,unsigned int l,const void*b,unsigned int n){auto*e=v8088android::Engine::active();return e?e->diskWrite(s,l,b,n):-1;}
void v8088_host_get_rtc(unsigned char* d){std::memset(d,0,36);} unsigned int v8088_host_micros(){return static_cast<unsigned int>(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());} unsigned char v8088_host_cmos_read(unsigned char){return 0;} void v8088_host_cmos_write(unsigned char,unsigned char){} unsigned char v8088_host_lpt_in(unsigned){return 0xff;} void v8088_host_lpt_out(unsigned,unsigned char){} int v8088_host_intercept(unsigned char){return 0;}
}
