package com.deangi.androidpdp11.storage

import java.io.OutputStream

/** Raw CP/M 2.2 disk layouts used by the original ESP32 vZ80. */
class Vz80DiskImageCreator : EmulatorDiskImageCreator() {
    override fun createFloppy(output: OutputStream): Long = fill(output, FLOPPY_BYTES)
    override fun createHardDisk(output: OutputStream): Long = fill(output, HDD_BYTES)

    private fun fill(output: OutputStream, bytes: Long): Long {
        val block = ByteArray(4096) { 0xE5.toByte() }
        var remaining = bytes
        while (remaining > 0) {
            val count = minOf(remaining, block.size.toLong()).toInt()
            output.write(block, 0, count)
            remaining -= count
        }
        output.flush()
        return bytes
    }

    companion object {
        const val FLOPPY_BYTES = 77L * 26 * 128
        const val HDD_BYTES = 2048L * 32 * 128
    }
}
