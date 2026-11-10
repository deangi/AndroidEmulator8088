#include "android_pdp11.h"

#include <algorithm>
#include <cstring>
#include <unistd.h>

#include "tty.h"
#include "disk_backend.h"
#include "kw11-l.h"
#include "loaders.h"
#include "rk05.h"
#include "rl02.h"

namespace pdp11android {

namespace {

// Android's Storage Access Framework gives us an already-authorized file
// descriptor.  Keep the native RK05 backend descriptor-based; reopening a
// /proc/self/fd path is not permitted on every Android build.
class AndroidFdDiskBackend final : public disk_backend {
public:
    explicit AndroidFdDiskBackend(const int source) : source(source) {}
    ~AndroidFdDiskBackend() override { if (fd >= 0) close(fd); }

    std::string get_identifier() const override { return "android-fd"; }
    void show_state(console* const cnsl) const override { cnsl->put_string_lf("Android document disk"); }

    bool begin(const bool) override {
        fd = dup(source);
        if (fd < 0) return false;
        const auto end = lseek(fd, 0, SEEK_END);
        if (end < 0) return false;
        size = static_cast<uint64_t>(end);
        return true;
    }

    bool read(const off_t offset, const size_t count, uint8_t* const target, const size_t) override {
        return pread(fd, target, count, offset) == static_cast<ssize_t>(count);
    }

