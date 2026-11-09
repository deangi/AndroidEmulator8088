package com.deangi.androidpdp11

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.deangi.androidpdp11.io.LocalNetworkInfo
import com.deangi.androidpdp11.storage.Vz80StorageRepository
import com.deangi.androidpdp11.storage.Vz80Profile
import com.deangi.androidpdp11.storage.Vz80ConfigDocument
import com.deangi.androidpdp11.terminal.Adm3aTerminal
import com.deangi.androidpdp11.terminal.TerminalDisplay

class MainActivity : ComponentActivity() {
    private var bound = false
    private var session by mutableStateOf<EmulatorSession?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            session = (binder as EmulatorService.LocalBinder).session
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) { session = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = android.content.Intent(this, EmulatorService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, service)
        bound = bindService(service, connection, android.content.Context.BIND_AUTO_CREATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val active = session
            if (active == null) {
                MaterialTheme { Text("Starting emulator...") }
            } else {
                LaunchedEffect(active.stopping.value) { if (active.stopping.value) finish() }
                AndroidEmulatorPdp11App(active)
            }
        }
    }

    override fun onDestroy() {
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }
}

@Composable
private fun AndroidEmulatorPdp11App(session: EmulatorSession) {
    val storage = session.storage
    var snapshot by session.snapshot
    var terminalVersion by session.terminalVersion
    var emulatorRunning by session.emulatorRunning
    var bootedProfile by session.bootedProfile
    var diskError by session.diskError
    var systemInfoVersion by session.systemInfoVersion
    var printerError by session.printerError
    var printerFile by session.printerFile
    var printerPending by session.printerPending
    var telnetRequested by session.telnetRequested
    var telnetManuallyStopped by session.telnetManuallyStopped
    var telnetStatus by session.telnetStatus
    var selectedProfile by session.selectedProfile
    var printerDialogOpen by remember { mutableStateOf(false) }
    var printerFiles by remember { mutableStateOf(emptyList<String>()) }
    var printerPreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var profileSelectorOpen by remember { mutableStateOf(false) }
    var drivesOpen by remember { mutableStateOf(false) }
    var configEditorOpen by remember { mutableStateOf(false) }
    var configDocuments by remember { mutableStateOf(emptyList<Vz80ConfigDocument>()) }
    val scope = rememberCoroutineScope()
    val terminal = session.terminal
    val printerCapture = session.printerCapture
    val emulator = session.emulator
    val telnet = session.telnet
    val inputQueues = session.inputQueues
    val bootInput = session.bootInput
    val console = session.console
    val selectStorage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            snapshot = storage.selectRoot(uri)
            selectedProfile = snapshot?.profiles?.firstOrNull()
            // The selected vZ80 folder owns wificonfig.ini. Restart so its
            // telnet port becomes effective immediately.
            if (telnetRequested) {
                telnet.stop()
                telnetRequested = false
            }
            console.publishOutput("Storage selection updated\r\n".encodeToByteArray())
        }
    }
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text("AndroidEmulatorPdp11", color = Color(0xFF55FF8A), style = MaterialTheme.typography.headlineMedium)
            Text("CP/M emulator console", color = Color(0xFFB4C8BA), modifier = Modifier.padding(top = 6.dp))

            TerminalDisplay(
                terminal = terminal,
                renderVersion = terminalVersion,
                onConsoleInput = console::acceptLocalInput,
                modifier = Modifier.padding(top = 16.dp),
            )

            ConsoleMenu(
                hasStorage = snapshot != null,
                onChooseStorage = { selectStorage.launch(null) },
                onEditConfig = {
                    configDocuments = storage.configDocuments()
                    configEditorOpen = true
                },
                onRestart = {
                    val booted = bootedProfile
                    if (booted != null) {
                        emulatorRunning = false
                        runCatching { storage.bootFloppies(booted, emulator) }
                            .onSuccess {
                                inputQueues.clear(); bootInput.arm(booted.bootText)
                                diskError = ""; terminal.reset(); terminalVersion++; emulatorRunning = true
                            }
                            .onFailure { console.publishOutput("Boot failed: ${it.message}\r\n".encodeToByteArray()) }
                    } else {
                        terminal.showCornerTestPattern()
                        terminalVersion++
                    }
                },
                onBoot = {
                    profileSelectorOpen = true
                },
                canManageDrives = bootedProfile != null && emulatorRunning,
                onManageDrives = { snapshot = storage.snapshot(); drivesOpen = true },
                onPrinter = {
                    printerDialogOpen = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { printerCapture.listFiles() } }
                            .onSuccess { printerFiles = it }
                            .onFailure { printerError = it.message ?: "Could not list captures" }
                    }
                },
                onSystemInfo = {
                    systemInfoVersion++
                },
                telnetRequested = telnetRequested,
                onToggleTelnet = {
                    if (telnetRequested) {
                        telnet.stop()
                        telnetRequested = false
                        telnetManuallyStopped = true
                    } else {
                        val port = snapshot?.telnetPort ?: DEFAULT_TELNET_PORT
                        telnet.start(port, console::acceptTelnetInput)
                        telnetRequested = true
                        telnetManuallyStopped = false
                    }
                },
            )

            StatusArea(snapshot, selectedProfile, emulatorRunning, bootedProfile, telnetRequested, telnetStatus, diskError, systemInfoVersion, printerFile, printerError)
        }
    }
    if (printerDialogOpen) {
        PrinterDialog(
            currentFile = printerFile,
            error = printerError,
            pending = printerPending != null,
            files = printerFiles,
            onDismiss = { printerDialogOpen = false },
            onNewCapture = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { printerCapture.close(); printerCapture.listFiles() } }
                        .onSuccess { printerFile = null; printerFiles = it }
                        .onFailure { printerError = it.message ?: "Could not close capture" }
                }
            },
            onRetry = { printerError = "" },
            onOpen = { name ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { printerCapture.readFile(name) } }
                        .onSuccess { printerPreview = name to it }
                        .onFailure { printerError = it.message ?: "Could not read capture" }
                }
            },
        )
    }
    printerPreview?.let { (name, contents) ->
        AlertDialog(
            onDismissRequest = { printerPreview = null },
            title = { Text(name) },
            text = { Text(contents, modifier = Modifier.height(300.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { printerPreview = null }) { Text("Close") } },
        )
    }
    if (drivesOpen && bootedProfile != null) {
        DriveManagementDialog(
            profile = bootedProfile!!,
            images = snapshot?.diskImages.orEmpty(),
            onDismiss = { drivesOpen = false },
            onApply = { profile ->
                drivesOpen = false
                runCatching { storage.replaceMountedDisks(profile, emulator) }
                    .onSuccess {
                        diskError = ""
                        bootedProfile = profile
                    }
                    .onFailure { error ->
                        console.publishOutput("Drive change failed: ${error.message}\r\n".encodeToByteArray())
                    }
            },
        )
    }
    if (configEditorOpen) {
        ConfigEditorDialog(
            documents = configDocuments,
            onSave = { name, content ->
                if (storage.saveConfigDocument(name, content)) {
                    val previousPort = snapshot?.telnetPort
                    snapshot = storage.snapshot()
                    if (telnetRequested && snapshot?.telnetPort != previousPort) {
                        telnet.stop()
                        telnetRequested = false
                    }
                    console.publishOutput("Saved $name\r\n".encodeToByteArray())
                } else {
                    console.publishOutput("Could not save $name\r\n".encodeToByteArray())
                }
                configEditorOpen = false
            },
            onDismiss = { configEditorOpen = false },
        )
    }
    if (profileSelectorOpen) {
        BootProfileDialog(
            profiles = snapshot?.profiles.orEmpty(),
            selected = selectedProfile,
            onSelect = { profile ->
                selectedProfile = profile
                profileSelectorOpen = false
                emulatorRunning = false
                bootedProfile = null
                runCatching { storage.bootFloppies(profile, emulator) }
                    .onSuccess {
                        inputQueues.clear(); bootInput.arm(profile.bootText)
                        terminal.reset(); terminalVersion++
                        diskError = ""
                        bootedProfile = profile
                        emulatorRunning = true
                    }
                    .onFailure { console.publishOutput("Boot failed: ${it.message}\r\n".encodeToByteArray()) }
            },
            onDismiss = { profileSelectorOpen = false },
        )
    }
}

