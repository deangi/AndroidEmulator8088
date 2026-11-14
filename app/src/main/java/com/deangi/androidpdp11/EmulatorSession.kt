package com.deangi.androidpdp11

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.deangi.androidpdp11.console.BootInputController
import com.deangi.androidpdp11.console.BootScriptController
import com.deangi.androidpdp11.console.ConsoleInputQueues
import com.deangi.androidpdp11.console.ConsoleInputSliceService
import com.deangi.androidpdp11.console.ConsoleIoRouter
import com.deangi.androidpdp11.emulator.EmulatorSliceCoordinator
import com.deangi.androidpdp11.io.LocalNetworkInfo
import com.deangi.androidpdp11.io.TelnetConsoleServer
import com.deangi.androidpdp11.storage.ShellDriveCommand
import com.deangi.androidpdp11.storage.Vz80Profile
import com.deangi.androidpdp11.storage.Vz80StorageRepository
import com.deangi.androidpdp11.terminal.Adm3aTerminal
import com.deangi.androidpdp11.terminal.TerminalPersonality
import kotlinx.coroutines.*

/** Owns the guest and transports independently of any Activity or screen. */
class EmulatorSession(context: Context) : AutoCloseable {
    val storage = Vz80StorageRepository(context.applicationContext)
    val snapshot = mutableStateOf(storage.snapshot())
    val terminalVersion = mutableIntStateOf(0)
    val emulatorRunning = mutableStateOf(false)
    val bootedProfile = mutableStateOf<Vz80Profile?>(null)
    val selectedProfile = mutableStateOf(snapshot.value?.profiles?.firstOrNull())
    val diskError = mutableStateOf("")
    val systemInfoVersion = mutableIntStateOf(0)
    val latestDiagnostics = mutableStateOf("")
    val printerError = mutableStateOf("")
    val printerFile = mutableStateOf<String?>(null)
    val printerPending = mutableStateOf<ByteArray?>(null)
    val telnetRequested = mutableStateOf(false)
    val telnetManuallyStopped = mutableStateOf(false)
    val telnetStatus = mutableStateOf(TelnetStatus())
    val stopping = mutableStateOf(false)

    val terminal = Adm3aTerminal(rows = 25, personality = TerminalPersonality.VT100).apply { showCornerTestPattern() }
    val printerCapture = storage.printerCapture()
    private val pdp11Core = Pdp11NativeCore()
    @Volatile private var activeConsoleCore: ConsoleExecutionCore = pdp11Core
    val telnet = TelnetConsoleServer()
    val inputQueues = ConsoleInputQueues()
    val bootInput = BootInputController(inputQueues::enqueueLocal)
    fun frontPanelPc(): Int = pdp11Core.lastProgramCounter()
    val bootScript = BootScriptController(inputQueues::enqueueLocal)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator = EmulatorSliceCoordinator(
        runInstructions = { activeConsoleCore.runInstructions(it) },
        services = listOf(ConsoleInputSliceService(inputQueues, { activeConsoleCore.hasPendingConsoleInput() }, { activeConsoleCore.enqueueInput(it) })),
    )
    val console = ConsoleIoRouter(
        localInput = inputQueues::enqueueLocal,
        telnetInput = inputQueues::enqueueTelnet,
        localOutput = { terminal.feed(it); terminalVersion.intValue++ },
        telnetOutput = telnet::writeConsole,
    )

    /** Apply the Drives dialog delta through the PDP-11 engine, never the inherited Z80 adapter. */
    fun replacePdpDrives(profile: Vz80Profile) {
        val previous = bootedProfile.value?.drives.orEmpty()
        val names = previous.keys + profile.drives.keys
        names.forEach { drive ->
            val before = previous[drive]
            val after = profile.drives[drive]
            if (before == after) return@forEach
            if (before != null) storage.dismountPdpDrive(drive, pdp11Core)
            if (after != null) storage.mountPdpDrive(drive, after.trimStart('/'), pdp11Core)
        }
        bootedProfile.value = profile
    }

