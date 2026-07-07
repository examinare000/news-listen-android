package com.rioikeda.newslisten.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [InMemorySessionStore] の契約検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/SessionStore.swift:19-22
 * （InMemorySessionStore）のミラー。Keystore に触れず状態遷移のみを検証する。
 */
class InMemorySessionStoreTest {

    @Test
    fun 何も保存していない状態ではloadがnullを返す() {
        val store = InMemorySessionStore()

        assertNull(store.load())
    }

    @Test
    fun saveした値がloadで取得できる() {
        val store = InMemorySessionStore()

        store.save("token-abc")

        assertEquals("token-abc", store.load())
    }

    @Test
    fun saveを2回呼ぶと最新の値で上書きされる() {
        val store = InMemorySessionStore()

        store.save("token-old")
        store.save("token-new")

        assertEquals("token-new", store.load())
    }

    @Test
    fun clear後はloadがnullを返す() {
        val store = InMemorySessionStore()
        store.save("token-abc")

        store.clear()

        assertNull(store.load())
    }
}