    bool write(const off_t offset, const size_t count, const uint8_t* const sourceBytes, const size_t) override {
        return pwrite(fd, sourceBytes, count, offset) == static_cast<ssize_t>(count);
    }

private:
    const int source;
    int fd { -1 };
};

} // namespace

void AndroidConsole::put_char_ll(const char value)
{
    std::lock_guard<std::mutex> lock(outputLock);
    output.push_back(static_cast<uint8_t>(value));
}

void AndroidConsole::put_string_lf(const std::string& value)
{
    put_string(value);
    put_char_ll('\n');
}

std::vector<uint8_t> AndroidConsole::drainOutput()
{
    std::lock_guard<std::mutex> lock(outputLock);
    std::vector<uint8_t> result(output.begin(), output.end());
    output.clear();
    return result;
}

Engine::Engine()
{
    // 31 x 8 KiB is the upstream non-embedded PDP-11 default (248 KiB).
    machine.set_memory_size(DEFAULT_N_PAGES);
    auto* processor = new cpu(&machine, &stopEvent);
    processor->set_console(&console);
    machine.add_cpu(processor);
    machine.add_tty(new tty(&console, &machine));
    console.set_bus(&machine);
    // bus constructs the standard KW11-L.  Android advances it from guest
    // instruction time rather than starting a host-timer thread.
    machine.getKW11_L()->set_interrupt_frequency(60);
    machine.getKW11_L()->attach_console(&console);
    zeroMemory();
    reset();
}

void Engine::reset(const uint16_t entryPc)
{
    // A PDP-11 INIT/reset affects CPU and I/O state, not installed RAM.
    // Android exposes clearMemory() as an explicit, separate host action.
    machine.reset(false);
    machine.getCpu()->reset();
    machine.getCpu()->setPC(entryPc);
}

void Engine::setProgramCounter(const uint16_t entryPc)
{
    machine.getCpu()->setPC(entryPc);
}

bool Engine::config(const std::string& parameter)
{
    const auto separator = parameter.find('=');
    if (separator == std::string::npos)
        return false;
    const auto key = parameter.substr(0, separator);
    const auto value = parameter.substr(separator + 1);
    if (key != "kwp_enabled")
        return false;
    if (value == "true" || value == "1" || value == "yes" || value == "on") {
        kwpEnabled = true;
        return true;
    }
    if (value == "false" || value == "0" || value == "no" || value == "off") {
        kwpEnabled = false;
        return true;
    }
    return false;
}

uint64_t Engine::runInstructions(const uint64_t count)
{
    const uint64_t before = machine.getCpu()->get_instructions_executed_count();
    for (uint64_t index = 0; index < count; ++index) {
        machine.getCpu()->step_fast();
        // Match the vpdp target rate: approximately one 60 Hz line-clock
        // tick for every 6,000 PDP-11 instructions executed.
        if (++kw11InstructionTicks >= 6000) {
            kw11InstructionTicks = 0;
            machine.getKW11_L()->tick();
        }
        if (auto* terminal = machine.getTty())
            terminal->service_deferred();
        if (auto* rl = machine.getRL02())
            rl->service_deferred();
        if (stopEvent.load() != EVENT_NONE)
            break;
    }
    return machine.getCpu()->get_instructions_executed_count() - before;
}

void Engine::setMemorySizeBytes(uint32_t bytes)
{
    constexpr uint32_t pageSize = 8192;
    const uint32_t pages = std::clamp((bytes + pageSize - 1) / pageSize, 1u, 512u);
    machine.set_memory_size(static_cast<int>(pages));
    reset();
}

uint32_t Engine::memorySizeBytes() const { return machine.get_memory_size(); }

void Engine::zeroMemory() { clearMemory(0); }

void Engine::clearMemory(const uint8_t value)
{
    auto* ram = machine.getRAM();
    if (!ram) return;
    std::vector<uint8_t> clear(ram->get_memory_size(), value);
    machine.write_physical_block(0, clear.data(), static_cast<uint32_t>(clear.size()));
}

void Engine::load(const uint32_t address, const uint8_t* bytes, const size_t count)
{
    if (bytes && count)
        machine.write_physical_block(address, bytes, static_cast<uint32_t>(count));
}

bool Engine::bootRk05(const int descriptor)
{
    if (descriptor < 0)
        return false;

    auto* image = new AndroidFdDiskBackend(descriptor);
    if (!image->begin(false)) {
        delete image;
        return false;
    }

    auto* controller = new rk05(&machine, console.get_disk_read_activity_flag(), console.get_disk_write_activity_flag());
    controller->access_disk_backends()->push_back(image);
    controller->begin();
    machine.add_rk05(controller);

    const auto entry = set_boot_loader(&machine, BL_RK05);
    if (!entry.has_value())
        return false;
    reset(*entry);
    return true;
}

bool Engine::mountRl(const int unit, const int descriptor)
{
    if (unit < 0 || unit > 3 || descriptor < 0)
        return false;

    auto* image = new AndroidFdDiskBackend(descriptor);
    if (!image->begin(false)) {
        delete image;
        return false;
    }

    // Match vpdp1170's deliberately tolerant RL media recognition.  Some
    // historical images contain a small trailer or sparse-file padding.
    constexpr uint64_t rl01Bytes = 5242880u;
    constexpr uint64_t rl02Bytes = 10485760u;
    constexpr uint64_t tolerancePercent = 20u;
    const auto withinTolerance = [](const uint64_t bytes, const uint64_t nominal) {
        const uint64_t delta = nominal * tolerancePercent / 100u;
        return bytes >= nominal - delta && bytes <= nominal + delta;
    };
    if (!withinTolerance(image->get_size(), rl01Bytes) &&
        !withinTolerance(image->get_size(), rl02Bytes)) {
        delete image;
        return false;
    }

    auto* controller = machine.getRL02();
    if (!controller) {
        controller = new rl02(&machine, console.get_disk_read_activity_flag(), console.get_disk_write_activity_flag());
        controller->begin();
        machine.add_rl02(controller);
    }
    auto* drives = controller->access_disk_backends();
    if (drives->size() < 4)
        drives->resize(4, nullptr);
    delete drives->at(unit);
    drives->at(unit) = image;
    return true;
}

bool Engine::dismountRl(const int unit)
{
    if (unit < 0 || unit > 3 || !machine.getRL02())
        return false;
    auto* drives = machine.getRL02()->access_disk_backends();
    if (static_cast<size_t>(unit) >= drives->size())
        return true;
    delete drives->at(unit);
    drives->at(unit) = nullptr;
    return true;
}

bool Engine::bootRl(const int unit)
{
    if (unit < 0 || unit > 3 || !machine.getRL02())
        return false;
    const auto* drives = machine.getRL02()->access_disk_backends();
    if (static_cast<size_t>(unit) >= drives->size() || drives->at(unit) == nullptr)
        return false;

    const auto entry = set_boot_loader(&machine, BL_RL02);
    if (!entry.has_value())
        return false;
    reset(*entry);
    return true;
}

void Engine::enqueueInput(const uint8_t value) { console.enqueue_input(value); }
bool Engine::hasPendingInput() { return console.poll_char(); }
std::vector<uint8_t> Engine::drainConsole() { return console.drainOutput(); }

} // namespace pdp11android
