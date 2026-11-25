package com.licht.nuvx.data.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

internal object MeteredConnectionObserver {
    fun watch(
        context: Context,
        meteredConnection: MutableStateFlow<Boolean?>,
        networkTransport: MutableStateFlow<NetworkTransport>,
    ) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun update(network: Network?, capabilities: NetworkCapabilities? = null) {
            if (network == null) {
                meteredConnection.value = null
                networkTransport.value = NetworkTransport.OFFLINE
                return
            }
            val current = capabilities ?: runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            if (current == null) {
                meteredConnection.value = runCatching { manager.isActiveNetworkMetered }.getOrNull()
                networkTransport.value = NetworkTransport.UNKNOWN
                return
            }
            meteredConnection.value = !current.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            networkTransport.value = classifyNetworkTransport(current)
        }
        val refresh = { update(manager.activeNetwork) }
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (manager.activeNetwork == network) {
                            networkTransport.value = NetworkTransport.UNKNOWN
                            update(network)
                        }
                    }
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        if (manager.activeNetwork == network) update(network, capabilities)
                    }
                },
            )
        }
    }
}
