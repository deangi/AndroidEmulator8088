package com.deangi.androidpdp11.io

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Best-effort LAN address lookup for the System Information console output. */
object LocalNetworkInfo {
    fun hasActiveInternetConnection(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun ipv4Address(): String = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { network -> network.isUp && !network.isLoopback && !network.isVirtual }
            .flatMap { network -> network.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
            ?.hostAddress ?: "unavailable"
    } catch (_: Exception) {
        "unavailable"
    }
}
