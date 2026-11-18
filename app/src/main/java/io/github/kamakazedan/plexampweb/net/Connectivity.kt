package io.github.kamakazedan.plexampweb.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Whether the phone is on Wi-Fi (or another connection that isn't paid for by the megabyte). */
object Connectivity {
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return unmetered(caps)
    }

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return online(caps)
    }

    /**
     * Calls `onChange` (on a background thread) as the phone's connection changes: whether
     * it's online, and whether it's Wi-Fi (or the like), and working.
     */
    fun watch(context: Context, onChange: (online: Boolean, unmetered: Boolean) -> Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onChange(online(caps), unmetered(caps))
            }

            // (No connection to take its place: offline.)
            override fun onLost(network: Network) {
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                onChange(caps != null && online(caps), caps != null && unmetered(caps))
            }
        })
    }

    private fun online(caps: NetworkCapabilities) = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    // (Validated: it's been seen to reach the internet, so not a hotel's sign-in page.)
    private fun unmetered(caps: NetworkCapabilities) =
        online(caps) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
