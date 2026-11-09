package com.deangi.androidpdp11.console

/**
 * The post-emulator console junction.  All local and remote characters enter
 * the CPU through [acceptInput]; console output is fanned to the Android
 * terminal and (when enabled) the telnet transport through [publishOutput].
 */
class ConsoleIoRouter(
    private val localInput: (Byte) -> Unit,
    private val telnetInput: (Byte) -> Unit,
    private val localOutput: (ByteArray) -> Unit,
    private val telnetOutput: (ByteArray) -> Unit = {},
) {
    fun acceptLocalInput(value: Byte) = localInput(value)
    fun acceptTelnetInput(value: Byte) = telnetInput(value)

    fun publishOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        localOutput(bytes)
        telnetOutput(bytes)
    }
}
