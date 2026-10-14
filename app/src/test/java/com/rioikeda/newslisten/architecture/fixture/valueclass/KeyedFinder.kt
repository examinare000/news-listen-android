package com.rioikeda.newslisten.architecture.fixture.valueclass

import com.rioikeda.newslisten.architecture.fixture.keys.OrderKey
import com.rioikeda.newslisten.architecture.fixture.keys.UserKey

// 引数の value class だけが違う 2 つの overload。JVM では `find-<hash>(int)` が 2 つになり、
// 名前の接頭辞（`find`）も引数の型（`int`）も同じ `(操作 ID, signature)` になる。
class KeyedFinder {
    fun find(id: UserKey): Int = id.value

    fun find(id: OrderKey): Int = id.value
}
