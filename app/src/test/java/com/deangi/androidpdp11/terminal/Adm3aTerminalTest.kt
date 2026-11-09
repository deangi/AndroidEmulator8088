package com.deangi.androidpdp11.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class Adm3aTerminalTest {
    @Test fun `writes text and honors carriage return`() {
        val terminal = Adm3aTerminal(columns = 8, rows = 2)
        terminal.feed("ABC\rZ".encodeToByteArray())
        assertEquals("ZBC     ", terminal.line(0))
    }

    @Test fun `esc equals moves cursor using ADM3A offset`() {
        val terminal = Adm3aTerminal(columns = 8, rows = 3)
        terminal.feed(byteArrayOf(0x1B, '='.code.toByte(), (32 + 1).toByte(), (32 + 2).toByte(), 'X'.code.toByte()))
        assertEquals("  X     ", terminal.line(1))
    }

    @Test fun `line feed scrolls at bottom`() {
        val terminal = Adm3aTerminal(columns = 4, rows = 2)
        terminal.feed("A\nB\nC".encodeToByteArray())
        assertEquals(" B  ", terminal.line(0))
        assertEquals("  C ", terminal.line(1))
    }

    @Test fun `escape T erases to end of line`() {
        val terminal = Adm3aTerminal(columns = 5, rows = 1)
        terminal.feed("ABCDE\rAB\u001BT".encodeToByteArray())
        assertEquals("AB   ", terminal.line(0))
    }

    @Test fun `corner pattern marks all four one based screen corners`() {
        val terminal = Adm3aTerminal(columns = 80, rows = 24)
        terminal.showCornerTestPattern()
        assertEquals('X', terminal.line(0)[0])
        assertEquals('X', terminal.line(23)[0])
        assertEquals('X', terminal.line(0)[79])
        assertEquals('X', terminal.line(23)[79])
        assertEquals('X', terminal.line(23, includeCursor = true)[79])
    }
}
