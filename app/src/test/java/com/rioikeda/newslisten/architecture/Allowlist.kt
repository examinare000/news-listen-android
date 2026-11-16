package com.rioikeda.newslisten.architecture

/**
 * 許可リストの 1 組。`(規則 ID, ファイル, 対象)` が違反の単位で、`owner` は消す slice の ID（記録。合否には効かない）。
 * 同じ組に当たる行が何行あっても 1 組と数える（行の増加は検出しない。限界）。
 */
data class AllowEntry(val rule: String, val file: String, val target: String, val owner: String) {
    val key: Triple<String, String, String> get() = Triple(rule, file, target)
}

/**
 * 今の違反の固定。空（android-dec-133）。以後の違反は 0 から増やせない。
 * 走査の出力から生成しない。差が出たら、走査と仕様のどちらが誤りかを先に決める。
 *
 * android-check-08 のファイルは test の根からの相対 path。
 */
object Allowlist {

    val entries: List<AllowEntry> = emptyList()
}
