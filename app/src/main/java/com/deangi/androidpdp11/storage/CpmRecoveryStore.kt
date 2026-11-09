package com.deangi.androidpdp11.storage

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** App-private, durable originals retained only during an update or after failure. */
internal class CpmRecoveryStore(private val directory: File) {
    data class Record(val id: String, val name: String, val uri: String, val size: Int, val sha256: String)

    fun records(): List<Record> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "Recovery storage is unavailable" }
        return directory.listFiles()?.filter { it.name.endsWith(".json") }?.map { file ->
            val json = JSONObject(file.readText())
            val id = file.name.removeSuffix(".json")
            requireId(id)
            Record(id, json.getString("name"), json.getString("uri"), json.getInt("size"), json.getString("sha256"))
        }?.sortedBy { it.name.lowercase() } ?: throw IllegalStateException("Cannot list recovery storage")
    }

    fun journal(name: String, uri: String): ImageRecoveryJournal = object : ImageRecoveryJournal {
        override fun prepare(original: ByteArray): String {
            check(records().none { it.uri == uri }) { "Image has a pending recovery; use cpm recoveries" }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create recovery storage" }
            val id = UUID.randomUUID().toString()
            val backup = File(directory, "$id.image")
            val pending = File(directory, "$id.pending")
            val metadata = File(directory, "$id.json")
            try {
                FileOutputStream(backup).use { it.write(original); it.flush(); it.fd.sync() }
                check(backup.length() == original.size.toLong() && digest(backup.readBytes()) == digest(original)) { "Recovery backup verification failed" }
                val content = JSONObject().put("name", name).put("uri", uri).put("size", original.size).put("sha256", digest(original)).toString().toByteArray()
                FileOutputStream(pending).use { it.write(content); it.flush(); it.fd.sync() }
                check(pending.renameTo(metadata)) { "Cannot save recovery metadata" }
                return id
            } catch (error: Exception) {
                backup.delete(); pending.delete(); metadata.delete()
                throw error
            }
        }

        override fun discard(id: String) = remove(id)
    }

    fun read(id: String): Pair<Record, ByteArray> {
        requireId(id)
        val record = requireNotNull(records().firstOrNull { it.id == id }) { "Recovery not found: $id" }
        val backup = File(directory, "$id.image")
        require(backup.isFile && backup.length() == record.size.toLong()) { "Recovery backup has an invalid size" }
        val bytes = backup.readBytes()
        require(digest(bytes) == record.sha256) { "Recovery backup failed verification" }
        return record to bytes
    }

    fun remove(id: String) {
        requireId(id)
        // Delete metadata first: a surviving backup is harmless and cannot be
        // mistaken for a pending rollback if deleting the backup fails.
        val metadata = File(directory, "$id.json")
        check(!metadata.exists() || metadata.delete()) { "Could not clear recovery metadata" }
        val backup = File(directory, "$id.image")
        check(!backup.exists() || backup.delete()) { "Could not clear recovery backup" }
    }

    private fun requireId(id: String) {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid recovery identifier" }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