    init {
        telnet.shellCommand = ::shellCommand
        scope.launch {
            while (isActive) {
                if (emulatorRunning.value) withContext(Dispatchers.Default) { coordinator.runSlice() }
                val output = activeConsoleCore.drainConsole()
                bootScript.observe(output)
                bootInput.poll(SystemClock.elapsedRealtime(), output.isNotEmpty())
                console.publishOutput(output)
                delay(if (emulatorRunning.value) 1 else 16)
            }
        }
        scope.launch {
            while (isActive) {
                telnetStatus.value = TelnetStatus(telnet.listeningPort, telnet.clientAddress, telnet.lastError)
                delay(250)
            }
        }
        scope.launch {
            while (isActive) {
                if (snapshot.value != null && printerError.value.isEmpty()) {
                    if (printerPending.value == null) pdp11Core.drainPrinter().takeIf { it.isNotEmpty() }?.let { printerPending.value = it }
                    val bytes = printerPending.value
                    if (bytes != null) {
                        try {
                            withContext(Dispatchers.IO) { printerCapture.write(bytes) }
                            printerPending.value = null
                            printerFile.value = printerCapture.currentFileName
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            printerError.value = error.message ?: error.javaClass.simpleName
                        }
                    }
                }
                delay(50)
            }
        }
        scope.launch {
            var nextRetry = 0L
            while (isActive) {
                val failed = telnetRequested.value && telnet.listeningPort == null && telnet.lastError != null
                if (!telnetManuallyStopped.value && snapshot.value != null && LocalNetworkInfo.hasActiveInternetConnection(context) &&
                    (!telnetRequested.value || failed) && SystemClock.elapsedRealtime() >= nextRetry) {
                    telnet.start(snapshot.value!!.telnetPort, console::acceptTelnetInput)
                    telnetRequested.value = true
                    nextRetry = SystemClock.elapsedRealtime() + 5000
                }
                delay(1000)
            }
        }
    }

    /**
     * Prepares UI state on the caller's (main) dispatcher, then performs all
     * scoped-storage and image-descriptor work away from the UI thread.
     */
    suspend fun bootPdp11(profile: Vz80Profile) {
        emulatorRunning.value = false
        inputQueues.clear()
        bootedProfile.value = null
        terminal.personality = if (profile.terminal.equals("adm3a", ignoreCase = true)) TerminalPersonality.ADM3A else TerminalPersonality.VT100
        terminal.reset()
        activeConsoleCore = pdp11Core
        withContext(Dispatchers.IO) {
            // Phase 1 8088 smoke profile: mount A: through D: using the same
            // descriptor-based boundary, then run the tiny guest echo loop.
            // Its output is published by ConsoleIoRouter to both the Android
            // terminal and every connected Telnet client.
            if (profile.bootDrive.trim().uppercase() in setOf("A", "B", "C", "D")) {
                pdp11Core.clearProfileDevices()
                profile.drives.forEach { (drive, image) ->
                    storage.mountPdpDrive(drive, image.trimStart('/'), pdp11Core)
                }
                check(pdp11Core.config("smoke_test=true")) { "Could not enable 8088 console smoke test" }
            } else {
            // vpdp1170 defines mem_size_kw in 1024-word units; PDP-11 words are
            // 16 bits, therefore each KW is 2048 addressable bytes.
            profile.memorySizeKw?.let { pdp11Core.memorySizeBytes = it * 2048 }
            profile.emulatorParameters.forEach { (name, value) ->
                // Unsupported profile diagnostics are retained for forward
                // compatibility; native cores selectively consume assignments.
                pdp11Core.config("$name=$value")
            }
            // DocumentFile lookups and disk-image attachment may block on the
            // Android document provider for seconds on a large media tree.
            storage.bootPdp11(profile, pdp11Core)
            }
        }
        diskError.value = ""
        bootedProfile.value = profile
        console.publishOutput("[Booting ${profile.title.ifBlank { profile.fileName }} from ${profile.bootDrive.uppercase()}]\r\n".encodeToByteArray())
        terminalVersion.intValue++
        emulatorRunning.value = true
    }

    fun captureDiagnostics() {
        latestDiagnostics.value = "Diagnostics requested..."
        pdp11Core.requestDiagnostics()
        scope.launch {
            while (isActive) {
                delay(50)
                val text = pdp11Core.takeDiagnostics()
                if (text.isNotEmpty()) {
                    // Use the common console router so a diagnostic button
                    // request is visible and copyable from Telnet as well as
                    // the local terminal/status panel.
                    console.publishOutput(("\r\n" + text + "\r\n").encodeToByteArray())
                    latestDiagnostics.value = withContext(Dispatchers.IO) {
                        runCatching { storage.appendDiagnostics(text) }
                            .fold(onSuccess = { text }, onFailure = { "$text\r\nLog write failed: ${it.message}" })
                    }
                    return@launch
                }
            }
        }
    }

