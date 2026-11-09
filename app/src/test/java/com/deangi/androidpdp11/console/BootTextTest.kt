package com.deangi.androidpdp11.console

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BootTextTest {
    @Test fun decodesEsp32EscapesAndComments() {
        val encoded = """"DIR\r\n\t\e\x41\101^C^?\\\";#" ; ignored"""
        val expected = byteArrayOf(68, 73, 82, 13, 10, 9, 27, 65, 65, 3, 127, 92, 34, 59, 35)
        assertArrayEquals(expected, BootText.decode(encoded))
        assertArrayEquals("DIR\r".toByteArray(), BootText.decode("""'DIR\r' # comment"""))
        assertEquals(256, BootText.decode("X".repeat(300)).size)
    }

    @Test fun waitsForOutputToSettleAndRetriesAFullQueueWithoutDroppingBytes() {
        val queue = ConsoleInputQueues(capacity = 2)
        val boot = BootInputController(queue::enqueueLocal, quietMs = 800)
        boot.arm(""""ABC"""")
        boot.poll(1000, false)
        assertEquals(0, queue.localSize())
        boot.poll(1100, true)
        boot.poll(1800, true)
        boot.poll(2599, false)
        assertEquals(0, queue.localSize())
        boot.poll(2600, false)
        assertEquals('A'.code.toByte(), queue.takeNext())
        boot.poll(2601, false)
        assertEquals('B'.code.toByte(), queue.takeNext())
        assertEquals('C'.code.toByte(), queue.takeNext())
        boot.poll(4000, false)
        assertEquals(0, queue.localSize())
    }

    @Test fun rearmingCancelsPreviouslyPendingText() {
        val queue = ConsoleInputQueues(capacity = 2)
        val boot = BootInputController(queue::enqueueLocal)
        boot.arm("OLD")
        boot.poll(100, true)
        boot.arm("NEW")
        boot.poll(2000, false)
        assertEquals(0, queue.localSize())
        boot.poll(2001, true)
        boot.poll(2801, false)
        assertEquals('N'.code.toByte(), queue.takeNext())
    }
}
