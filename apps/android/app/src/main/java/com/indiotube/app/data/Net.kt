package com.indiotube.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Live connectivity so the library can flip to downloads-only when the phone
// goes offline. "Online" means an internet-capable, validated network —
// captive portals do not count.
class Net(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _unmetered = MutableStateFlow(false)
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onUnavailable() {
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            refresh()
        }
    }

    init {
        refresh()
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun refresh() {
        val n = cm.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _unmetered.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }
}
