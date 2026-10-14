package com.rioikeda.newslisten.architecture

/**
 * 検査器が返す失敗の 1 件。`heading` は別の見出しで列挙するための分類、`subject` は名指しする対象
 * （操作 ID・field 名・型名など）、`detail` は原因と経路。
 */
data class Finding(val heading: String, val subject: String, val detail: String = "") {
    override fun toString(): String = "[$heading] $subject" + if (detail.isEmpty()) "" else " — $detail"
}

/** 失敗の見出し。検査器の実装とテストが同じ文字列を指すための定数。 */
object Headings {
    // 列挙
    const val ENUMERATION = "列挙の失敗"

    // 台帳と列挙の照合
    const val MISSING_ROW = "台帳に無い操作"
    const val STALE_ROW = "列挙に無い台帳の行"
    const val AMBIGUOUS_ROW = "2 行に一致する操作"
    const val SIGNATURE_MISMATCH = "signature の不一致"
    const val MISSING_SIGNATURE = "$SIGNATURE_MISMATCH（足りない signature）"
    const val EXTRA_SIGNATURE = "$SIGNATURE_MISMATCH（余分な signature）"
    const val DUPLICATE_ROW = "重複した台帳の行"
    const val SPLIT_INTERFACE_IMPLEMENTATION = "interface と実装が別の行"

    // 分類の整合
    const val CLASSIFICATION = "分類の不整合"

    // 分類表の完全性
    const val UNCLASSIFIED_TYPE = "未分類の型"
    const val UNCLASSIFIED_PORT_OPERATION = "未分類の port の操作"
    const val UNREGISTERED_HOOK = "未登録の hook"
    const val SURPLUS_CATALOG_ROW = "余分な行"

    // probe の網羅
    const val MISSING_CALL = "欠けた呼び方"
    const val SURPLUS_PROBE = "余分な probe"
    const val OMITTED_SIGNATURE = "signature を省けない呼び方"
    const val OVERLAPPING_CALL = "重なった呼び方"
    const val MISSING_SCENARIO = "欠けた scenario"

    // 配線の検査（規則の見出し）
    const val WIRING_CLASS_MISMATCH = "分類の不一致"
    const val WIRING_OUTSIDE_FACTORY = "工場の外の関数"
    const val WIRING_NOT_RECORDING_FAKE = "記録 fake でない実物"
    const val WIRING_CONTAINER = "型引数に効果を持ちうる型を含む容器"
    const val WIRING_NULL_EFFECTFUL = "効果を持ちうる型の field が null"
    const val WIRING_FOREIGN_SCHEDULER = "別の scheduler の dispatcher"
    const val WIRING_PLATFORM = "PLATFORM の field"
    const val UNASSIGNABLE_SETTER = "代入できない SETTER の行"

    // probe の実行
    const val DEADLINE_INCOMPLETE = "期限までに完了しない"
    const val PROBE_FAILURE = "探査の失敗"
    const val BACKGROUND_KEYS = "背景の Job の鍵の不一致"
    const val DEADLINE_CLOCK = "期限の時刻のずれ"
    const val LEFTOVER_JOB = "cancel 後も active な Job"

    // 移行違反の表との一致
    const val UNLISTED_WRITE = "表に無い書込"
    const val STALE_VIOLATION = "書込の無い表の行（縮め忘れ）"
    const val BACKGROUND_CHANGE = "背景処理の変化"
    const val INVALID_OWNER = "owner 不正"
    const val DUPLICATE_VIOLATION = "重複した表の行"
    const val INVALID_ROW = "表の行の欄が不正"
    const val KNOWN_NOT_IN_TABLE = "既知の違反が表に無い"

    // 既知の違反の観測
    const val PROBE_DEFECT = "probe の欠陥"
}
