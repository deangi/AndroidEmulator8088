package com.deangi.androidpdp11.storage

import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Vz80DiskImageCreatorTest {
    private class CheckingOutput : OutputStream() {
        var count = 0L
        override fun write(value: Int) { assertEquals(0xE5, value and 0xff); count++ }
        override fun write(data: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) assertEquals(0xE5, data[i].toInt() and 0xff)
            count += length
        }
    }

    @Test fun createsExactEsp32GeometryAndFill() {
        val creator: EmulatorDiskImageCreator = Vz80DiskImageCreator()
        val floppy = CheckingOutput()
        val hardDisk = CheckingOutput()
        assertEquals(256256L, creator.createFloppy(floppy))
        assertEquals(256256L, floppy.count)
        assertEquals(8388608L, creator.createHardDisk(hardDisk))
        assertEquals(8388608L, hardDisk.count)
    }

    @Test fun propagatesWriteFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("disk full") }
            override fun write(data: ByteArray, offset: Int, length: Int) { throw IOException("disk full") }
        }
        assertThrows(IOException::class.java) { Vz80DiskImageCreator().createFloppy(output) }
    }
}
