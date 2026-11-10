package com.deangi.androidpdp11

/**
 * The host-side console contract shared by CPU implementations.
 *
 * [enqueueInput] is fed by the local terminal and Telnet queues.  The native
 * core signals its console device immediately, so a guest with receiver
 * interrupts enabled need not wait for its next status-register poll.
 * [drainConsole] is consumed by the common console router, which mirrors the
 * result to the Android terminal window and connected Telnet clients.
 */
interface ConsoleExecutionCore : AutoCloseable {
    /** Apply one emulator-specific configuration assignment, for example `kwp_enabled=false`. */
    fun config(parameter: String): Boolean = false
    fun runInstructions(count: Long): Long
    fun enqueueInput(value: Byte)
    fun hasPendingConsoleInput(): Boolean
    fun drainConsole(): ByteArray
}
