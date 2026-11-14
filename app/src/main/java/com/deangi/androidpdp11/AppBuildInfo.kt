package com.deangi.androidpdp11

/** The one user-facing identity for this emulator build. */
object EmulatorIdentity {
    const val emulatorName = "v8080"
    const val mediaFolderName = "v8088"
    const val profileFileName = "v8088config.ini"
    const val profileFilePattern = "v8088config*.ini"

    fun storageUnavailable() = "$emulatorName storage is unavailable"
    fun shellPath() = "/$emulatorName"
    fun consoleBanner() = "AndroidEmulator8088 $emulatorName console\r\n"
    fun managementShellBanner() = "\r\nAndroidEmulator8088 $emulatorName management shell\r\n" +
        "The active emulator continues running. Type help; exit returns to the console.\r\n" +
        "$emulatorName:/> "
}

/** Stable metadata source for the future System Information screen. */
object AppBuildInfo {
    val version: String = BuildConfig.APP_VERSION
    val buildDateUtc: String = BuildConfig.BUILD_DATE_UTC
    val displayName: String = "AndroidEmulator8088 ${EmulatorIdentity.emulatorName} $version ($buildDateUtc)"
}
