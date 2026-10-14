package com.rioikeda.newslisten.architecture.fixture.implname

// 母集団ファイルに value class を置くと、JVM で `-impl` を名前に含む公開メソッドが現れる。
// これは除外せず、列挙の失敗にする。
@JvmInline
value class Money(val amount: Int)

class MoneyHolder {
    fun zero(): Int = 0
}
