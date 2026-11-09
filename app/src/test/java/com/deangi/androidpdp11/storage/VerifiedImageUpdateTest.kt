package com.deangi.androidpdp11.storage

import org.junit.Assert.*
import org.junit.Test

class VerifiedImageUpdateTest {
    private val original = byteArrayOf(1, 2, 3)
    private val updated = byteArrayOf(4, 5, 6)

    private class Access(initial: ByteArray, val failWrites: Set<Int> = emptySet()) : ImageAccess {
        var bytes = initial.copyOf()
        var writes = 0
        override fun read() = bytes.copyOf()
        override fun write(bytes: ByteArray) {
            writes++
            this.bytes[0] = bytes[0] // Simulate a partial write before an I/O failure.
            if (writes in failWrites) error("write failed")
            this.bytes = bytes.copyOf()
        }
        override fun sync() = Unit
    }

    private class Journal(var failPrepare: Boolean = false, var failDiscard: Boolean = false) : ImageRecoveryJournal {
        var saved: ByteArray? = null
        override fun prepare(original: ByteArray): String {
            if (failPrepare) error("backup failed")
            saved = original.copyOf()
            return "recovery-id"
        }
        override fun discard(id: String) {
            if (failDiscard) error("cleanup failed")
            saved = null
        }
    }

    @Test fun updatesAndRemovesRecoveryAfterVerification() {
        val access = Access(original)
        val journal = Journal()
        assertNull(VerifiedImageUpdate.apply(access, journal, original, updated))
        assertArrayEquals(updated, access.bytes)
        assertNull(journal.saved)
    }

    @Test fun partialFailureRestoresOriginal() {
        val access = Access(original, setOf(1))
        val journal = Journal()
        val error = assertThrows(IllegalStateException::class.java) { VerifiedImageUpdate.apply(access, journal, original, updated) }
        assertTrue(error.message!!.contains("original image restored"))
        assertArrayEquals(original, access.bytes)
        assertNull(journal.saved)
    }

    @Test fun failedRollbackKeepsRecovery() {
        val access = Access(original, setOf(1, 2))
        val journal = Journal()
        val error = assertThrows(IllegalStateException::class.java) { VerifiedImageUpdate.apply(access, journal, original, updated) }
        assertTrue(error.message!!.contains("cpm restore recovery-id"))
        assertArrayEquals(original, journal.saved)
    }

    @Test fun backupFailureAndStaleImageDoNotWrite() {
        val access = Access(original)
        assertThrows(IllegalStateException::class.java) { VerifiedImageUpdate.apply(access, Journal(failPrepare = true), original, updated) }
        assertThrows(IllegalStateException::class.java) { VerifiedImageUpdate.apply(access, Journal(), updated, original) }
        assertEquals(0, access.writes)
        assertArrayEquals(original, access.bytes)
    }

    @Test fun failedCleanupReportsRetainedRecovery() {
        val access = Access(original)
        val journal = Journal(failDiscard = true)
        assertEquals("recovery-id", VerifiedImageUpdate.apply(access, journal, original, updated))
        assertArrayEquals(updated, access.bytes)
        assertArrayEquals(original, journal.saved)
    }
}
