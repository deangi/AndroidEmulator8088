package com.deangi.androidpdp11.storage

import java.io.OutputStream

/** Emulator-specific disk formats implement these operations independently of storage location. */
abstract class EmulatorDiskImageCreator {
    abstract fun createFloppy(output: OutputStream): Long
    abstract fun createHardDisk(output: OutputStream): Long
}
