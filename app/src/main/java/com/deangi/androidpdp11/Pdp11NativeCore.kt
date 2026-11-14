package com.deangi.androidpdp11

/**
 * Direct JNI host for the PDP-11/70 engine. It is intentionally parallel to
 * [NativeEmulator] until RT-11 drive mounting replaces the inherited CP/M
 * storage adapter.
 */
class Pdp11NativeCore : ConsoleExecutionCore {
    @Volatile private var handle = nativeCreate()

    val processorId: String get() = withHandle { nativeProcessorId(it) }
    fun diagnostics(): String = withHandle { nativeDiagnostics(it) }
    /** Lock-free with respect to CPU execution; native owns request/result synchronization. */
    fun requestDiagnostics() { handle.takeIf { it != 0L }?.let(::nativeRequestDiagnostics) }
    fun takeDiagnostics(): String = handle.takeIf { it != 0L }?.let(::nativeTakeDiagnostics).orEmpty()
    var memorySizeBytes: Int
        get() = withHandle { nativeMemorySizeBytes(it) }
        set(value) = withHandle { nativeSetMemorySizeBytes(it, value) }

    fun reset(entryPc: Int = 0) = withHandle { nativeReset(it, entryPc) }
    override fun config(parameter: String): Boolean = withHandle { nativeConfig(it, parameter) }
    internal fun setProgramCounter(value: Int) = withHandle { nativeSetProgramCounter(it, value) }
    /** PC sampled by the emulation thread after its most recent execution epoch. */
    fun lastProgramCounter(): Int = withHandle { nativeLastProgramCounter(it) }
    override fun runInstructions(count: Long): Long = withHandle { nativeRunInstructions(it, count) }
    fun zeroMemory() = withHandle { nativeZeroMemory(it) }
    fun clearMemory(value: Byte = 0) = withHandle { nativeClearMemory(it, value) }
    fun load(address: Int, bytes: ByteArray) = withHandle { nativeLoad(it, address, bytes) }
    /** Start a profile with only the controllers explicitly mounted by that profile. */
    fun clearProfileDevices() = withHandle { nativeClearProfileDevices(it) }
    fun bootRk05(descriptor: Int): Boolean = withHandle { nativeBootRk05(it, descriptor) }
    /** Notify the native RL11 controller that DL[unit] now has media. */
    fun mountRl(unit: Int, descriptor: Int): Boolean = withHandle { nativeMountRl(it, unit, descriptor) }
    /** Notify the native RL11 controller that DL[unit] is now empty. */
    fun dismountRl(unit: Int): Boolean = withHandle { nativeDismountRl(it, unit) }
    fun bootRl(unit: Int): Boolean = withHandle { nativeBootRl(it, unit) }
    /** Attach the UDA50/MSCP DU0 image; native code retains a duplicated descriptor. */
    fun mountDu0(descriptor: Int): Boolean = withHandle { nativeMountDu0(it, descriptor) }
    fun bootDu0(): Boolean = withHandle { nativeBootDu0(it) }
    fun mountDrive(name: String, descriptor: Int): Boolean = withHandle { nativeMountDrive(it, name, descriptor) }
    fun dismountDrive(name: String): Boolean = withHandle { nativeDismountDrive(it, name) }
    fun enumerateDrives(): String = withHandle { nativeEnumerateDrives(it) }
    override fun enqueueInput(value: Byte) = withHandle { nativeEnqueueInput(it, value) }
    override fun hasPendingConsoleInput(): Boolean = withHandle { nativeHasPendingConsoleInput(it) }
    override fun drainConsole(): ByteArray = withHandle { nativeDrainConsole(it) }
    fun drainPrinter(): ByteArray = withHandle { nativeDrainPrinter(it) }

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
    private external fun nativeDiagnostics(handle: Long): String
    private external fun nativeRequestDiagnostics(handle: Long)
    private external fun nativeTakeDiagnostics(handle: Long): String
    private external fun nativeReset(handle: Long, pc: Int)
    private external fun nativeConfig(handle: Long, parameter: String): Boolean
    private external fun nativeSetProgramCounter(handle: Long, pc: Int)
    private external fun nativeLastProgramCounter(handle: Long): Int
    private external fun nativeRunInstructions(handle: Long, count: Long): Long
    private external fun nativeMemorySizeBytes(handle: Long): Int
    private external fun nativeSetMemorySizeBytes(handle: Long, bytes: Int)
    private external fun nativeClearMemory(handle: Long, value: Byte)
    private external fun nativeZeroMemory(handle: Long)
    private external fun nativeLoad(handle: Long, address: Int, bytes: ByteArray)
    private external fun nativeClearProfileDevices(handle: Long)
    private external fun nativeBootRk05(handle: Long, descriptor: Int): Boolean
    private external fun nativeMountRl(handle: Long, unit: Int, descriptor: Int): Boolean
    private external fun nativeDismountRl(handle: Long, unit: Int): Boolean
    private external fun nativeBootRl(handle: Long, unit: Int): Boolean
    private external fun nativeMountDu0(handle: Long, descriptor: Int): Boolean
    private external fun nativeBootDu0(handle: Long): Boolean
    private external fun nativeMountDrive(handle: Long, name: String, descriptor: Int): Boolean
    private external fun nativeDismountDrive(handle: Long, name: String): Boolean
    private external fun nativeEnumerateDrives(handle: Long): String
    private external fun nativeEnqueueInput(handle: Long, value: Byte)
    private external fun nativeHasPendingConsoleInput(handle: Long): Boolean
    private external fun nativeDrainConsole(handle: Long): ByteArray
    private external fun nativeDrainPrinter(handle: Long): ByteArray

    companion object {
        init { System.loadLibrary("android8088") }
    }
}
