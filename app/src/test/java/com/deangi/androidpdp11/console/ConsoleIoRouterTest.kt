package com.deangi.androidpdp11.console

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleIoRouterTest {
    @Test fun `input and output follow their separate console paths`() {
        val localInput = mutableListOf<Byte>()
        val telnetInput = mutableListOf<Byte>()
        val local = mutableListOf<ByteArray>()
        val telnet = mutableListOf<ByteArray>()
        val router = ConsoleIoRouter(localInput::add, telnetInput::add, local::add, telnet::add)

        router.acceptLocalInput('A'.code.toByte())
        router.acceptTelnetInput('T'.code.toByte())
        router.publishOutput("READY\r\n".encodeToByteArray())

        assertEquals(listOf('A'.code.toByte()), localInput)
        assertEquals(listOf('T'.code.toByte()), telnetInput)
        assertEquals("READY\r\n", local.single().decodeToString())
        assertEquals("READY\r\n", telnet.single().decodeToString())
    }

    @Test fun `empty output does not notify consumers`() {
        var outputCount = 0
        ConsoleIoRouter({}, {}, { outputCount++ }).publishOutput(byteArrayOf())
        assertEquals(0, outputCount)
    }
}
