#include "instruction_scheduler.h"

namespace pdp11android {
InstructionScheduler::Token InstructionScheduler::scheduleAfter(uint64_t now, uint64_t delay, Callback callback, void* argument) {
    const uint64_t due = now + delay;
    const Token token = nextToken++;
    events.emplace(due, Event { token, callback, argument });
    if (due < activeStopInstruction) activeStopInstruction = due;
    return token;
}
void InstructionScheduler::cancel(Token token) {
    for (auto it = events.begin(); it != events.end(); ++it) if (it->second.token == token) { events.erase(it); break; }
}
uint64_t InstructionScheduler::nextDue() const { return events.empty() ? UINT64_MAX : events.begin()->first; }
void InstructionScheduler::deliverDue(uint64_t now) {
    while (!events.empty() && events.begin()->first <= now) {
        auto event = events.begin()->second;
        events.erase(events.begin());
        event.callback(event.argument);
    }
}
} // namespace pdp11android
