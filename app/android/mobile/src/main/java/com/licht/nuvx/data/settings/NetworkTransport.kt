package com.licht.nuvx.data.settings

import android.net.NetworkCapabilities
import android.os.Build

enum class NetworkTransport { OFFLINE, UNKNOWN, WIFI, CELLULAR }

internal fun classifyNetworkTransport(capabilities: NetworkCapabilities): NetworkTransport {
    val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val other = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
        (Build.VERSION.SDK_INT >= 27 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) ||
        (Build.VERSION.SDK_INT >= 31 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) ||
        (Build.VERSION.SDK_INT >= 34 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_THREAD)) ||
        (Build.VERSION.SDK_INT >= 35 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE))
    return when {
        wifi && !cellular && !other -> NetworkTransport.WIFI
        cellular && !wifi && !other -> NetworkTransport.CELLULAR
        else -> NetworkTransport.UNKNOWN
    }
}
