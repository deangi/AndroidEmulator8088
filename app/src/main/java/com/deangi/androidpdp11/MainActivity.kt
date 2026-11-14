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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    var latestDiagnostics by session.latestDiagnostics
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
    var configLoading by remember { mutableStateOf(false) }
    var configDocuments by remember { mutableStateOf(emptyList<Vz80ConfigDocument>()) }
    val scope = rememberCoroutineScope()
    val terminal = session.terminal
    val printerCapture = session.printerCapture
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
            Text("AndroidEmulator8088", color = Color(0xFF55FF8A), style = MaterialTheme.typography.headlineMedium)
            Text("Intel 8088 console", color = Color(0xFFB4C8BA), modifier = Modifier.padding(top = 6.dp))

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
                    if (!configLoading) {
                        configLoading = true
                        scope.launch {
                            runCatching {
                                // DocumentFile queries can be slow for a tree holding
                                // large disk images.  Never perform them on Compose's
                                // input/UI thread or Android will report an ANR.
                                withContext(Dispatchers.IO) { storage.configDocuments() }
                            }.onSuccess { documents ->
                                configDocuments = documents
                                configEditorOpen = true
                            }.onFailure { error ->
                                console.publishOutput("Could not read configuration files: ${error.message}\r\n".encodeToByteArray())
                            }
                            configLoading = false
                        }
                    }
                },
                onRestart = {
                    val booted = bootedProfile
                    if (booted != null) {
                        emulatorRunning = false
                        scope.launch {
                            runCatching { session.bootPdp11(booted) }
                                .onSuccess {
                                    inputQueues.clear(); bootInput.arm(booted.bootText); session.bootScript.arm(booted.bootScript)
                                    diskError = ""; terminal.reset(); terminalVersion++; emulatorRunning = true
                                }
                                .onFailure { console.publishOutput("Boot failed: ${it.message}\r\n".encodeToByteArray()) }
                        }
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
                onDiagnostics = { session.captureDiagnostics(); systemInfoVersion++ },
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

            StatusArea(snapshot, selectedProfile, emulatorRunning, bootedProfile, telnetRequested, telnetStatus, diskError, systemInfoVersion, printerFile, printerError, latestDiagnostics, session.frontPanelPc())
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
                runCatching { session.replacePdpDrives(profile) }
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
    if (configLoading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Loading configuration") },
            text = { Text("Reading configuration files…") },
            confirmButton = {},
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
                scope.launch {
                    runCatching { session.bootPdp11(profile) }
                        .onSuccess {
                            inputQueues.clear(); bootInput.arm(profile.bootText); session.bootScript.arm(profile.bootScript)
                            terminal.reset(); terminalVersion++
                            diskError = ""
                            bootedProfile = profile
                            emulatorRunning = true
                        }
                        .onFailure { console.publishOutput("Boot failed: ${it.message}\r\n".encodeToByteArray()) }
                }
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
                Text("Close files on affected drives before changing disks. Changes last for this session; the configuration file is not changed.")
                (listOf("RK0", "RK1", "RL0", "RL1", "RL2", "RL3", "DU0", "RP0", "RP1") + assignments.keys).distinct().forEach { drive ->
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
                    Text("No ${EmulatorIdentity.profileFilePattern} or wificonfig*.ini files were found.")
                } else {
                    // A row silently ran off the dialog on small screens.
                    // Keep every discovered configuration reachable.
                    Column(
                        modifier = Modifier.fillMaxWidth().height(130.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        documents.forEach { document ->
                            TextButton(onClick = { selected = document }, modifier = Modifier.fillMaxWidth()) {
                                Text(document.fileName, color = if (document == selected) Color(0xFF55FF8A) else Color.Unspecified)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        label = { Text(selected?.fileName.orEmpty()) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
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
                    Text("No ${EmulatorIdentity.profileFilePattern} files were found in ${EmulatorIdentity.emulatorName}.")
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
    onDiagnostics: () -> Unit,
    telnetRequested: Boolean,
    onToggleTelnet: () -> Unit,
) {
    // Equal-width cells keep every action visible on the Fire display, even
    // when Android applies a larger system font scale.
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val menuColors = ButtonDefaults.buttonColors(
            disabledContainerColor = Color(0xFF3E4742),
            disabledContentColor = Color(0xFFC5CDC7),
        )
        val buttonPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onBoot, enabled = hasStorage, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Boot", fontSize = 11.sp) }
            Button(onClick = onManageDrives, enabled = canManageDrives, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Drives", fontSize = 11.sp) }
            Button(onClick = onRestart, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Restart", fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onPrinter, enabled = hasStorage, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Printer", fontSize = 11.sp) }
            Button(onClick = onChooseStorage, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Storage", fontSize = 11.sp) }
            Button(onClick = onEditConfig, enabled = hasStorage, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Config", fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onSystemInfo, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("System info", fontSize = 11.sp) }
            Button(onClick = onDiagnostics, enabled = hasStorage, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text("Diagnostics", fontSize = 11.sp) }
            Button(onClick = onToggleTelnet, enabled = hasStorage, modifier = Modifier.weight(1f).height(42.dp), colors = menuColors, contentPadding = buttonPadding) { Text(if (telnetRequested) "Stop Telnet" else "Start Telnet", fontSize = 11.sp) }
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
    latestDiagnostics: String,
    frontPanelPc: Int,
) {
    val status = remember(snapshot, selectedProfile, bootedProfile, emulatorRunning, telnetRequested, telnetStatus, diskError, systemInfoVersion, printerFile, printerError, latestDiagnostics) {
        val telnet = when {
            !telnetRequested -> "off"
            telnetStatus.error != null -> "error: ${telnetStatus.error}"
            telnetStatus.clientAddress != null -> "connected: ${telnetStatus.clientAddress}"
            telnetStatus.port != null -> "listening on port ${telnetStatus.port}"
            else -> "starting"
        }
        val lines = buildList {
            add("AndroidEmulator8088 status")
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
                profile?.memorySizeKw?.let { kw -> add("  Memory: $kw KW (${kw * 2} KiB)") }
                add("  Front panel PC: ${frontPanelPc.toString(8).padStart(6, '0')}")
                add("  Telnet endpoint: ${LocalNetworkInfo.ipv4Address()}:$port")
            }
            add("")
            add("8088 configured drives:")
            val drives = bootedProfile?.drives?.toSortedMap().orEmpty()
            if (drives.isEmpty()) add("  (none)")
            else drives.forEach { (drive, image) -> add("  $drive: $image") }
            if (diskError.isNotEmpty()) { add(""); add("Disk error: $diskError") }
            if (latestDiagnostics.isNotEmpty()) { add(""); add(latestDiagnostics) }
        }
        Adm3aTerminal().apply { feed(lines.joinToString("\r\n").encodeToByteArray()) }
    }
    TerminalDisplay(terminal = status, renderVersion = 0, onConsoleInput = {}, interactive = false, modifier = Modifier.padding(top = 16.dp))
}

private const val DEFAULT_TELNET_PORT = 2323
