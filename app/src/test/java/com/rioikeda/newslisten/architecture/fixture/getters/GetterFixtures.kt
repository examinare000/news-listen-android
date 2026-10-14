package com.rioikeda.newslisten.architecture.fixture.getters

// getter を 2 つ持つ所有者。getter の呼び方は、作者が書かなければ runner が既定（空の引数）を生成する。
class GetterOwner {
    val a: Int
        get() = 1

    val b: Int
        get() = 2
}
