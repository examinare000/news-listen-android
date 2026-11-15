package com.rioikeda.newslisten.learning.domain

/**
 * 語彙の同一性（android-rule-learning-07）。エピソード id と、前後の空白を除いて小文字にした語で決まる。
 * 構築を [of] だけにして、画面ごとに別の正規化が生まれないようにする。
 */
class VocabularyKey private constructor(val episodeId: String, val term: String) {
    override fun equals(other: Any?): Boolean =
        other is VocabularyKey && episodeId == other.episodeId && term == other.term

    override fun hashCode(): Int = 31 * episodeId.hashCode() + term.hashCode()

    override fun toString(): String = "VocabularyKey($episodeId, $term)"

    companion object {
        fun of(episodeId: String, term: String): VocabularyKey = VocabularyKey(episodeId, term.trim().lowercase())
    }
}
