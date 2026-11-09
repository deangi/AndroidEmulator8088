package com.deangi.androidpdp11.io

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Single-client, LAN-only telnet transport for the emulated console. */
class TelnetConsoleServer {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val outputExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var output: BufferedOutputStream? = null
    private var session: TelnetShellSession? = null
    private var generation = 0L
    @Volatile var shellCommand: (String) -> String = { "Shell unavailable\r\n" }

    @Volatile var listeningPort: Int? = null
        private set
    @Volatile var clientAddress: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    fun start(port: Int, onInput: (Byte) -> Unit) {
        require(port in 1024..65535) { "Telnet port must be between 1024 and 65535" }
        val token = synchronized(lock) {
            generation++
            closeClientLocked()
            server?.close()
            server = null
            listeningPort = null
            lastError = null
            generation
        }
        executor.execute {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    synchronized(lock) {
                        if (generation != token) return@execute
                        server = socket
                    }
                    socket.bind(InetSocketAddress(port))
                    synchronized(lock) {
                        if (generation != token) return@execute
                        listeningPort = socket.localPort
                    }
                    while (!socket.isClosed) {
                        val accepted = try { socket.accept() } catch (error: java.io.IOException) {
                            if (socket.isClosed) break else throw error
                        }
                        executor.execute { serveClient(accepted, onInput, token) }
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) { if (generation == token) lastError = error.message ?: error.javaClass.simpleName }
            } finally {
                synchronized(lock) { if (generation == token) { server = null; listeningPort = null } }
            }
        }
    }

    fun writeConsole(bytes: ByteArray) {
        val target = synchronized(lock) { if (session?.active == true) null else client } ?: return
        writeToClient(target, bytes, guestOutput = true)
    }

    private fun writeToClient(target: Socket, bytes: ByteArray, guestOutput: Boolean = false) {
        val payload = bytes.copyOf()
        outputExecutor.execute {
            synchronized(lock) {
                if (client !== target) return@synchronized
                if (guestOutput && session?.active == true) return@synchronized
                val stream = output ?: return@synchronized
                try {
                    payload.forEach { byte ->
                        stream.write(byte.toInt())
                        if (byte.toInt() and 0xFF == IAC) stream.write(IAC)
                    }
                    stream.flush()
                } catch (_: Exception) {
                    closeClientLocked(target)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            closeClientLocked()
            server?.close()
            server = null
            listeningPort = null
        }
    }

    fun close() {
        stop()
        executor.shutdownNow()
        outputExecutor.shutdownNow()
    }

    /**
     * A new connection replaces any older one.  This prevents a dropped Wi-Fi
     * client whose TCP close was never observed from blocking a reconnect.
     */
    private fun serveClient(accepted: Socket, onInput: (Byte) -> Unit, token: Long) {
        val shell = TelnetShellSession(onInput, { bytes -> writeToClient(accepted, bytes) }, { shellCommand(it) })
        synchronized(lock) {
            if (generation != token || server?.isClosed != false) { accepted.close(); return }
            closeClientLocked()
            accepted.tcpNoDelay = true
            accepted.keepAlive = true
            accepted.soTimeout = 200
            client = accepted
            session = shell
            output = BufferedOutputStream(accepted.getOutputStream())
            clientAddress = accepted.inetAddress.hostAddress
            sendTelnetPreambleLocked()
        }
        try {
            BufferedInputStream(accepted.getInputStream()).use { input -> readClient(input, shell) }
        } catch (_: java.io.IOException) {
        } finally {
            synchronized(lock) { closeClientLocked(accepted) }
        }
    }

    private fun readClient(input: BufferedInputStream, shell: TelnetShellSession) {
        var state = RxState.DATA
        var afterCr = false
        while (true) {
            val value = try { input.read() } catch (_: SocketTimeoutException) { shell.poll(); continue }
            if (value < 0) return
            when (state) {
                RxState.DATA -> when (value) {
                    IAC -> state = RxState.IAC
                    else -> {
                        if (afterCr && value == 0) { afterCr = false; continue }
                        shell.accept(value.toByte())
                        afterCr = value == '\r'.code
                    }
                }
                RxState.IAC -> state = when (value) {
                    IAC -> { shell.accept(IAC.toByte()); RxState.DATA }
                    DO, DONT, WILL, WONT -> RxState.OPTION
                    SB -> RxState.SUBNEGOTIATION
                    else -> RxState.DATA
                }
                RxState.OPTION -> state = RxState.DATA
                RxState.SUBNEGOTIATION -> state = if (value == IAC) RxState.SUBNEGOTIATION_IAC else RxState.SUBNEGOTIATION
                RxState.SUBNEGOTIATION_IAC -> state = if (value == SE) RxState.DATA else RxState.SUBNEGOTIATION
            }
        }
    }

    private fun sendTelnetPreambleLocked() {
        val stream = output ?: return
        try {
            // Match the donor's basic telnet setup: local echo, suppress-go-
            // ahead, and binary mode. These bytes must not be IAC-escaped.
            stream.write(byteArrayOf(
                IAC.toByte(), WILL.toByte(), ECHO.toByte(),
                IAC.toByte(), WILL.toByte(), SUPPRESS_GO_AHEAD.toByte(),
                IAC.toByte(), DO.toByte(), SUPPRESS_GO_AHEAD.toByte(),
                IAC.toByte(), DO.toByte(), BINARY.toByte(),
            ))
            stream.write("AndroidEmulatorPdp11 console\r\n".encodeToByteArray())
            stream.flush()
        } catch (_: Exception) {
            closeClientLocked()
        }
    }

    private fun closeClientLocked(expected: Socket? = null) {
        if (expected != null && client !== expected) return
        output = null
        session = null
        client?.close()
        client = null
        clientAddress = null
    }

    private enum class RxState { DATA, IAC, OPTION, SUBNEGOTIATION, SUBNEGOTIATION_IAC }
    private companion object {
        const val IAC = 255
        const val DO = 253
        const val DONT = 254
        const val WILL = 251
        const val WONT = 252
        const val SB = 250
        const val SE = 240
        const val BINARY = 0
        const val ECHO = 1
        const val SUPPRESS_GO_AHEAD = 3
    }
}
