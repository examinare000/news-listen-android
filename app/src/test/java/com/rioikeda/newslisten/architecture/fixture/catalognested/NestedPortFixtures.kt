package com.rioikeda.newslisten.architecture.fixture.catalognested

import com.rioikeda.newslisten.network.SessionStore

// port の field を持つ private な入れ子の class。外側の型は、その class を field に持つので効果を持ちうる型になる。
class NestedPortOuter {
    private class Inner(val sessions: SessionStore?)

    private val inner = Inner(null)

    fun ping(): Int = if (inner.sessions == null) 0 else 1
}
