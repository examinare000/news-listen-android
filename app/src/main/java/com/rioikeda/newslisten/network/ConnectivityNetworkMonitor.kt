package com.rioikeda.newslisten.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `ConnectivityManager` を使用してネットワーク接続状態を監視する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/NetworkMonitoring.swift の
 * `NetworkMonitor`（`NWPathMonitor` 版）のミラー。`Context` に依存するため
 * Robolectric 等を用いないユニットテストの対象外とし、[StubNetworkMonitor] で
 * 呼び出し側のロジックを検証する。
 */
class ConnectivityNetworkMonitor(
    context: Context,
) : NetworkMonitoring {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private val mutableIsOnline = MutableStateFlow(true)
    override val isOnline: StateFlow<Boolean> = mutableIsOnline.asStateFlow()

    private var isStarted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mutableIsOnline.value = true
        }

        override fun onLost(network: Network) {
            mutableIsOnline.value = false
        }
    }

    override fun start() {
        // 多重登録は ConnectivityManager が例外を投げるため、二重 start は無視して冪等にする。
        if (isStarted) return
        isStarted = true

        // WHY: registerDefaultNetworkCallback は「変化した時」のみ onAvailable/onLost を
        // 呼ぶため、start() 時点の実状態を同期的に読んで isOnline にシードしないと、
        // 実際は既にオフラインな端末で初期値 true のまま観測されてしまう罠がある。
        mutableIsOnline.value = currentlyOnline()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun stop() {
        if (!isStarted) return
        isStarted = false
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun currentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
