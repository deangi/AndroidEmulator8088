package com.deangi.androidpdp11.storage

/** Simple shell operations accept single names in the vZ80 folder only. */
internal object ShellFileNames {
    fun validate(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it.isWhitespace() || it.isISOControl() || it in "/\\*?" }) {
            "Use one file name in the vZ80 folder; paths and wildcards are not supported"
        }
    }

    fun requireUnmounted(name: String, mounted: Set<String>) {
        require(mounted.none { it.trimStart('/').equals(name, ignoreCase = true) }) {
            "Disk image is mounted: $name; dismount it first"
        }
    }
}
