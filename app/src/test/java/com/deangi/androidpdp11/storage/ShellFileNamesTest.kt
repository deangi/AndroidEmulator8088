package com.deangi.androidpdp11.storage

import org.junit.Assert.assertThrows
import org.junit.Test

class ShellFileNamesTest {
    @Test fun rejectsWildcardsPathsAndSpecialNames() {
        listOf("", ".", "..", "*.dsk", "foo?", "a/b", "a\\b", "a b", "a\n").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { ShellFileNames.validate(name) }
        }
        ShellFileNames.validate("source.file")
        ShellFileNames.validate("copy.hdd")
    }

    @Test fun protectsMountedNamesIgnoringCaseAndLeadingSlash() {
        val mounted = setOf("/cdrive.hdd", "A.DSK")
        assertThrows(IllegalArgumentException::class.java) { ShellFileNames.requireUnmounted("CDRIVE.HDD", mounted) }
        assertThrows(IllegalArgumentException::class.java) { ShellFileNames.requireUnmounted("a.dsk", mounted) }
        ShellFileNames.requireUnmounted("other.dsk", mounted)
    }
}