    private fun shellCommand(input: String): String {
        val parts = input.trim().split(Regex("\\s+"), limit = 2)
        return when (parts.firstOrNull()?.lowercase().orEmpty()) {
            "" -> ""
            "help", "?" -> "help  status  drives  mount <drive> <image>  dismount <drive>\r\ncreate floppy <name.dsk>  create hdd <name.hdd>\r\ncpm ls <image.dsk>\r\ncpm export <image.dsk> <CPMFILE> <hostfile> [text]\r\ncpm import <image.dsk> <hostfile> <CPMFILE> [text]\r\ncpm rm <image.dsk> <CPMFILE>\r\ncpm recoveries | cpm restore <recovery-id>\r\npwd  ls  cat <file>  cp <source> <destination>  rm <file>  exit\r\n"
            "status" -> runBlocking(Dispatchers.Main.immediate) {
                "${AppBuildInfo.displayName}\r\nEmulator: ${if (emulatorRunning.value) "running" else "stopped"}\r\nProfile: ${bootedProfile.value?.fileName ?: selectedProfile.value?.fileName ?: "none"}\r\n"
            }
            "diagnostics", "diag" -> pdp11Core.diagnostics()
            "drives" -> runBlocking(Dispatchers.Main.immediate) {
                val configured = bootedProfile.value?.drives.orEmpty()
                pdp11Core.enumerateDrives().lineSequence().filter { it.isNotBlank() }.joinToString("\r\n", postfix = "\r\n") { line ->
                    val drive = line.substringBefore(' ').uppercase()
                    val image = when {
                        drive.startsWith("RL") -> configured[drive] ?: configured["DL${drive.last()}"]
                        drive == "DU0" -> configured["DU0"]
                        else -> configured[drive]
                    }
                    if (image.isNullOrBlank()) line else "$line file=$image"
                }
            }
            "mount", "dismount" -> {
                val isMount = parts[0].equals("mount", ignoreCase = true)
                val (drive, image) = if (isMount) {
                    val (d, name) = ShellDriveCommand.mountArguments(parts.getOrNull(1))
                    d to storage.resolveDiskImage(name)
                } else ShellDriveCommand.dismountArgument(parts.getOrNull(1)) to null
                runBlocking(Dispatchers.Main.immediate) {
                    val current = bootedProfile.value
                    check(emulatorRunning.value && current != null) { "Boot PDP-11 before changing drives" }
                    if (image == null) storage.dismountPdpDrive(drive, pdp11Core) else storage.mountPdpDrive(drive, image, pdp11Core)
                    val assignments = if (image == null) current.drives - drive else current.drives + (drive to image)
                    val updated = current.copy(drives = assignments)
                        bootedProfile.value = updated
                        diskError.value = ""
                    "${if (image == null) "Dismounted $drive" else "Mounted $image on $drive"}\r\n"
                }
            }
            "pwd" -> "/vPDP11\r\n"
            "ls" -> if (parts.size == 1) storage.shellList() else "usage: ls\r\n"
            "cat" -> if (parts.size == 2) storage.shellRead(parts[1]) else "usage: cat <file>\r\n"
            "cp" -> {
                val names = parts.getOrNull(1)?.split(Regex("\\s+")) ?: emptyList()
                if (names.size == 2) storage.shellCopy(names[0], names[1]) else "usage: cp <source> <destination>\r\n"
            }
            "rm" -> if (parts.size == 2) storage.shellRemove(parts[1]) else "usage: rm <file>\r\n"
            "cpm" -> {
                val args = parts.getOrNull(1)?.split(Regex("\\s+")) ?: emptyList()
                when (args.firstOrNull()?.lowercase()) {
                    "ls" -> if (args.size == 2) storage.shellCpmDirectory(args[1]) else "usage: cpm ls <image.dsk>\r\n"
                    "export" -> if (args.size in 4..5 && (args.size == 4 || args[4].equals("text", true)))
                        storage.shellCpmExport(args[1], args[2], args[3], args.size == 5)
                    else "usage: cpm export <image.dsk> <CPMFILE> <hostfile> [text]\r\n"
                    "import" -> if (args.size in 4..5 && (args.size == 4 || args[4].equals("text", true)))
                        storage.shellCpmImport(args[1], args[2], args[3], args.size == 5)
                    else "usage: cpm import <image.dsk> <hostfile> <CPMFILE> [text]\r\n"
                    "rm" -> if (args.size == 3) storage.shellCpmRemove(args[1], args[2]) else "usage: cpm rm <image.dsk> <CPMFILE>\r\n"
                    "recoveries" -> if (args.size == 1) storage.shellCpmRecoveries() else "usage: cpm recoveries\r\n"
                    "restore" -> if (args.size == 2) storage.shellCpmRestore(args[1]) else "usage: cpm restore <recovery-id>\r\n"
                    else -> "usage: cpm ls | export | import | rm | recoveries | restore (type help)\r\n"
                }
            }
            "create" -> {
                val args = parts.getOrNull(1)?.split(Regex("\\s+")) ?: emptyList()
                if (args.size in 2..3) storage.shellCreatePdpDisk(args[0], args[1], args.getOrNull(2)?.toLongOrNull() ?: 0) else "usage: create <rk05|rl01|rl02|du0> <name.dsk> [size-bytes]\r\n"
            }
            else -> "Unknown command: ${parts[0]} (type help)\r\n"
        }
    }

    override fun close() {
        scope.cancel()
        telnet.close()
        runCatching { printerCapture.close() }
        pdp11Core.close()
    }
}

data class TelnetStatus(val port: Int? = null, val clientAddress: String? = null, val error: String? = null)
