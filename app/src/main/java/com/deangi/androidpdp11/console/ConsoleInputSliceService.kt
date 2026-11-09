package com.deangi.androidpdp11.console

import com.deangi.androidpdp11.emulator.EmulatorSliceService
import com.deangi.androidpdp11.emulator.SlicePhase

/** Promotes one host RX byte whenever the Z80 console receiver becomes empty. */
class ConsoleInputSliceService(
    private val queues: ConsoleInputQueues,
    private val receiverHasByte: () -> Boolean,
    private val supplyReceiver: (Byte) -> Unit,
) : EmulatorSliceService {
    override fun run(phase: SlicePhase) {
        if (!receiverHasByte()) queues.takeNext()?.let(supplyReceiver)
    }
}
