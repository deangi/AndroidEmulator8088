// (C) 2018-2026 by Folkert van Heusden
// Released under MIT license

#include <errno.h>
#include <string.h>
#include <unistd.h>
#if defined(ESP32)
#include <Arduino.h>
#endif

#include "tty.h"
#include "cpu.h"
#include "gen.h"
#include "log.h"
#include "memory.h"
#include "utils.h"


const char * const regnames[] = { 
	"reader status ",
	"reader buffer ",
	"puncher status",
	"puncher buffer"
	};

static constexpr uint16_t TTY_DONE = 0200;
static constexpr uint16_t TTY_IE   = 0100;
// A console character remains in flight for one engine service quantum.  The
// host must not reassert TPS.DONE in the same guest instruction that wrote TPB.
static constexpr uint32_t TTY_TX_DELAY_INSTRUCTIONS = 100;

tty::tty(console *const c, bus *const b, pdp11android::InstructionScheduler *const scheduler) :
	c(c),
	b(b), scheduler(scheduler)
{
	reset(true);
	c->set_data_cb_notifier(this);
}

tty::~tty()
{
}

void tty::reset(const bool hard)
{
	(void)hard;
	// SIMH tti_reset / tto_reset (and Unibus INIT): TKS=0, TPS=DONE, CLR_INT.
	memset(registers, 0x00, sizeof registers);
	registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2] = TTY_DONE;
	rx_irq_asserted = false;
	tx_irq_asserted = false;
	tx_ready_reported = true;
	tx_busy = false;
	tx_ready_at_instruction = 0;
	if (scheduler && txEvent) scheduler->cancel(txEvent);
	txEvent = 0;
	tx_pending_byte = 0;
	if (b && b->getCpu()) {
		b->getCpu()->unqueue_interrupt(4, 060);
		b->getCpu()->unqueue_interrupt(4, 064);
	}
}

void tty::complete_tx(void* argument)
{
	auto* self = static_cast<tty*>(argument);
	self->tx_busy = false;
	self->tx_ready_at_instruction = 0;
	self->txEvent = 0;
	self->registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2] |= TTY_DONE;
	self->update_tx_interrupt();
}

void tty::update_rx_interrupt()
{
	const uint16_t tks = registers[(PDP11TTY_TKS - PDP11TTY_BASE) / 2];
	const bool should_assert = (tks & (TTY_DONE | TTY_IE)) == (TTY_DONE | TTY_IE);
	if (should_assert && !rx_irq_asserted) {
		rx_irq_asserted = true;
		b->getCpu()->queue_interrupt(4, 060);
	}
	else if (!should_assert && rx_irq_asserted) {
		rx_irq_asserted = false;
		b->getCpu()->unqueue_interrupt(4, 060);
	}
}

void tty::update_tx_interrupt()
{
	const uint16_t tps = registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2];
	const bool should_assert = (tps & (TTY_DONE | TTY_IE)) == (TTY_DONE | TTY_IE);

	// KL11 transmit DONE is a level which creates one request when it rises.
	// Do not infer that the request was cleared merely because the CPU has
	// accepted vector 064: while DONE remains set, repeatedly queuing it
	// creates an interrupt storm and can prevent early OS startup code from
	// making progress.  A subsequent TPB write clears DONE and releases this
	// latch; the completion transition is then allowed to assert a new request.
	if (should_assert && !tx_irq_asserted && b && b->getCpu()) {
		tx_irq_asserted = true;
		tx_ready_reported = true;
		b->getCpu()->queue_interrupt(4, 064);
	}
	else if (!should_assert && b && b->getCpu()) {
		tx_irq_asserted = false;
		b->getCpu()->unqueue_interrupt(4, 064);
	}
	else if (!should_assert) {
		tx_irq_asserted = false;
	}
}

void tty::service_deferred()
{
	if (!tx_busy) {
		registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2] |= TTY_DONE;
		update_tx_interrupt();
		return;
	}
	const uint64_t now = scheduler ? scheduler->now() : (b && b->getCpu()
		? b->getCpu()->get_instructions_executed_count() : 0);
	if (now < tx_ready_at_instruction)
		return;

	tx_busy = false;
	tx_ready_at_instruction = 0;
	registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2] |= TTY_DONE;
	update_tx_interrupt();
}

bool tty::needs_deferred_service() const
{
	const uint16_t tps = registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2];
	const uint16_t tks = registers[(PDP11TTY_TKS - PDP11TTY_BASE) / 2];
	return tx_busy || (tks & TTY_IE) || !(tps & TTY_DONE);
}

uint8_t tty::read_byte(const uint16_t addr)
{
	uint16_t v = read_word(addr & ~1);
	if (addr & 1)
		return v >> 8;
	return v;
}

