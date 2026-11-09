package com.deangi.androidpdp11.storage

/** Fixed-size image I/O, separated from Android so rollback can be tested. */
internal interface ImageAccess {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
    fun sync()
}

internal interface ImageRecoveryJournal {
    fun prepare(original: ByteArray): String
    fun discard(id: String)
}

internal object VerifiedImageUpdate {
    fun apply(access: ImageAccess, journal: ImageRecoveryJournal, original: ByteArray, updated: ByteArray): String? {
        require(original.size == updated.size) { "Image length cannot change" }
        check(access.read().contentEquals(original)) { "Image changed since it was read; retry" }
        val id = journal.prepare(original)
        var writeAttempted = false
        try {
            check(access.read().contentEquals(original)) { "Image changed before write; retry" }
            writeAttempted = true
            access.write(updated)
            access.sync()
            check(access.read().contentEquals(updated)) { "Image verification failed" }
        } catch (error: Exception) {
            if (writeAttempted) {
                try {
                    access.write(original)
                    access.sync()
                    check(access.read().contentEquals(original)) { "Rollback verification failed" }
                } catch (restoreError: Exception) {
                    throw IllegalStateException("Update failed and rollback failed; use cpm recoveries and cpm restore $id", restoreError)
                }
            }
            val cleared = runCatching { journal.discard(id) }.isSuccess
            val result = if (writeAttempted) "Update failed; original image restored" else "Image changed before write; retry"
            throw IllegalStateException(result + if (cleared) "" else "; recovery record $id remains", error)
        }
        // A retained journal is safe and visible through cpm recoveries.
        return if (runCatching { journal.discard(id) }.isSuccess) null else id
    }
}
