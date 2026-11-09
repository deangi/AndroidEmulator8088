package com.deangi.androidpdp11.storage

/** Validates the telnet shell's single-drive mount/dismount syntax. */
internal object ShellDriveCommand {
    fun drive(value: String): String {
        val name = value.removeSuffix(":").uppercase()
        require(name.length == 1 && name[0] in 'A'..'D') { "Drive must be A, B, C, or D" }
        return name
    }

    fun mountArguments(arguments: String?): Pair<String, String> {
        val parts = arguments?.trim()?.split(Regex("\\s+")) ?: emptyList()
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "usage: mount <A-D> <image.dsk|image.hdd>" }
        ShellFileNames.validate(parts[1])
        require(parts[1].endsWith(".dsk", true) || parts[1].endsWith(".hdd", true)) { "Image must end in .dsk or .hdd" }
        return drive(parts[0]) to parts[1]
    }

    fun dismountArgument(arguments: String?): String {
        val value = arguments?.trim().orEmpty()
        require(value.isNotEmpty() && value.none { it.isWhitespace() }) { "usage: dismount <A-D>" }
        return drive(value)
    }
}
