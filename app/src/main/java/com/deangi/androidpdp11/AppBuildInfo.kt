package com.deangi.androidpdp11

/** Stable metadata source for the future System Information screen. */
object AppBuildInfo {
    val version: String = BuildConfig.APP_VERSION
    val buildDateUtc: String = BuildConfig.BUILD_DATE_UTC
    val displayName: String = "AndroidEmulator8088 $version ($buildDateUtc)"
}
