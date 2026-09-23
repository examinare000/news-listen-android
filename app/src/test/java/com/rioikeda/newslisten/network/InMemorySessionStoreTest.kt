package com.rioikeda.newslisten.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- save の戻り値（CI-T14, T-T14. order 手順3: save の失敗を呼出元へ返す） ---

    @Test
    fun store_ok_saveが成功したらtrueを返しloadで取得できる() {
        // verifies: CI-T14
        val store = InMemorySessionStore()

        val result = store.save("t")

        assertTrue(result)
        assertEquals("t", store.load())
    }

    @Test
    fun store_fail_saveFailsが真ならfalseを返しloadは変わらない() {
        // verifies: CI-T14
        val store = InMemorySessionStore(initialToken = "old", saveFails = true)

        val result = store.save("t")

        assertFalse(result)
        assertEquals("old", store.load())
    }
}
