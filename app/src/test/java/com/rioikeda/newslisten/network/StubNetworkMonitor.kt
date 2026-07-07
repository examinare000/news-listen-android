package com.rioikeda.newslisten.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * テスト用のスタブ。接続状態を手動で操作できる。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/NetworkMonitoring.swift の
 * `StubNetworkMonitor` のミラー。`start`/`stop` は実際の監視を行わず呼び出し回数のみ記録する
 * （テストから ConnectivityManager 相当の副作用なしに呼び出し検証できるようにするため）。
 */
class StubNetworkMonitor(
    initialIsOnline: Boolean = true,
) : NetworkMonitoring {
    private val mutableIsOnline = MutableStateFlow(initialIsOnline)
    override val isOnline: StateFlow<Boolean> = mutableIsOnline.asStateFlow()

    /** [start] が呼ばれた回数。 */
    var startCallCount = 0
        private set

    /** [stop] が呼ばれた回数。 */
    var stopCallCount = 0
        private set

    override fun start() {
        startCallCount++
    }

    override fun stop() {
        stopCallCount++
    }

    /** テストから接続状態を直接変更する。 */
    fun setOnline(value: Boolean) {
        mutableIsOnline.value = value
    }
}
