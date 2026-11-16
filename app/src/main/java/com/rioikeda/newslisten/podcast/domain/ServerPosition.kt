package com.rioikeda.newslisten.podcast.domain

import java.time.Instant

/** サーバーが持つ位置。[recordedAt] が null は時刻の無い値（最も古い）。 */
data class ServerPosition(val seconds: Double, val recordedAt: Instant?)
