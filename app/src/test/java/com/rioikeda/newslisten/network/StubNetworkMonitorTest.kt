package com.rioikeda.newslisten.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StubNetworkMonitor] の契約検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/NetworkMonitoring.swift の
 * StubNetworkMonitor のミラー。[NetworkMonitoring] インタフェースの初期値・状態遷移の契約を
 * Android 依存（ConnectivityManager）に触れずに固定する。
 */
class StubNetworkMonitorTest {

    @Test
    fun 初期値を指定しない場合はisOnlineがtrueである() {
        val monitor = StubNetworkMonitor()

        assertTrue(monitor.isOnline.value)
    }

    @Test
    fun 初期値をfalseで生成できる() {
        val monitor = StubNetworkMonitor(initialIsOnline = false)

        assertFalse(monitor.isOnline.value)
    }

    @Test
    fun setOnlineで状態を切り替えるとisOnlineに反映される() {
        val monitor = StubNetworkMonitor()

        monitor.setOnline(false)
        assertFalse(monitor.isOnline.value)

        monitor.setOnline(true)
        assertTrue(monitor.isOnline.value)
    }

    @Test
    fun startとstopの呼び出し回数を記録する() {
        val monitor = StubNetworkMonitor()

        monitor.start()
        monitor.start()
        monitor.stop()

        assertEquals(2, monitor.startCallCount)
        assertEquals(1, monitor.stopCallCount)
    }
}
