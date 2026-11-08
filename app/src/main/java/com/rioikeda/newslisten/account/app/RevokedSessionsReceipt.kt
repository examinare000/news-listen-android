package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.core.ApiException

/** 他のセッションの一括失効の結果。 */
sealed interface RevokedSessionsReceipt {
    class Revoked(val revokedCount: Int) : RevokedSessionsReceipt

    class Failed(val failure: ApiException) : RevokedSessionsReceipt
}
