package com.rioikeda.newslisten.architecture.fixture.catalog

// 分類表の完全性（未分類 0）と不動点（効果を持たない）を確かめる fixture。
// private な companion の field・private な最上位の data class の static field・入れ子の enum の合成 field
// （`$ENTRIES`・`$VALUES`）が、型の分類に現れる。

class WithPrivateCompanion {
    private companion object {
        val label = "label"
    }

    fun ping(): Int = label.length + HiddenPair.ZERO.a
}

private data class HiddenPair(val a: Int, val b: Int) {
    companion object {
        val ZERO = HiddenPair(0, 0)
    }
}

class HolderOfEnum {
    enum class Kind { A, B }

    fun ping(): Int = Kind.entries.size
}
