package com.rioikeda.newslisten.architecture.fixture.enumeration

import androidx.compose.runtime.Composer
import com.rioikeda.newslisten.architecture.fixture.keys.UserKey

/** data class: `componentN`・`copy`・`equals`・`hashCode`・`toString`・`move$default` は公開操作から除かれ、`getX` などは残る。 */
data class Point(val x: Int, val y: Int) {
    fun move(dx: Int = 0): Point = copy(x = x + dx)
}

/** enum: `values`・`valueOf`・`getEntries` は公開操作から除かれる。 */
enum class Mode { FAST, SLOW }

class Outer {
    var x: Int = 1

    class Inner {
        fun hello(): String = "hello"
    }

    companion object {
        fun make(): Outer = Outer()
    }

    // JVM では名前に `$` が付く（internal の名前修飾）。公開操作から除かれる。
    internal fun internalMember(): Int = 1

    suspend fun fetch(id: String): Int = id.length

    // value class を引数に取るので、JVM 名は `find-<hash>` になる。ID は `-` の後ろを落とした `Outer#find`。
    fun find(id: UserKey): Int = id.value
}

fun topLevel(name: String): String = name

/** Composable と同じ形（Composer を引数に取る static）。公開操作から除かれる。 */
fun composableLike(composer: Composer, changed: Int) {}