void tty::notify_rx()
{
	registers[(PDP11TTY_TKS - PDP11TTY_BASE) / 2] |= TTY_DONE;
	update_rx_interrupt();
}

uint16_t tty::read_word(const uint16_t addr)
{
	const int reg    = (addr - PDP11TTY_BASE) / 2;
	service_deferred();
	uint16_t  vtemp  = registers[reg];
	bool      notify = false;

	if (addr == PDP11TTY_TKS) {
		bool have_char = c->poll_char();

		vtemp &= ~TTY_DONE;
		vtemp |= have_char ? TTY_DONE : 0;
	}
	else if (addr == PDP11TTY_TKB) {
		auto ch = c->wait_char(1);
		if (ch.has_value() == false) {
			vtemp = 0;
			registers[(PDP11TTY_TKS - PDP11TTY_BASE) / 2] &= ~TTY_DONE;
		}
		else {
			vtemp = ch.value() | (parity(ch.value()) << 7);
			if (c->poll_char()) {
				registers[(PDP11TTY_TKS - PDP11TTY_BASE) / 2] |= TTY_DONE;
				notify = true;
			}
			else
				registers[(PDP11TTY_TKS - PDP11TTY_BASE) / 2] &= ~TTY_DONE;
		}
	}
	else if (addr == PDP11TTY_TPS) {
		vtemp = registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2];
	}

	DOLOG(log_ss::LS_COMM, "PDP11TTY read addr %o (%s): %d, 7bit: %d", addr, regnames[reg], vtemp, vtemp & 127);

	registers[reg] = vtemp;

	if (notify)
		notify_rx();
	else if (addr == PDP11TTY_TKS || addr == PDP11TTY_TKB)
		update_rx_interrupt();

	return vtemp;
}

void tty::write_byte(const uint16_t addr, const uint8_t v)
{
	uint16_t vtemp = registers[(addr - PDP11TTY_BASE) / 2];
	
	if (addr & 1) {
		vtemp &= ~0xff00;
		vtemp |= v << 8;
	}
	else {
		vtemp &= ~0x00ff;
		vtemp |= v;
	}

	write_word(addr, vtemp);
}

void tty::write_word(const uint16_t addr, uint16_t v)
{
	const int reg = (addr - PDP11TTY_BASE) / 2;

	DOLOG(log_ss::LS_COMM, "PDP11TTY write %o (%s): %o", addr, regnames[reg], v);

	if (addr == PDP11TTY_TKS) {
		registers[reg] = v & TTY_IE;
		if (c->poll_char())
			registers[reg] |= TTY_DONE;
		update_rx_interrupt();
		return;
	}

	if (addr == PDP11TTY_TPS) {
		const bool old_ie = (registers[reg] & TTY_IE) != 0;
		registers[reg] = (registers[reg] & TTY_DONE) | (v & TTY_IE);
		if (!old_ie && (registers[reg] & (TTY_DONE | TTY_IE)) == (TTY_DONE | TTY_IE))
			tx_ready_reported = false;
		update_tx_interrupt();
		return;
	}

	if (addr == PDP11TTY_TPB) {
		char ch = v & 127;
		tx_busy = true;
		tx_ready_reported = false;
		const uint64_t now = scheduler ? scheduler->now() : (b && b->getCpu()
			? b->getCpu()->get_instructions_executed_count() : 0);
		tx_ready_at_instruction = now + TTY_TX_DELAY_INSTRUCTIONS;
		if (scheduler)
			txEvent = scheduler->scheduleAfter(now, TTY_TX_DELAY_INSTRUCTIONS, &tty::complete_tx, this);
		registers[(PDP11TTY_TPS - PDP11TTY_BASE) / 2] &= ~TTY_DONE;
		update_tx_interrupt();

		DOLOG(log_ss::LS_COMM, "PDP11TTY print '%c'", ch);
		c->put_char(ch);
	}

	DOLOG(log_ss::LS_COMM, "set register %o to %o", addr, v);
	registers[(addr - PDP11TTY_BASE) / 2] = v;
}

#if IS_POSIX
JsonDocument tty::serialize()
{
	JsonDocument j;

	JsonDocument ja_reg;
	JsonArray    ja_reg_work = ja_reg.to<JsonArray>();
        for(size_t i=0; i<4; i++)
                ja_reg_work.add(registers[i]);
	j["registers"] = ja_reg;

	return j;
}

tty *tty::deserialize(const JsonVariantConst j, bus *const b, console *const cnsl)
{
	tty       *out   = new tty(cnsl, b);

	JsonArrayConst ja_reg = j["registers"];
	int       i_reg  = 0;
	for(auto v: ja_reg)
		out->registers[i_reg++] = v;

	return out;
}
#endif
