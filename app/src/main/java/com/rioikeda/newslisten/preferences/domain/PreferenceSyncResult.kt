package com.rioikeda.newslisten.preferences.domain

import com.rioikeda.newslisten.core.ApiException

/** 設定の変更要求の結果。通信の失敗は variant を潰さず [ApiException] のまま運ぶ。 */
sealed interface PreferenceSyncResult {
    data object Applied : PreferenceSyncResult

    /** 後から来た操作、または主体の交代で、この呼出の結果は採られなかった。 */
    data object Superseded : PreferenceSyncResult

    data class Rejected(val rejected: PreferenceRejected) : PreferenceSyncResult

    data class Failed(val cause: ApiException) : PreferenceSyncResult

    data object SaveFailed : PreferenceSyncResult
}

/** 値域検査で拒否された項目。 */
data class PreferenceRejected(val item: PreferenceItem)
