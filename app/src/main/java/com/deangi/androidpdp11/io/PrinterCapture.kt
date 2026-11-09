package com.deangi.androidpdp11.io

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

/** Sequential CP/M line-printer capture files, compatible with the ESP32 LPn.TXT convention. */
class PrinterCapture(private val contentResolver: ContentResolver, private val vz80Directory: () -> DocumentFile?) {
    private var output: java.io.OutputStream? = null
    @Volatile
    var currentFileName: String? = null
        private set

    @Synchronized
    fun beginSession(): String {
        close()
        val root = requireNotNull(vz80Directory()) { "vZ80 storage is unavailable" }
        val printer = root.findFile("printer") ?: requireNotNull(root.createDirectory("printer"))
        val next = generateSequence(0) { it + 1 }
            .map { "LP$it.TXT" }
            .first { printer.findFile(it) == null }
        val file = requireNotNull(printer.createFile("text/plain", next))
        output = requireNotNull(contentResolver.openOutputStream(file.uri, "w"))
        currentFileName = next
        return next
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (output == null) beginSession()
        requireNotNull(output).apply { write(bytes); flush() }
    }

    @Synchronized
    fun close() { output?.close(); output = null; currentFileName = null }

    fun listFiles(): List<String> = vz80Directory()?.findFile("printer")?.listFiles()
        ?.filter { it.isFile && it.name?.matches(Regex("LP[0-9]+\\.TXT", RegexOption.IGNORE_CASE)) == true }
        ?.mapNotNull { it.name }?.sorted().orEmpty()

    fun readFile(name: String): String {
        require(name.matches(Regex("LP[0-9]+\\.TXT", RegexOption.IGNORE_CASE)))
        val file = requireNotNull(vz80Directory()?.findFile("printer")?.findFile(name)) { "Capture not found" }
        return requireNotNull(contentResolver.openInputStream(file.uri)).bufferedReader(Charsets.ISO_8859_1).use { reader ->
            val chars = CharArray(16384)
            val count = reader.read(chars).coerceAtLeast(0)
            String(chars, 0, count) + if (reader.read() >= 0) "\n[Preview limited to 16384 characters]" else ""
        }
    }
}
