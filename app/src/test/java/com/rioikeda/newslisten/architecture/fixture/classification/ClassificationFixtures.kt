package com.rioikeda.newslisten.architecture.fixture.classification

import android.content.Context
import com.rioikeda.newslisten.network.SessionStore

/** port の field を持つので効果を持ちうる型。その getter を STRUCTURAL にしてはならない。 */
class PortHolder(private val sessions: SessionStore) {
    val label: String
        get() = "label"
}

/** `Context`（PLATFORM）を引数に取る操作は STRUCTURAL にできない。 */
class ContextTaker {
    fun open(context: Context?) {}
}

/** 効果を持たない値。getter と純粋な関数は STRUCTURAL で通る。getter を COMMAND にしてはならない。 */
class PureValue {
    val value: Int
        get() = 1

    fun pure(x: Int): Int = x
}