@Composable
private fun PrinterDialog(
    currentFile: String?, error: String, pending: Boolean, files: List<String>,
    onDismiss: () -> Unit, onNewCapture: () -> Unit, onRetry: () -> Unit, onOpen: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Printer capture") },
        text = {
            Column {
                Text(currentFile?.let { "Capturing to printer/$it" } ?: "Waiting for printer output")
                if (error.isNotEmpty()) {
                    Text("Printer error: $error", color = Color(0xFFB3261E))
                    if (pending) Text("Some output may already be in the file; check it before retrying.")
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                TextButton(onClick = onNewCapture, enabled = !pending) { Text("Start new capture") }
                Text("Saved captures:")
                Column(modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState())) {
                    if (files.isEmpty()) Text("No captures yet")
                    files.forEach { name -> TextButton(onClick = { onOpen(name) }) { Text(name) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DriveManagementDialog(
    profile: Vz80Profile,
    images: List<String>,
    onDismiss: () -> Unit,
    onApply: (Vz80Profile) -> Unit,
) {
    var assignments by remember(profile) { mutableStateOf(profile.drives) }
    var choosingDrive by remember { mutableStateOf<String?>(null) }
    val duplicates = assignments.values.groupingBy { it.trimStart('/').lowercase() }.eachCount().any { it.value > 1 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mounted drives") },
        text = {
            Column {
                Text("Close files on affected drives before changing disks. At the CP/M prompt, press Ctrl-C after changing disks. Changes last for this session; the configuration file is not changed.")
                listOf("A", "B", "C", "D").forEach { drive ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("$drive: ${assignments[drive] ?: "(empty)"}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { choosingDrive = drive }) { Text("Change") }
                    }
                }
                if (duplicates) Text("Each drive must use a different image.", color = Color(0xFFB3261E))
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(profile.copy(drives = assignments)) }, enabled = !duplicates && assignments != profile.drives) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    choosingDrive?.let { drive ->
        AlertDialog(
            onDismissRequest = { choosingDrive = null },
            title = { Text("Select image for $drive:") },
            text = {
                Column(modifier = Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { assignments = assignments - drive; choosingDrive = null }) { Text("Dismount") }
                    images.forEach { image ->
                        TextButton(onClick = { assignments = assignments + (drive to image); choosingDrive = null }) { Text(image) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingDrive = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConfigEditorDialog(
    documents: List<Vz80ConfigDocument>,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(documents) { mutableStateOf(documents.firstOrNull()) }
    var content by remember(selected) { mutableStateOf(selected?.content.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit configuration") },
        text = {
            Column {
                if (documents.isEmpty()) {
                    Text("No z80config*.ini or wificonfig*.ini files were found.")
                } else {
                    Row {
                        documents.forEach { document ->
                            TextButton(onClick = { selected = document }) {
                                Text(document.fileName, color = if (document == selected) Color(0xFF55FF8A) else Color.Unspecified)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        label = { Text(selected?.fileName.orEmpty()) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onSave(it.fileName, content) } }, enabled = selected != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BootProfileDialog(
    profiles: List<Vz80Profile>,
    selected: Vz80Profile?,
    onSelect: (Vz80Profile) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select boot configuration") },
        text = {
            Column {
                if (profiles.isEmpty()) {
                    Text("No z80config*.ini files were found in vZ80.")
                } else {
                    profiles.forEach { profile ->
                        TextButton(onClick = { onSelect(profile) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(profile.title.ifBlank { profile.fileName })
                                Text("${profile.fileName} • boot ${profile.bootDrive}:", fontSize = 12.sp)
                                if (profile == selected) Text("Selected", color = Color(0xFF55FF8A), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ConsoleMenu(
    hasStorage: Boolean,
    onChooseStorage: () -> Unit,
    onEditConfig: () -> Unit,
    onRestart: () -> Unit,
    onBoot: () -> Unit,
    canManageDrives: Boolean,
    onManageDrives: () -> Unit,
    onPrinter: () -> Unit,
    onSystemInfo: () -> Unit,
    telnetRequested: Boolean,
    onToggleTelnet: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBoot, enabled = hasStorage) { Text("Boot") }
            Button(onClick = onManageDrives, enabled = canManageDrives) { Text("Drives") }
            Button(onClick = onRestart) { Text("Restart") }
            Button(onClick = onPrinter, enabled = hasStorage) { Text("Printer") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onChooseStorage) { Text(if (hasStorage) "Storage" else "Choose storage") }
            Button(onClick = onEditConfig, enabled = hasStorage) { Text("Config") }
            Button(onClick = onSystemInfo) { Text("System info") }
            Button(onClick = onToggleTelnet, enabled = hasStorage) { Text(if (telnetRequested) "Stop Telnet" else "Start Telnet") }
        }
    }
}

@Composable
private fun StatusArea(
    snapshot: com.deangi.androidpdp11.storage.Vz80StorageSnapshot?,
    selectedProfile: Vz80Profile?,
    emulatorRunning: Boolean,
    bootedProfile: Vz80Profile?,
    telnetRequested: Boolean,
    telnetStatus: TelnetStatus,
    diskError: String,
    systemInfoVersion: Int,
    printerFile: String?,
    printerError: String,
) {
    val status = remember(snapshot, selectedProfile, bootedProfile, emulatorRunning, telnetRequested, telnetStatus, diskError, systemInfoVersion, printerFile, printerError) {
        val telnet = when {
            !telnetRequested -> "off"
            telnetStatus.error != null -> "error: ${telnetStatus.error}"
            telnetStatus.clientAddress != null -> "connected: ${telnetStatus.clientAddress}"
            telnetStatus.port != null -> "listening on port ${telnetStatus.port}"
            else -> "starting"
        }
        val lines = buildList {
            add("AndroidEmulatorPdp11 status")
            add(AppBuildInfo.displayName)
            add("")
            add("Emulator: " + if (emulatorRunning) "running" else "stopped")
            add("Boot profile: ${selectedProfile?.fileName ?: "not selected"}")
            add("Storage: " + if (snapshot == null) "not selected" else "ready (${snapshot.profiles.size} profiles, ${snapshot.diskImages.size} images)")
            add("Telnet: $telnet")
            add("IP address: ${LocalNetworkInfo.ipv4Address()}")
            add("Printer: " + if (printerError.isNotEmpty()) "error: $printerError" else printerFile?.let { "printer/$it" } ?: "ready")
            if (systemInfoVersion > 0) {
                val profile = bootedProfile ?: selectedProfile
                val port = telnetStatus.port ?: snapshot?.telnetPort ?: DEFAULT_TELNET_PORT
                add("")
                add("System information:")
                add("  ${AppBuildInfo.displayName}")
                add("  Boot profile: ${profile?.let { "${it.fileName} (${it.title})" } ?: "not selected"}")
                add("  Telnet endpoint: ${LocalNetworkInfo.ipv4Address()}:$port")
            }
            add("")
            add("Mounted drives:")
            listOf("A", "B", "C", "D").forEach { drive -> add("  $drive: ${bootedProfile?.drives?.get(drive) ?: "(empty)"}") }
            if (diskError.isNotEmpty()) { add(""); add("Disk error: $diskError") }
        }
        Adm3aTerminal().apply { feed(lines.joinToString("\r\n").encodeToByteArray()) }
    }
    TerminalDisplay(terminal = status, renderVersion = 0, onConsoleInput = {}, interactive = false, modifier = Modifier.padding(top = 16.dp))
}

private const val DEFAULT_TELNET_PORT = 2323
