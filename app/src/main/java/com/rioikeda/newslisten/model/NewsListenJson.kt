package com.rioikeda.newslisten.model

import kotlinx.serialization.json.Json

/**
 * バックエンド API のレスポンス/リクエストをデコード・エンコードする共有 Json 設定。
 *
 * WHY: backend/api/schemas.py は未知フィールド追加をクライアントが無視する前提の
 * 前方互換設計（例: ADR-059 の PodcastResponse.segments）を採っている。
 * kotlinx.serialization の既定（未知キーで例外）のままではこの前提が崩れるため、
 * ignoreUnknownKeys を全 DTO 共通で有効にする。coerceInputValues は型不一致の
 * プリミティブ値をデフォルトへ丸めることで、サーバー側の値追加・変更時にも
 * デコード自体は継続できるようにする。
 */
val NewsListenJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
