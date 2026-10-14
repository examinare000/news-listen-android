package com.rioikeda.newslisten.architecture.fixture.summary

import com.rioikeda.newslisten.network.SessionStore

// 要約（到達先の節）の fixture: port を 1 つと WRITE の hook を 1 つ持つ所有者。
class SummaryOwner(
    private val sessions: SessionStore,
    private val onSaved: () -> Unit,
) {
    fun ping(): Int = 1
}
