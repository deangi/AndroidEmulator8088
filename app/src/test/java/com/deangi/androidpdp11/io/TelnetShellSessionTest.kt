package com.deangi.androidpdp11.io

import org.junit.Assert.*
import org.junit.Test

class TelnetShellSessionTest {
    @Test fun shellKeepsGuestInputSeparateAndReturnsToConsole() {
        val guest = mutableListOf<Byte>()
        val output = StringBuilder()
        val commands = mutableListOf<String>()
        val session = TelnetShellSession(guest::add, { output.append(it.decodeToString()) }, { commands.add(it); "OK\r\n" })
        "a\u001b>>help\r\n".encodeToByteArray().forEach(session::accept)
        assertTrue(session.active)
        assertEquals(listOf('a'.code.toByte()), guest)
        assertEquals(listOf("help"), commands)
        assertTrue(output.toString().contains("OK"))
        "exit\rb".encodeToByteArray().forEach(session::accept)
        assertFalse(session.active)
        assertEquals(listOf('a'.code.toByte(), 'b'.code.toByte()), guest)
    }

    @Test fun unmatchedEscapeIsForwardedAndTimedOut() {
        var clock = 0L
        val guest = mutableListOf<Byte>()
        val session = TelnetShellSession(guest::add, {}, { "" }, { clock })
        session.accept(27)
        session.accept('x'.code.toByte())
        session.accept(27)
        session.accept('>'.code.toByte())
        clock = 5000
        session.poll()
        assertEquals(listOf(27.toByte(), 'x'.code.toByte(), 27.toByte(), '>'.code.toByte()), guest)
        assertFalse(session.active)
    }
}
