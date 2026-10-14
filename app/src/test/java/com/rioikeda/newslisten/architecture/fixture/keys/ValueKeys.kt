package com.rioikeda.newslisten.architecture.fixture.keys

// WHY: value class は JVM で `-impl` 付きの公開メソッドを持つので、母集団の外（別 package）に置く。
// 引数にだけ使い、JVM では中身の Int と同じ引数の型になる。

@JvmInline
value class UserKey(val value: Int)

@JvmInline
value class OrderKey(val value: Int)
