package com.deangi.androidpdp11.io

import com.deangi.androidpdp11.EmulatorIdentity

/** Per-connection ESP32-compatible ESC >> host-shell router. */
class TelnetShellSession(
    private val guestInput: (Byte) -> Unit,
    private val shellOutput: (ByteArray) -> Unit,
    private val command: (String) -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile var active: Boolean = false
        private set
    private var escapeState = 0
    private var escapeAt = 0L
    private val line = StringBuilder()
    private var ignoreLf = false

    fun poll() {
        if (escapeState != 0 && now() - escapeAt >= 5000) {
            guestInput(27)
            if (escapeState == 2) guestInput('>'.code.toByte())
            escapeState = 0
        }
    }

    fun accept(byte: Byte) {
        poll()
        val c = byte.toInt() and 0xff
        if (ignoreLf && (c == 10 || c == 0)) { ignoreLf = false; return }
        ignoreLf = false
        if (active) {
            when (c) {
                8, 127 -> if (line.isNotEmpty()) { line.deleteCharAt(line.lastIndex); write("\b \b") }
                13, 10 -> {
                    ignoreLf = c == 13
                    val input = line.toString().trim()
                    line.clear()
                    write("\r\n")
                    if (input.equals("exit", true)) {
                        write("Returning Telnet to the emulator console.\r\n")
                        active = false
                    } else {
                        val result = runCatching { command(input) }.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}\r\n" }
                        write(result)
                        write("vz80:/> ")
                    }
                }
                in 32..126 -> if (line.length < 255) { line.append(c.toChar()); write(c.toChar().toString()) }
            }
            return
        }
        when (escapeState) {
            0 -> if (c == 27) { escapeState = 1; escapeAt = now() } else guestInput(byte)
            1 -> if (c == '>'.code) { escapeState = 2; escapeAt = now() } else {
                guestInput(27); escapeState = 0; accept(byte)
            }
            else -> if (c == '>'.code) {
                escapeState = 0; active = true; line.clear()
                write(EmulatorIdentity.managementShellBanner())
            } else {
                guestInput(27); guestInput('>'.code.toByte()); escapeState = 0; accept(byte)
            }
        }
    }

    private fun write(text: String) = shellOutput(text.encodeToByteArray())
}
