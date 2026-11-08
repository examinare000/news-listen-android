package com.rioikeda.newslisten.auth.domain

/**
 * 端末ローカル資産の持ち主の id。形式 `[A-Za-z0-9_-]+` の全一致を満たすときだけ作れ、欠落・形式不正は `null`
 * （欠落と不正は区別しない。ADR-104 決定 16、android-rule-account-03）。形式の判断はこの [of] 1 箇所に置く。
 */
@JvmInline
value class SubjectId private constructor(val value: String) {
    companion object {
        private val VALID = Regex("[A-Za-z0-9_-]+")

        fun of(raw: String?): SubjectId? = raw?.takeIf { VALID.matches(it) }?.let(::SubjectId)
    }
}

/** 利用者の権限。未知の値は非 admin に倒す（android-rule-account-02）。 */
enum class Role {
    Admin,
    User,
    ;

    val isAdmin: Boolean get() = this == Admin

    companion object {
        fun of(raw: String): Role = if (raw == "admin") Admin else User
    }
}

/** 認証済みの利用者。DTO ではなく domain の語彙で、認証状態が保持する。 */
data class AccountUser(
    val username: String,
    val displayName: String,
    val role: Role,
    val subjectId: SubjectId?,
)
