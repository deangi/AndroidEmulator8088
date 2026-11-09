package com.deangi.androidpdp11.console

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleKeyMapperTest {
    @Test fun `maps printable and terminal control keys`() {
        assertEquals('A'.code.toByte(), ConsoleKeyMapper.toConsoleByte(KeyEvent.KEYCODE_A, 'A'.code))
        assertEquals('\r'.code.toByte(), ConsoleKeyMapper.toConsoleByte(KeyEvent.KEYCODE_ENTER, 0))
        assertEquals('\b'.code.toByte(), ConsoleKeyMapper.toConsoleByte(KeyEvent.KEYCODE_DEL, 0))
        assertEquals('\t'.code.toByte(), ConsoleKeyMapper.toConsoleByte(KeyEvent.KEYCODE_TAB, 0))
        assertEquals(0x1B.toByte(), ConsoleKeyMapper.toConsoleByte(KeyEvent.KEYCODE_ESCAPE, 0))
    }

    @Test fun `ignores non-console keys`() {
        assertEquals(null, ConsoleKeyMapper.toConsoleByte(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
    }
}
