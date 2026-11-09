package com.deangi.androidpdp11

/**
 * JNI boundary for the portable CPU/port core. Higher-level Android concerns
 * (terminal rendering, document storage, telnet, and service lifecycle) are
 * intentionally not represented here.
 */
class NativeEmulator : ConsoleExecutionCore {
    private var handle = nativeCreate()

    fun reset(entryPc: Int = 0) = withHandle { nativeReset(it, entryPc) }
    fun bootDisks(fds: IntArray, hddMask: Int, bootDrive: Int): Boolean = withHandle { nativeBootDisks(it, fds, hddMask, bootDrive) }
    fun replaceDisks(fds: IntArray, hddMask: Int): Boolean = withHandle { nativeReplaceDisks(it, fds, hddMask) }
    fun runCycles(minCycles: Long): Long = withHandle { nativeRunCycles(it, minCycles) }
    override fun runInstructions(count: Long): Long = withHandle { nativeRunInstructions(it, count) }
    fun load(address: Int, bytes: ByteArray) = withHandle { nativeLoad(it, address, bytes) }
    override fun enqueueInput(value: Byte) = withHandle { nativeEnqueueInput(it, value) }
    override fun hasPendingConsoleInput(): Boolean = withHandle { nativeHasPendingConsoleInput(it) }
    override fun drainConsole(): ByteArray = withHandle { nativeDrainConsole(it) }
    fun drainPrinter(): ByteArray = withHandle { nativeDrainPrinter(it) }
    fun takeDiskError(): String = withHandle { nativeTakeDiskError(it) }

    override fun close() = synchronized(this) {
        if (handle != 0L) nativeDestroy(handle).also { handle = 0L }
    }

    private inline fun <T> withHandle(action: (Long) -> T): T = synchronized(this) {
        check(handle != 0L) { "Native emulator has been closed" }
        action(handle)
    }

    private external fun nativeCreate(): Long
    private external fun nativeTakeDiskError(handle: Long): String
    private external fun nativeBootDisks(handle: Long, fds: IntArray, hddMask: Int, bootDrive: Int): Boolean
    private external fun nativeReplaceDisks(handle: Long, fds: IntArray, hddMask: Int): Boolean
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long, pc: Int)
    private external fun nativeRunCycles(handle: Long, cycles: Long): Long
    private external fun nativeRunInstructions(handle: Long, instructions: Long): Long
    private external fun nativeLoad(handle: Long, address: Int, bytes: ByteArray)
    private external fun nativeEnqueueInput(handle: Long, value: Byte)
    private external fun nativeHasPendingConsoleInput(handle: Long): Boolean
    private external fun nativeDrainConsole(handle: Long): ByteArray
    private external fun nativeDrainPrinter(handle: Long): ByteArray

    companion object {
        init { System.loadLibrary("androidpdp11") }
    }
}
