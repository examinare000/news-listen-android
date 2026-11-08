package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.core.PlaybackSpeed

/**
 * 再生操作の共有定数。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PlaybackConstants.swift:15-18。
 * プレイヤー UI とロック画面/コントロールセンター相当の実装（フェーズ7 MediaSessionService）で
 * 同じ値を使い、片方だけ変更して食い違う回帰を防ぐ狙いを踏襲する。
 */
object PlaybackConstants {
    /** 速度選択肢（倍率）。値域の正本は [PlaybackSpeed] で、ここはそこから導く。 */
    val speeds: List<Double> = PlaybackSpeed.all.map { it.multiplier }

    /** 戻るスキップ秒。 */
    const val SKIP_BACKWARD_SECONDS: Double = 15.0

    /** 進むスキップ秒。 */
    const val SKIP_FORWARD_SECONDS: Double = 30.0
}
