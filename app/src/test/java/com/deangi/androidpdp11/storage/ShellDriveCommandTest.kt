package com.deangi.androidpdp11.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellDriveCommandTest {
    @Test fun acceptsDriveAndIndividualImage() {
        assertEquals("A" to "some.dsk", ShellDriveCommand.mountArguments("a some.dsk"))
        assertEquals("C" to "disk.HDD", ShellDriveCommand.mountArguments("C: disk.HDD"))
        assertEquals("D", ShellDriveCommand.dismountArgument("d:"))
    }

    @Test fun rejectsInvalidDrivesPathsWildcardsAndArity() {
        listOf("E some.dsk", "A *.dsk", "A ../x.dsk", "A x.txt", "A", "A x.dsk extra").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { ShellDriveCommand.mountArguments(input) }
        }
        listOf("", "A B", "E", "AB").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { ShellDriveCommand.dismountArgument(input) }
        }
    }
}
