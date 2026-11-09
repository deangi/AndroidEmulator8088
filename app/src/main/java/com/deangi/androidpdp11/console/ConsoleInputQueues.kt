package com.deangi.androidpdp11.console

import java.util.ArrayDeque

/**
 * Two bounded host-side RX queues. Local (touch/Bluetooth) input has priority;
 * telnet is serviced whenever the local queue is empty. The emulator receives
 * at most one byte at a time, so its RX-ready status bit represents a byte it
 * can actually consume.
 */
class ConsoleInputQueues(private val capacity: Int = CAPACITY) {
    private val lock = Any()
    private val local = ArrayDeque<Byte>(capacity)
    private val telnet = ArrayDeque<Byte>(capacity)

    fun enqueueLocal(value: Byte): Boolean = enqueue(local, value)
    fun enqueueTelnet(value: Byte): Boolean = enqueue(telnet, value)

    /** Removes the next byte in source-priority order, or null when all queues are empty. */
    fun takeNext(): Byte? = synchronized(lock) {
        when {
            local.isNotEmpty() -> local.removeFirst()
            telnet.isNotEmpty() -> telnet.removeFirst()
            else -> null
        }
    }

    fun localSize(): Int = synchronized(lock) { local.size }
    fun telnetSize(): Int = synchronized(lock) { telnet.size }
    fun clear() = synchronized(lock) { local.clear(); telnet.clear() }

    private fun enqueue(queue: ArrayDeque<Byte>, value: Byte): Boolean = synchronized(lock) {
        if (queue.size >= capacity) false else {
            queue.addLast(value)
            true
        }
    }

    companion object { const val CAPACITY = 128 }
}
