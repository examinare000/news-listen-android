package com.rioikeda.newslisten.architecture

/** 移行違反 1 件。`rule` は常に `android-dep-08`。`owner`・`fixTask`・`removeBy` は [SliceIds.all] の要素。 */
data class MigrationViolation(
    val operationId: String,
    val port: String,
    val op: String,
    val path: String,
    val rule: String = RULE,
    val owner: String,
    val fixTask: String,
    val removeBy: String,
) {
    companion object {
        const val RULE = "android-dep-08"
    }
}

/** 既知の違反。目標 Spec §6 から来る組で、違反の表に含まれ、かつ観測されなければならない。 */
data class KnownViolation(
    val operationId: String,
    val port: String,
    val op: String,
    val source: String,
    val removeBy: String,
)

object QueryWriteViolations {
    /**
     * 観測と一致させる表。query が書く `(操作 ID, port, op)` を、owner の slice が消すまで固定する（両方向の一致で縮め忘れも落とす）。
     * 現在、移行違反は無い。新しい違反を許すときは owner の slice を付けて足し、その slice が直して消す。
     */
    val table: List<MigrationViolation> = emptyList()

    /** 観測より先に固定する既知の違反。現在は無い。消して通すことは禁止。 */
    val known: List<KnownViolation> = emptyList()
}
