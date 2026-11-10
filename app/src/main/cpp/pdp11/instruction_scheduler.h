#pragma once

#include <cstdint>
#include <functional>
#include <map>

namespace pdp11android {

class InstructionScheduler {
public:
    using Callback = void (*)(void*);
    using Token = uint64_t;

    Token scheduleAfter(uint64_t now, uint64_t delay, Callback callback, void* argument);
    void cancel(Token token);
    uint64_t nextDue() const;
    void deliverDue(uint64_t now);
    uint64_t now() const { return instructionTime; }
    void advance(uint64_t instructions) { instructionTime += instructions; }
    void setActiveStop(uint64_t instruction) { activeStopInstruction = instruction; }
    uint64_t activeStop() const { return activeStopInstruction; }

private:
    struct Event { Token token; Callback callback; void* argument; };
    std::multimap<uint64_t, Event> events;
    Token nextToken { 1 };
    uint64_t activeStopInstruction { UINT64_MAX };
    uint64_t instructionTime { 0 };
};

} // namespace pdp11android
