// (C) 2018-2026 by Folkert van Heusden
// Released under MIT license

#pragma once

#include "gen.h"
#if IS_POSIX
#include <ArduinoJson.h>
#endif
#include <mutex>
#include <optional>
#include <stdint.h>
#include <stdio.h>
#include <string>
#include <thread>
#include <vector>

#include "bus.h"
#include "console.h"


#define PDP11TTY_TKS		0177560	// reader status
#define PDP11TTY_TKB		0177562	// reader buffer
#define PDP11TTY_TPS		0177564	// puncher status
#define PDP11TTY_TPB		0177566	// puncher buffer
#define PDP11TTY_BASE	PDP11TTY_TKS
#define PDP11TTY_END	(PDP11TTY_TPB + 2)

class memory;

class tty
{
private:
	console *const c      { nullptr };
	bus     *const b      { nullptr };

	uint16_t registers[4] { 0 };
	bool rx_irq_asserted { false };
	bool tx_irq_asserted { false };
	bool tx_ready_reported { false };
	bool tx_busy { false };
	uint64_t tx_ready_at_instruction { 0 };
	uint8_t tx_pending_byte { 0 };

	void update_rx_interrupt();
	void update_tx_interrupt();

public:
	tty(console *const c, bus *const b);
	virtual ~tty();

	void notify_rx();

#if IS_POSIX
	JsonDocument serialize();
	static tty *deserialize(const JsonVariantConst j, bus *const b, console *const cnsl);
#endif

	void reset(const bool hard);
	/** Complete transmitter output only when its guest-instruction deadline passes. */
	void service_deferred();
	std::optional<uint64_t> next_event_instruction() const {
		return tx_busy ? std::optional<uint64_t>(tx_ready_at_instruction) : std::nullopt;
	}
	// True when TX is in flight, RX IE is armed, or transmitter DONE is clear.
	bool needs_deferred_service() const;

	uint8_t read_byte(const uint16_t addr);
	uint16_t read_word(const uint16_t addr);

	void write_byte(const uint16_t addr, const uint8_t v);
	void write_word(const uint16_t addr, uint16_t v);

	void operator()();
};
