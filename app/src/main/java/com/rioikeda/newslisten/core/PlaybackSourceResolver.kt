package com.rioikeda.newslisten.core

/**
 * 再生元の種別（docs/design/shared-playback-spec.md §6.1）。
 */
enum class PlaybackSource {
    CACHED,
    NETWORK,
    UNAVAILABLE,
}

/**
 * キャッシュを常に優先する。署名付き URL の期限切れ再取得を避けられ、
 * オフラインでも再生できるため（Web版 resolvePlaybackSource と同じ優先度）。
 */
fun resolvePlaybackSource(hasCached: Boolean, isOnline: Boolean): PlaybackSource {
    if (hasCached) {
        return PlaybackSource.CACHED
    }
    return if (isOnline) PlaybackSource.NETWORK else PlaybackSource.UNAVAILABLE
}
