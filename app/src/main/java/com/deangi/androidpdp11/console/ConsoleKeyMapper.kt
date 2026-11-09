package com.deangi.androidpdp11.console

import android.view.KeyEvent

/** Converts physical Android/Bluetooth keyboard events to CP/M console bytes. */
object ConsoleKeyMapper {
    fun toConsoleByte(keyCode: Int, unicodeChar: Int): Byte? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> '\r'.code.toByte()
        KeyEvent.KEYCODE_DEL -> '\b'.code.toByte()
        KeyEvent.KEYCODE_TAB -> '\t'.code.toByte()
        KeyEvent.KEYCODE_ESCAPE -> 0x1B.toByte()
        else -> unicodeChar.takeIf { it in 0x20..0xFF }?.toByte()
    }
}
