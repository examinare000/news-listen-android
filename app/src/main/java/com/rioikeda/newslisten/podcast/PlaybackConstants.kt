package com.rioikeda.newslisten.podcast

/**
 * 再生操作の共有定数。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PlaybackConstants.swift:15-18。
 * プレイヤー UI とロック画面/コントロールセンター相当の実装（フェーズ7 MediaSessionService）で
 * 同じ値を使い、片方だけ変更して食い違う回帰を防ぐ狙いを踏襲する。
 */
object PlaybackConstants {
    /** 速度選択肢（倍率）。8段階。 */
    val speeds: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5)

    /**
     * [speeds] に含まれる値だけを選択可能とする。許容誤差は持たず、NaN は常に false。
     * 値域の判定はここに集約し、呼び出し側で [speeds] を直接検索しない。
     */
    fun isSelectableSpeed(value: Double): Boolean = value in speeds

    /** 戻るスキップ秒。 */
    const val SKIP_BACKWARD_SECONDS: Double = 15.0

    /** 進むスキップ秒。 */
    const val SKIP_FORWARD_SECONDS: Double = 30.0
}
