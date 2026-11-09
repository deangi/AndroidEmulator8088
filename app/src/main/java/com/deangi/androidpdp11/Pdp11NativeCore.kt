package com.deangi.androidpdp11

/**
 * Direct JNI host for the PDP-11/70 engine. It is intentionally parallel to
 * [NativeEmulator] until RT-11 drive mounting replaces the inherited CP/M
 * storage adapter.
 */
class Pdp11NativeCore : ConsoleExecutionCore {
    private var handle = nativeCreate()

    val processorId: String get() = withHandle { nativeProcessorId(it) }
    var memorySizeBytes: Int
        get() = withHandle { nativeMemorySizeBytes(it) }
        set(value) = withHandle { nativeSetMemorySizeBytes(it, value) }

    fun reset(entryPc: Int = 0) = withHandle { nativeReset(it, entryPc) }
    internal fun setProgramCounter(value: Int) = withHandle { nativeSetProgramCounter(it, value) }
    override fun runInstructions(count: Long): Long = withHandle { nativeRunInstructions(it, count) }
    fun zeroMemory() = withHandle { nativeZeroMemory(it) }
    fun clearMemory(value: Byte = 0) = withHandle { nativeClearMemory(it, value) }
    fun load(address: Int, bytes: ByteArray) = withHandle { nativeLoad(it, address, bytes) }
    fun bootRk05(descriptor: Int): Boolean = withHandle { nativeBootRk05(it, descriptor) }
    override fun enqueueInput(value: Byte) = withHandle { nativeEnqueueInput(it, value) }
    override fun hasPendingConsoleInput(): Boolean = withHandle { nativeHasPendingConsoleInput(it) }
    override fun drainConsole(): ByteArray = withHandle { nativeDrainConsole(it) }

    override fun close() = synchronized(this) {
        if (handle != 0L) nativeDestroy(handle).also { handle = 0L }
    }

    private inline fun <T> withHandle(action: (Long) -> T): T = synchronized(this) {
        check(handle != 0L) { "PDP-11 core has been closed" }
        action(handle)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcessorId(handle: Long): String
    private external fun nativeReset(handle: Long, pc: Int)
    private external fun nativeSetProgramCounter(handle: Long, pc: Int)
    private external fun nativeRunInstructions(handle: Long, count: Long): Long
    private external fun nativeMemorySizeBytes(handle: Long): Int
    private external fun nativeSetMemorySizeBytes(handle: Long, bytes: Int)
    private external fun nativeClearMemory(handle: Long, value: Byte)
    private external fun nativeZeroMemory(handle: Long)
    private external fun nativeLoad(handle: Long, address: Int, bytes: ByteArray)
    private external fun nativeBootRk05(handle: Long, descriptor: Int): Boolean
    private external fun nativeEnqueueInput(handle: Long, value: Byte)
    private external fun nativeHasPendingConsoleInput(handle: Long): Boolean
    private external fun nativeDrainConsole(handle: Long): ByteArray

    companion object {
        init { System.loadLibrary("androidpdp11") }
    }
}
