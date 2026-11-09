package com.deangi.androidpdp11.io

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelnetConsoleServerTest {
    @Test fun rapidStopAndStartLeavesOneWorkingListener() {
        val port = ServerSocket(0).use { it.localPort }
        val server = TelnetConsoleServer()
        val delivered = CountDownLatch(1)
        try {
            repeat(30) { server.start(port) {}; server.stop() }
            server.start(port) { delivered.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (server.listeningPort == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(port, server.listeningPort)
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write('X'.code)
                assertTrue(delivered.await(5, TimeUnit.SECONDS))
            }
        } finally { server.stop() }
    }

    @Test fun socketSessionRoutesShellAndRestoresGuestInput() {
        val port = ServerSocket(0).use { it.localPort }
        val server = TelnetConsoleServer()
        val guest = mutableListOf<Byte>()
        val delivered = CountDownLatch(1)
        server.shellCommand = { if (it == "status") "SHELL STATUS\r\n" else "UNKNOWN\r\n" }
        try {
            server.start(port) { synchronized(guest) { guest.add(it) }; delivered.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (server.listeningPort == null && System.nanoTime() < deadline) Thread.sleep(10)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                fun readThrough(marker: String): String {
                    val result = StringBuilder()
                    while (!result.contains(marker)) {
                        val value = input.read()
                        check(value >= 0) { "Disconnected before $marker" }
                        result.append(value.toChar())
                    }
                    return result.toString()
                }
                readThrough("AndroidEmulatorPdp11 console\r\n")
                socket.getOutputStream().write("\u001b>>".encodeToByteArray())
                readThrough("vz80:/> ")
                server.writeConsole("GUEST-OUTPUT".encodeToByteArray())
                socket.getOutputStream().write("status\r\n".encodeToByteArray())
                val shellResponse = readThrough("vz80:/> ")
                assertTrue(shellResponse.contains("SHELL STATUS"))
                assertTrue(!shellResponse.contains("GUEST-OUTPUT"))
                socket.getOutputStream().write("exit\r\nZ".encodeToByteArray())
                readThrough("Returning Telnet to the CP/M console.\r\n")
                assertTrue(delivered.await(5, TimeUnit.SECONDS))
                assertEquals(listOf('Z'.code.toByte()), synchronized(guest) { guest.toList() })
            }
        } finally { server.stop() }
    }

    @Test fun negotiationAndSubnegotiationReturnToConsoleData() {
        val port = ServerSocket(0).use { it.localPort }
        val server = TelnetConsoleServer()
        val received = mutableListOf<Int>()
        val complete = CountDownLatch(2)
        try {
            server.start(port) { synchronized(received) { received.add(it.toInt() and 0xff) }; complete.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (server.listeningPort == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(port, server.listeningPort)
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write(byteArrayOf(
                    255.toByte(), 253.toByte(), 1, 65,
                    255.toByte(), 250.toByte(), 24, 1, 255.toByte(), 240.toByte(), 66,
                ))
                assertTrue(complete.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(65, 66), synchronized(received) { received.toList() })
            }
        } finally {
            server.stop()
        }
    }
}
