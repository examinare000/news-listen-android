package com.rioikeda.newslisten.passkey.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.passkey.PasskeyProviderException

/** パスキー登録の結果。文言への写しは presentation が持つ。 */
sealed interface PasskeyRegisterOutcome {
    object Registered : PasskeyRegisterOutcome

    object Cancelled : PasskeyRegisterOutcome

    object Conflict : PasskeyRegisterOutcome

    class Failed(val cause: ApiException) : PasskeyRegisterOutcome

    class ProviderFailed(val cause: PasskeyProviderException) : PasskeyRegisterOutcome
}

/** パスキーログインの結果。文言への写しは presentation が持つ。 */
sealed interface PasskeyLoginOutcome {
    object LoggedIn : PasskeyLoginOutcome

    object Cancelled : PasskeyLoginOutcome

    class Failed(val cause: ApiException) : PasskeyLoginOutcome

    class ProviderFailed(val cause: PasskeyProviderException) : PasskeyLoginOutcome
}
