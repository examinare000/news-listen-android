package com.rioikeda.newslisten.catalog.app

enum class EpisodeStatusKind { Playable, Generating, Failed }

/**
 * 一覧の 1 行（画面が読むリードモデル）。`Episode` の状態は型で写す（android-check-04）。
 * 再生中かどうかは持たない: Playback の状態は Catalog が読まず、presentation が突き合わせる。
 */
data class EpisodeRow(
    val episodeId: String,
    val displayTitle: String,
    val difficultyCode: String,
    val durationSeconds: Int,
    val createdAt: String,
    val status: EpisodeStatusKind,
    val canDownload: Boolean,
)
