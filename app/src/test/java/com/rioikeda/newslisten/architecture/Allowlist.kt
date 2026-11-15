package com.rioikeda.newslisten.architecture

/**
 * 許可リストの 1 組。`(規則 ID, ファイル, 対象)` が違反の単位で、`owner` は消す slice の ID（記録。合否には効かない）。
 * 同じ組に当たる行が何行あっても 1 組と数える（行の増加は検出しない。限界）。
 */
data class AllowEntry(val rule: String, val file: String, val target: String, val owner: String) {
    val key: Triple<String, String, String> get() = Triple(rule, file, target)
}

/**
 * 今の違反の固定（Spec §8.4 android-slice-skeleton-01。order §3.1 の 115 組）。以後の slice は、違反を消したら組を消す。
 * 走査の出力から生成しない。差が出たら、走査と仕様のどちらが誤りかを先に決める。
 * android-slice-skeleton-03 で、`*ViewModel` の presentation 化（android-dec-09）に合わせて 24 組を消し 43 組を足した。
 *
 * 持ち主の導出規則: その組を成す行をすべて消す slice のうち、§8.1 の順で最も後のもの。
 * android-check-08 のファイルは test の根からの相対 path。
 */
object Allowlist {

    private fun rule(ruleId: String, target: String, owner: String, vararg files: String) =
        files.map { AllowEntry(ruleId, it, target, owner) }

    val entries: List<AllowEntry> =
        // android-dep-03 (4): network 4
        rule("android-dep-03", "network", "android-slice-closure-01", "podcast/OfflineLibrary.kt") +
            rule("android-dep-03", "network", "android-slice-closure-01", "podcast/app/PlaybackCoordinator.kt") +
            rule("android-dep-03", "network", "android-slice-closure-01", "catalog/app/EpisodeCatalog.kt") +
            rule("android-dep-03", "network", "android-slice-closure-01", "auth/app/AuthSession.kt")
}
