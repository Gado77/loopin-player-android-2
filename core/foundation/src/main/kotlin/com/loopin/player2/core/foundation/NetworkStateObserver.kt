package com.loopin.player2.core.foundation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger

class NetworkStateObserver(
    context: Context,
    private val stateManager: DeviceStateManager,
    private val logger: PlayerLogger,
) : AutoCloseable {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    fun start() {
        stateManager.setNetworkAvailable(isNetworkAvailable())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
            registered = true
        }.onFailure {
            logger.log(LogLevel.WARN, TAG, "Network callback unavailable; startup remains offline-capable", it)
        }
    }

    override fun close() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun refresh() = stateManager.setNetworkAvailable(isNetworkAvailable())

    @Suppress("DEPRECATION")
    private fun isNetworkAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return connectivityManager.activeNetworkInfo?.isConnected == true
        }
        val network = connectivityManager.activeNetwork ?: return connectivityManager.activeNetworkInfo?.isConnected == true
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "NetworkState"
    }
}
