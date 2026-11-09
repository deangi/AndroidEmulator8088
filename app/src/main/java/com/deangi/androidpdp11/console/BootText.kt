package com.deangi.androidpdp11.console

/** Decodes the ESP32 vZ80 boot_text syntax, limited to its 256-byte maximum. */
object BootText {
    fun decode(raw: String): ByteArray {
        var quoted = false
        var quote = '\u0000'
        var escaped = false
        var end = raw.length
        for (i in raw.indices) {
            val c = raw[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (quoted) { if (c == quote) quoted = false; continue }
            if (c == '"' || c == '\'') { quoted = true; quote = c; continue }
            if (c == ';' || c == '#') { end = i; break }
        }
        val value = raw.substring(0, end).trim().let {
            if (it.length >= 2 && (it.first() == '"' || it.first() == '\'') && it.last() == it.first()) it.substring(1, it.length - 1) else it
        }
        val result = ArrayList<Byte>()
        var i = 0
        while (i < value.length && result.size < 256) {
            var c = value[i++].code
            if (c == '^'.code && i < value.length) {
                c = value[i++].let { if (it == '?') 0x7f else it.code and 0x1f }
            } else if (c == '\\'.code && i < value.length) {
                val next = value[i++]
                c = when (next) {
                    'r' -> 13; 'n' -> 10; 't' -> 9; 'b' -> 8; 'f' -> 12
                    'e' -> 27; 's' -> 32
                    'x' -> {
                        var number = 0
                        var count = 0
                        while (i < value.length && count < 2) {
                            val digit = value[i].digitToIntOrNull(16) ?: break
                            number = number * 16 + digit; i++; count++
                        }
                        number
                    }
                    in '0'..'7' -> {
                        var number = next.digitToInt()
                        var count = 1
                        while (i < value.length && count < 3 && value[i] in '0'..'7') {
                            number = number * 8 + value[i].digitToInt(); i++; count++
                        }
                        number
                    }
                    else -> next.code
                }
            }
            result.add(c.toByte())
        }
        return result.toByteArray()
    }
}

/** Waits for boot output to settle, then retries while the local RX queue is full. */
class BootInputController(private val enqueue: (Byte) -> Boolean, private val quietMs: Long = 800) {
    private var pending = byteArrayOf()
    private var offset = 0
    private var lastOutput: Long? = null
    private var injecting = false

    fun arm(encoded: String) {
        pending = BootText.decode(encoded)
        offset = 0
        lastOutput = null
        injecting = false
    }

    fun poll(nowMs: Long, outputProduced: Boolean) {
        if (offset >= pending.size) return
        if (!injecting) {
            if (outputProduced) lastOutput = nowMs
            val last = lastOutput ?: return
            if (nowMs - last < quietMs) return
            injecting = true
        }
        while (offset < pending.size && enqueue(pending[offset])) offset++
    }
}
