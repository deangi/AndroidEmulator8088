package com.deangi.androidpdp11.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleInputQueuesTest {
    @Test fun `local input is delivered before telnet input`() {
        val queues = ConsoleInputQueues()
        queues.enqueueTelnet('T'.code.toByte())
        queues.enqueueLocal('L'.code.toByte())

        assertEquals('L'.code.toByte(), queues.takeNext())
        assertEquals('T'.code.toByte(), queues.takeNext())
        assertEquals(null, queues.takeNext())
    }

    @Test fun `each queue is bounded to 128 bytes`() {
        val queues = ConsoleInputQueues()
        repeat(ConsoleInputQueues.CAPACITY) { assertTrue(queues.enqueueLocal(it.toByte())) }
        assertFalse(queues.enqueueLocal(0))
        assertEquals(ConsoleInputQueues.CAPACITY, queues.localSize())
    }
}
