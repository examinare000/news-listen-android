package com.rioikeda.newslisten.core

/**
 * 再生速度の値域の正本。生成は [of] だけで、値域外は null を返す。
 * 許容誤差は持たず、NaN は常に null。
 */
@JvmInline
value class PlaybackSpeed private constructor(val multiplier: Double) {
    companion object {
        val all: List<PlaybackSpeed> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5).map(::PlaybackSpeed)

        val DEFAULT: PlaybackSpeed = PlaybackSpeed(1.0)

        fun of(raw: Double): PlaybackSpeed? = all.firstOrNull { it.multiplier == raw }
    }
}
