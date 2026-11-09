package com.deangi.androidpdp11.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.os.ParcelFileDescriptor
import com.deangi.androidpdp11.NativeEmulator
import com.deangi.androidpdp11.Pdp11NativeCore
import com.deangi.androidpdp11.io.PrinterCapture

internal const val DEFAULT_TELNET_PORT = 2323

data class Vz80Profile(val fileName: String, val title: String, val terminal: String, val bootDrive: String, val drives: Map<String, String>, val bootText: String = "")
data class Vz80ConfigDocument(val fileName: String, val content: String)
data class Vz80StorageSnapshot(
    val directoryName: String,
    val profiles: List<Vz80Profile>,
    val diskImages: List<String>,
    val telnetPort: Int = DEFAULT_TELNET_PORT,
)

class Vz80StorageRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("vz80-storage", Context.MODE_PRIVATE)
    private val mediaLock = Any()
    private var mountedNames: Set<String> = emptySet()
    private val imageCreator: EmulatorDiskImageCreator = Vz80DiskImageCreator()
    private val recovery = CpmRecoveryStore(java.io.File(context.filesDir, "cpm-recovery"))
    fun printerCapture(): PrinterCapture = PrinterCapture(context.contentResolver, ::vz80Directory)

    fun resolveDiskImage(name: String): String {
        ShellFileNames.validate(name)
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val matches = root.listFiles().filter { it.isFile && it.name.equals(name, ignoreCase = true) }
        val exact = matches.firstOrNull { it.name == name }
        val file = exact ?: matches.singleOrNull()
        require(file != null) { if (matches.isEmpty()) "Disk image not found: $name" else "Ambiguous disk image name: $name" }
        return requireNotNull(file.name)
    }

    /** Host-shell operations restricted to the selected vZ80 directory. */
    fun shellList(): String {
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val contents = root.listFiles().sortedBy { it.name?.lowercase() }.joinToString("\r\n") {
            if (it.isDirectory) {
                "<DIR>  ${it.name.orEmpty()}"
            } else {
                "%10d  %s".format(it.length(), it.name.orEmpty())
            }
        }.ifEmpty { "(empty)" }
        return "vZ80 folder: ${root.uri}\r\n$contents\r\n"
    }

    fun shellRead(name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "..") { "Use a file name in the vZ80 folder" }
        require(!name.endsWith(".dsk", true) && !name.endsWith(".hdd", true)) { "Disk images cannot be displayed as text" }
        val file = requireNotNull(vz80Directory()?.findFile(name)) { "File not found: $name" }
        require(file.isFile) { "Not a file: $name" }
        val bytes = requireNotNull(context.contentResolver.openInputStream(file.uri)) { "Cannot open $name" }.use { input ->
            val buffer = ByteArray(4097)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count <= 0) break
                total += count
            }
            buffer.copyOf(total)
        }
        return bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1).replace("\r\n", "\n").replace("\n", "\r\n") +
            if (bytes.size > 4096) "\r\n[truncated]\r\n" else "\r\n"
    }

    fun shellCpmDirectory(imageName: String): String = withUnmountedFloppy(imageName) { image, _, _ ->
        val directory = CpmFloppyImage(image).directory()
        buildString {
            append("CP/M floppy $imageName\r\n")
            append("User  File          Records  Allocated  Attributes\r\n")
            directory.files.forEach { file ->
                val attributes = listOfNotNull(if (file.readOnly) "R/O" else null, if (file.system) "SYS" else null).joinToString(",")
                append("%4d  %-12s  %7d  %6d KB  %s\r\n".format(file.user, file.name, file.records, file.blocks, attributes))
            }
            if (directory.files.isEmpty()) append("(no files)\r\n")
            append("Free: ${directory.freeBytes / 1024} KB; directory slots free: ${directory.freeEntries}\r\n")
            if (directory.opaqueEntries != 0) append("Note: ${directory.opaqueEntries} nonstandard occupied directory entries preserved.\r\n")
        }
    }

    fun shellCpmExport(imageName: String, selector: String, destination: String, text: Boolean): String = withUnmountedFloppy(imageName) { image, root, _ ->
        ShellFileNames.validate(destination)
        val bytes = CpmFloppyImage(image).exportFile(selector, text)
        writeNewFile(root, destination, bytes)
        "Exported $selector to $destination (${bytes.size} bytes, ${if (text) "trailing Ctrl-Z removed" else "full CP/M records"})\r\n"
    }

    fun shellCpmImport(imageName: String, source: String, selector: String, text: Boolean): String = withUnmountedFloppy(imageName) { image, root, target ->
        ShellFileNames.validate(source)
        ShellFileNames.requireUnmounted(source, mountedNames)
        val matches = root.listFiles().filter { it.isFile && it.name.equals(source, ignoreCase = true) }
        val inputFile = requireNotNull(matches.firstOrNull { it.name == source } ?: matches.singleOrNull()) { "Source file not found or ambiguous: $source" }
        require(inputFile.length() <= MAX_CPM_FILE_BYTES) { "Source file is too large for a floppy" }
        val data = requireNotNull(context.contentResolver.openInputStream(inputFile.uri)) { "Cannot open source: $source" }.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= MAX_CPM_FILE_BYTES) { "Source file is too large for a floppy" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val result = CpmFloppyImage(image).importFile(selector, data, text)
        val pending = updateExistingFloppy(target, image, result)
        "Imported $source as $selector into ${target.name}.\r\n" + recoveryWarning(pending)
    }

    fun shellCpmRemove(imageName: String, selector: String): String = withUnmountedFloppy(imageName) { image, _, target ->
        val result = CpmFloppyImage(image).removeFile(selector)
        val pending = updateExistingFloppy(target, image, result)
        "Removed $selector from ${target.name}.\r\n" + recoveryWarning(pending)
    }

    private fun updateExistingFloppy(file: DocumentFile, original: ByteArray, updated: ByteArray): String? {
        val journal = recovery.journal(file.name.orEmpty(), file.uri.toString())
        return requireNotNull(context.contentResolver.openFileDescriptor(file.uri, "rw")) { "Cannot open image for update" }.use { descriptor ->
            VerifiedImageUpdate.apply(DescriptorImageAccess(descriptor.fileDescriptor, original.size), journal, original, updated)
        }
    }

    private fun recoveryWarning(id: String?): String = id?.let { "Warning: recovery record $it remains; use cpm recoveries.\r\n" }.orEmpty()

    private fun requireNoPendingRecovery(file: DocumentFile) {
        check(recovery.records().none { it.uri == file.uri.toString() }) { "Image has a pending recovery; use cpm recoveries" }
    }

    fun shellCpmRecoveries(): String = synchronized(mediaLock) {
        val records = recovery.records()
        if (records.isEmpty()) "No pending image recoveries.\r\n" else
            "Pending recoveries (restore replaces the current image):\r\n" + records.joinToString("") { "${it.id}  ${it.name}\r\n" }
    }

    fun shellCpmRestore(id: String): String = synchronized(mediaLock) {
        val (record, backup) = recovery.read(id)
        ShellFileNames.requireUnmounted(record.name, mountedNames)
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val file = requireNotNull(root.listFiles().firstOrNull { it.isFile && it.uri.toString() == record.uri }) {
            "Original image is not in the selected vZ80 folder"
        }
        ShellFileNames.requireUnmounted(file.name.orEmpty(), mountedNames)
        try {
            requireNotNull(context.contentResolver.openFileDescriptor(file.uri, "rw")) { "Cannot open image for recovery" }.use { descriptor ->
                val access = DescriptorImageAccess(descriptor.fileDescriptor, backup.size)
                access.read()
                access.write(backup)
                access.sync()
                check(access.read().contentEquals(backup)) { "Recovery verification failed" }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Restore failed; recovery $id remains available", error)
        }
        recovery.remove(id)
        "Restored ${file.name} from recovery $id.\r\n"
    }

    private fun <T> withUnmountedFloppy(name: String, action: (ByteArray, DocumentFile, DocumentFile) -> T): T = synchronized(mediaLock) {
        ShellFileNames.validate(name)
        require(name.endsWith(".dsk", true)) { "Only floppy .dsk images are supported" }
        ShellFileNames.requireUnmounted(name, mountedNames)
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val matches = root.listFiles().filter { it.isFile && it.name.equals(name, ignoreCase = true) }
        val file = requireNotNull(matches.firstOrNull { it.name == name } ?: matches.singleOrNull()) { "Floppy image not found or ambiguous: $name" }
        requireNoPendingRecovery(file)
        require(file.length() in CpmFloppyImage.IMAGE_BYTES.toLong()..MAX_FLOPPY_IMAGE_BYTES.toLong()) { "Unsupported floppy image size" }
        val image = requireNotNull(context.contentResolver.openInputStream(file.uri)) { "Cannot open image: $name" }.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= MAX_FLOPPY_IMAGE_BYTES) { "Unsupported floppy image size" }
                output.write(buffer, 0, count)
            }
            require(output.size() >= CpmFloppyImage.IMAGE_BYTES) { "Unsupported floppy image size" }
            output.toByteArray()
        }
        action(image, root, file)
    }

    private fun writeNewFile(root: DocumentFile, name: String, bytes: ByteArray) {
        ShellFileNames.validate(name)
        require(root.listFiles().none { it.name.equals(name, ignoreCase = true) }) { "Destination already exists: $name" }
        val file = requireNotNull(root.createFile("application/octet-stream", name)) { "Could not create: $name" }
        try {
            check(file.name == name) { "Storage provider changed the destination name" }
            requireNotNull(context.contentResolver.openOutputStream(file.uri, "w")) { "Could not write: $name" }.use { it.write(bytes); it.flush() }
            check(file.length() == bytes.size.toLong()) { "File size verification failed" }
            requireNotNull(context.contentResolver.openInputStream(file.uri)) { "Cannot verify: $name" }.use { input ->
                val buffer = ByteArray(8192)
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
                    check(count > 0) { "File verification failed: $name" }
                    for (i in 0 until count) check(buffer[i] == bytes[offset + i]) { "File verification failed: $name" }
                    offset += count
                }
                check(input.read() < 0) { "File verification failed: $name" }
            }
        } catch (error: Exception) {
            if (!file.delete()) throw IllegalStateException("Write failed; partial file may remain: ${file.name}", error)
            throw error
        }
    }

    fun shellCopy(source: String, destination: String): String = synchronized(mediaLock) {
        ShellFileNames.validate(source)
        ShellFileNames.validate(destination)
        ShellFileNames.requireUnmounted(source, mountedNames)
        ShellFileNames.requireUnmounted(destination, mountedNames)
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val files = root.listFiles()
        val from = requireNotNull(files.firstOrNull { it.name.equals(source, ignoreCase = true) }) { "File not found: $source" }
        require(from.isFile) { "Not a file: $source" }
        requireNoPendingRecovery(from)
        require(files.none { it.name.equals(destination, ignoreCase = true) }) { "Destination already exists: $destination" }
        val target = requireNotNull(root.createFile("application/octet-stream", destination)) { "Could not create: $destination" }
        try {
            check(target.name == destination) { "Storage provider changed the destination name" }
            requireNotNull(context.contentResolver.openInputStream(from.uri)) { "Could not open: $source" }.use { input ->
                requireNotNull(context.contentResolver.openOutputStream(target.uri, "w")) { "Could not write: $destination" }.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
        } catch (error: Exception) {
            if (!target.delete()) throw IllegalStateException("Copy failed; partial file may remain: ${target.name}", error)
            throw error
        }
        "Copied $source to $destination\r\n"
    }

    fun shellRemove(name: String): String = synchronized(mediaLock) {
        ShellFileNames.validate(name)
        ShellFileNames.requireUnmounted(name, mountedNames)
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val file = requireNotNull(root.listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }) { "File not found: $name" }
        require(file.isFile) { "Not a file: $name" }
        requireNoPendingRecovery(file)
        check(file.delete()) { "Could not remove: $name" }
        "Removed $name\r\n"
    }

    fun shellCreateDisk(type: String, name: String): String = synchronized(mediaLock) {
        ShellFileNames.validate(name)
        val floppy = when (type.lowercase()) {
            "floppy" -> true
            "hdd" -> false
            else -> throw IllegalArgumentException("usage: create floppy <name.dsk> | create hdd <name.hdd>")
        }
        require(name.endsWith(if (floppy) ".dsk" else ".hdd", ignoreCase = true)) {
            if (floppy) "Floppy image name must end in .dsk" else "Hard-disk image name must end in .hdd"
        }
        ShellFileNames.requireUnmounted(name, mountedNames)
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        require(root.listFiles().none { it.name.equals(name, ignoreCase = true) }) { "Destination already exists: $name" }
        val file = requireNotNull(root.createFile("application/octet-stream", name)) { "Could not create: $name" }
        try {
            check(file.name == name) { "Storage provider changed the destination name" }
            val bytes = requireNotNull(context.contentResolver.openOutputStream(file.uri, "w")) { "Could not write: $name" }.use { output ->
                if (floppy) imageCreator.createFloppy(output) else imageCreator.createHardDisk(output)
            }
            check(file.length() == bytes) { "Image size verification failed" }
            "Created $name ($bytes bytes, blank CP/M data disk)\r\n"
        } catch (error: Exception) {
            if (!file.delete()) throw IllegalStateException("Creation failed; partial file may remain: ${file.name}", error)
            throw error
        }
    }

    /** Open selected images without truncation; native code duplicates the descriptors. */
    fun bootFloppies(profile: Vz80Profile, emulator: NativeEmulator) = synchronized(mediaLock) { withDiskImages(profile) { fds, hddMask ->
        val boot = profile.bootDrive.singleOrNull()?.uppercaseChar()?.minus('A') ?: -1
        require(boot in 0..3) { "Invalid boot drive: ${profile.bootDrive}" }
        require(fds[boot] >= 0) { "Boot drive ${profile.bootDrive} is not configured" }
        require(hddMask and (1 shl boot) == 0) { "Boot from a floppy .dsk; hard disks can be mounted as data drives" }
        check(emulator.bootDisks(fds, hddMask, boot)) { "Could not read boot sector or mount disk images" }
        mountedNames = profile.drives.values.toSet()
    } }

    /**
     * Phase-3 RT-11 path: attach the configured RK0 image to the PDP-11
     * RK05 controller.  The native core duplicates the descriptor before
     * this method returns, so Android retains no host path in native code.
     */
    fun bootPdp11Rk05(profile: Vz80Profile, emulator: Pdp11NativeCore) = synchronized(mediaLock) {
        val imageName = profile.drives["RK0"]?.trim()?.trimStart('/')
            ?: throw IllegalArgumentException("RT-11 profile has no rk0 image")
        require(imageName.endsWith(".dsk", ignoreCase = true)) { "RK0 image must end in .dsk" }
        val root = requireNotNull(vz80Directory()) { "vPDP11 storage is unavailable" }
        val image = requireNotNull(root.findFile(imageName)) { "Disk image not found: $imageName" }
        require(image.isFile) { "Not a disk image: $imageName" }
        require(image.length() >= 512) { "Disk image is too small: $imageName" }
        requireNotNull(context.contentResolver.openFileDescriptor(image.uri, "rw")) { "Cannot open disk image: $imageName" }.use { descriptor ->
            check(emulator.bootRk05(descriptor.fd)) { "Could not attach RK0 image" }
        }
        mountedNames = setOf(imageName)
    }

    fun replaceMountedDisks(profile: Vz80Profile, emulator: NativeEmulator) = synchronized(mediaLock) { withDiskImages(profile) { fds, hddMask ->
        check(emulator.replaceDisks(fds, hddMask)) { emulator.takeDiskError().ifEmpty { "Could not mount disk images" } }
        mountedNames = profile.drives.values.toSet()
    } }

    private fun withDiskImages(profile: Vz80Profile, action: (IntArray, Int) -> Unit) {
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val opened = mutableListOf<ParcelFileDescriptor>()
        val fds = IntArray(4) { -1 }
        var hddMask = 0
        val names = mutableSetOf<String>()
        try {
            for (i in 0..3) {
                val configured = profile.drives[('A' + i).toString()] ?: continue
                val name = configured.trim().trimStart('/')
                require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "..") { "Invalid disk image name: $configured" }
                val hdd = name.endsWith(".hdd", true)
                require(hdd || name.endsWith(".dsk", true)) { "Unsupported disk image: $name" }
                require(names.add(name.lowercase())) { "Disk image configured more than once: $name" }
                val file = requireNotNull(root.findFile(name)) { "Disk image not found: $name" }
                require(file.isFile && if (hdd) file.length() == Vz80DiskImageCreator.HDD_BYTES else file.length() >= Vz80DiskImageCreator.FLOPPY_BYTES) { "Invalid image size: $name" }
                requireNoPendingRecovery(file)
                if (hdd) hddMask = hddMask or (1 shl i)
                val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(file.uri, "rw")) { "Cannot open disk image: $name" }
                opened.add(descriptor)
                fds[i] = descriptor.fd
            }
            action(fds, hddMask)
        } finally {
            opened.forEach { it.close() }
        }
    }

    fun selectRoot(uri: Uri): Vz80StorageSnapshot? {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        preferences.edit().putString(TREE_URI_KEY, uri.toString()).apply()
        return prepare(uri)
    }

    fun snapshot(): Vz80StorageSnapshot? = preferences.getString(TREE_URI_KEY, null)?.let(Uri::parse)?.let(::prepare)

    fun configDocuments(): List<Vz80ConfigDocument> = vz80Directory()
        ?.listFiles()
        ?.filter { file -> file.isFile && file.name?.let(::isConfigFile) == true }
        ?.mapNotNull { file ->
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                Vz80ConfigDocument(file.name.orEmpty(), reader.readText())
            }
        }
        ?.sortedBy { document -> document.fileName.lowercase() }
        .orEmpty()

    fun saveConfigDocument(fileName: String, content: String): Boolean {
        if (!isConfigFile(fileName)) return false
        val file = vz80Directory()?.findFile(fileName) ?: return false
        return try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(content)
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private fun prepare(rootUri: Uri): Vz80StorageSnapshot? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        if (!root.canRead() || !root.canWrite()) return null
        val directory = root.findFile(VZ80_DIRECTORY) ?: root.createDirectory(VZ80_DIRECTORY) ?: return null
        seed(directory)
        return scan(directory)
    }

    private fun vz80Directory(): DocumentFile? = preferences.getString(TREE_URI_KEY, null)
        ?.let(Uri::parse)
        ?.let { rootUri -> DocumentFile.fromTreeUri(context, rootUri) }
        ?.findFile(VZ80_DIRECTORY)

    private fun seed(directory: DocumentFile) {
        context.assets.list(SEED_DIRECTORY).orEmpty().forEach { assetName ->
            if (directory.findFile(assetName) != null) return@forEach
            val target = directory.createFile("application/octet-stream", assetName) ?: return@forEach
            context.assets.open("$SEED_DIRECTORY/$assetName").use { input -> context.contentResolver.openOutputStream(target.uri, "w")?.use(input::copyTo) }
        }
    }

    private fun scan(directory: DocumentFile): Vz80StorageSnapshot {
        val files = directory.listFiles().filter { it.isFile }
        val profiles = files.filter { it.name?.lowercase()?.let { name -> name == "pdpconfig.ini" || name.startsWith("pdpconfig-") && name.endsWith(".ini") } == true }
            .map(::parseProfile).sortedBy { it.fileName.lowercase() }
        val images = files.mapNotNull { it.name }.filter { it.endsWith(".dsk", true) || it.endsWith(".hdd", true) }.sortedBy(String::lowercase)
        val telnetPort = files.firstOrNull { it.name.equals("wificonfig.ini", ignoreCase = true) }
            ?.let(::parseTelnetPort) ?: DEFAULT_TELNET_PORT
        return Vz80StorageSnapshot(directory.name ?: VZ80_DIRECTORY, profiles, images, telnetPort)
    }

    private fun parseProfile(file: DocumentFile): Vz80Profile {
        val values = mutableMapOf<String, String>()
        var section = ""
        var bootText = ""
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines -> lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith(';') || line.startsWith('#') -> Unit
                line.startsWith('[') && line.endsWith(']') -> section = line.substring(1, line.length - 1).lowercase()
                '=' in line -> {
                    val (key, value) = line.split('=', limit = 2)
                    val name = key.trim().lowercase()
                    val text = value.trim()
                    if (section == "console" && name in setOf("boot_text", "boot_input", "typeahead", "boot_keys")) bootText = text
                    values["$section.$name"] = text.trim('"')
                }
            }
        } }
        val drives = values.filterKeys { it.startsWith("disks.") }
            .mapKeys { (key, _) -> key.removePrefix("disks.").uppercase() }
            .filter { (_, value) -> value.isNotBlank() }
        return Vz80Profile(file.name ?: "pdpconfig.ini", values["system.title"] ?: file.name.orEmpty(), values["console.terminal"] ?: "adm3a", values["disks.boot"]?.uppercase() ?: "RK0", drives, bootText)
    }

    private fun parseTelnetPort(file: DocumentFile): Int {
        var section = ""
        var port: Int? = null
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines -> lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith('[') && line.endsWith(']') -> section = line.substring(1, line.length - 1).lowercase()
                section == "telnet" && '=' in line -> {
                    val (key, value) = line.split('=', limit = 2)
                    if (key.trim().equals("port", ignoreCase = true)) port = value.trim().toIntOrNull()
                }
            }
        } }
        return port?.takeIf { it in 1024..65535 } ?: DEFAULT_TELNET_PORT
    }

    private fun isConfigFile(name: String): Boolean {
        val lower = name.lowercase()
        return (lower == "pdpconfig.ini" || lower.startsWith("pdpconfig-") ||
            lower == "wificonfig.ini" || lower.startsWith("wificonfig-")) && lower.endsWith(".ini")
    }

    private companion object {
        const val MAX_FLOPPY_IMAGE_BYTES = 16 * 1024 * 1024
        const val MAX_CPM_FILE_BYTES = 241 * 1024
        const val TREE_URI_KEY = "tree_uri"
        const val VZ80_DIRECTORY = "vPDP11"
        const val SEED_DIRECTORY = "seed"
    }
}
