package com.deangi.androidpdp11.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CpmFloppyImageTest {
    @Test fun readsBundledBootDiskDirectoryAndFile() {
        val image = CpmFloppyImage(File("src/main/assets/seed/altair48k-boot.dsk").readBytes())
        val directory = image.directory()
        assertTrue(directory.files.any { it.user == 0 && it.name == "SIN.BAS" })
        assertTrue(directory.files.any { it.name == "MBASIC.COM" })
        assertEquals(1, directory.opaqueEntries)
        assertEquals(103 * 1024, directory.freeBytes)
        val text = image.exportFile("SIN.BAS", text = true).toString(Charsets.US_ASCII)
        assertTrue(text.contains("100 FOR A=1 TO 100"))
        assertTrue(text.contains("130 NEXT"))
        assertEquals(128, image.exportFile("0:SIN.BAS").size)
    }

    @Test fun rejectsUnknownSelectorsAndMalformedDirectories() {
        listOf("32:FILE.TXT", "TOOLONGFILENAME.TXT", "*.COM", "../X", "FILE.").forEach { selector ->
            assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage.parseSelector(selector) }
        }
        assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage(ByteArray(1)) }
        val invalid = ByteArray(CpmFloppyImage.IMAGE_BYTES) { 0xE5.toByte() }
        invalid[2 * 26 * 128] = 0x20
        invalid[2 * 26 * 128 + 16] = 0xff.toByte()
        assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage(invalid).directory() }
    }

    @Test fun importsMultipleExtentsAndRemovesOnlyInCopies() {
        val source = File("src/main/assets/seed/altair48k-boot.dsk").readBytes() + ByteArray(6144) { 0x42 }
        val original = source.copyOf()
        val payload = ByteArray(20_123) { (it * 17).toByte() }
        val initial = CpmFloppyImage(source).directory()
        val imported = CpmFloppyImage(source).importFile("2:TEST.BIN", payload)
        assertArrayEquals(original, source)
        assertArrayEquals(original.copyOfRange(CpmFloppyImage.IMAGE_BYTES, original.size), imported.copyOfRange(CpmFloppyImage.IMAGE_BYTES, imported.size))
        assertArrayEquals(original.copyOfRange(9472, 9504), imported.copyOfRange(9472, 9504))
        val copied = CpmFloppyImage(imported)
        val file = copied.exportFile("2:TEST.BIN")
        assertArrayEquals(payload, file.copyOf(payload.size))
        assertEquals(0, file.size % 128)
        assertEquals(initial.freeBytes - 20 * 1024, copied.directory().freeBytes)
        assertEquals(initial.freeEntries - 2, copied.directory().freeEntries)
        assertThrows(IllegalArgumentException::class.java) { copied.importFile("2:TEST.BIN", payload) }
        val removed = copied.removeFile("2:TEST.BIN")
        assertEquals(initial.freeBytes, CpmFloppyImage(removed).directory().freeBytes)
        assertEquals(initial.freeEntries, CpmFloppyImage(removed).directory().freeEntries)
        assertArrayEquals(payload, CpmFloppyImage(imported).exportFile("2:TEST.BIN").copyOf(payload.size))
        assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage(removed).exportFile("2:TEST.BIN") }
    }

    @Test fun textPaddingEmptyFilesAndOutOfSpace() {
        val blank = ByteArray(CpmFloppyImage.IMAGE_BYTES) { 0xE5.toByte() }
        val image = CpmFloppyImage(blank)
        val text = "Hello\r\n".toByteArray()
        val withText = image.importFile("HELLO.TXT", text, text = true)
        assertArrayEquals(text, CpmFloppyImage(withText).exportFile("HELLO.TXT", text = true))
        val withEmpty = CpmFloppyImage(withText).importFile("EMPTY", byteArrayOf())
        assertEquals(0, CpmFloppyImage(withEmpty).exportFile("EMPTY").size)
        assertThrows(IllegalArgumentException::class.java) { image.importFile("HUGE.BIN", ByteArray(241 * 1024 + 1)) }
    }

    @Test fun reservesOpaqueEntryBlocksAndRejectsSharedBlocks() {
        val image = ByteArray(CpmFloppyImage.IMAGE_BYTES) { 0xE5.toByte() }
        val slot = 2 * 26 * 128
        image.fill(0, slot, slot + 32)
        image[slot] = 0x3B
        image[slot + 16] = 2
        val parsed = CpmFloppyImage(image)
        assertEquals(240 * 1024, parsed.directory().freeBytes)
        val imported = parsed.importFile("31:NEW.TXT", byteArrayOf(65))
        assertArrayEquals(image.copyOfRange(slot, slot + 32), imported.copyOfRange(slot, slot + 32))
        assertEquals(239 * 1024, CpmFloppyImage(imported).directory().freeBytes)
        val conflicted = imported.copyOf()
        conflicted[slot + 16] = 3
        assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage(conflicted).directory() }
    }

    @Test fun refusesRemovalOfReadOnlyFile() {
        val image = ByteArray(CpmFloppyImage.IMAGE_BYTES) { 0xE5.toByte() }
        val imported = CpmFloppyImage(image).importFile("LOCKED.TXT", byteArrayOf(65))
        val firstEntry = 2 * 26 * 128
        imported[firstEntry + 9] = (imported[firstEntry + 9].toInt() or 0x80).toByte()
        assertTrue(CpmFloppyImage(imported).directory().files.single().readOnly)
        assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage(imported).removeFile("LOCKED.TXT") }
    }

    @Test fun refusesFullDirectoryAndPreservesBinaryCtrlZ() {
        val blank = ByteArray(CpmFloppyImage.IMAGE_BYTES) { 0xE5.toByte() }
        val data = byteArrayOf(1, 0x1A, 2, 0x1A)
        var image = CpmFloppyImage(blank).importFile("BINARY.DAT", data)
        assertArrayEquals(data, CpmFloppyImage(image).exportFile("BINARY.DAT").copyOf(data.size))
        for (i in 1 until 64) image = CpmFloppyImage(image).importFile("F$i", byteArrayOf())
        assertEquals(0, CpmFloppyImage(image).directory().freeEntries)
        assertThrows(IllegalArgumentException::class.java) { CpmFloppyImage(image).importFile("EXTRA", byteArrayOf()) }
    }
}
