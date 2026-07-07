package com.rioikeda.newslisten.network

import kotlinx.coroutines.flow.StateFlow

/**
 * ネットワーク接続状態を提供する抽象。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/NetworkMonitoring.swift の
 * `NetworkMonitoring` プロトコルのミラー。iOS は `@Published var isOnline: Bool` で
 * 購読可能にし `init()` で監視を自動開始するが、Android では `Context` に依存する
 * リソース（`ConnectivityManager` のコールバック登録）の確保・解放を呼び出し側が
 * 明示的にライフサイクル管理できるよう、`isOnline` を [StateFlow] にし
 * `start()`/`stop()` を独立したメソッドとして持つ。
 */
interface NetworkMonitoring {
    /** ネットワークがオンラインかどうか（初期値: `true`）。 */
    val isOnline: StateFlow<Boolean>

    /** 監視を開始する。 */
    fun start()

    /** 監視を停止する。 */
    fun stop()
}
