package com.rioikeda.newslisten.architecture

/**
 * main の全 `.kt` を層へ対応づける規則（Spec §3.2・§7 android-check-01。android-dec-130）。
 *
 * 層は、package の規則（[layerOf]）・`app/` の種別表（[portFiles]・[readModelFiles]）・入口 4 つの exact path で決める。
 * 規則に当たらないファイルは `null` で、`ArchitectureStructureTest` が 0 件であることを検査する。
 * `*ViewModel` は `ui/` に置き、presentation になる（android-dec-09）。
 *
 * 限界:
 * - 同じ package の参照は import に現れないので、依存の向きの検査では見えない。
 * - 型の情報を持たないので、名前の形でしか判定できない検査（port の受け取り・遷移関数の呼出）がある。
 *
 * パスは main の根（`app/src/main/java/com/rioikeda/newslisten/`）からの相対で、`/` 区切り。
 */
enum class Layer {
    DOMAIN,
    APPLICATION,
    PORT,
    READ_MODEL,
    ADAPTER,
    PRESENTATION,
    ENTRY,
    COMPOSITION_ROOT,
}

/** 型単位の行。ファイルの分類は変えず、その型だけ別の層として扱う。 */
data class TypeRow(val file: String, val type: String, val layer: Layer, val owner: String)

/** 恒久の例外（許可リストと別。使われなくなったら失敗させる）。 */
data class PermanentException(val id: String, val rule: String, val file: String, val target: String)

object LayerMap {

    private val entryFiles = setOf(
        "MainActivity.kt",
        "NewsListenApplication.kt",
        "playbackservice/PlaybackService.kt",
        "notification/FcmTokenService.kt",
    )

    /** `app/` に置く port（android-dec-130。名前でなく表で決める）。 */
    val portFiles: Set<String> = setOf(
        "podcast/app/PlayerController.kt",
        "podcast/app/AudioStore.kt",
        "podcast/app/PositionStore.kt",
        "passkey/app/PasskeyProvider.kt",
        "preferences/app/PreferencesStore.kt",
        "preferences/app/PreferencesApi.kt",
        "auth/app/AuthApi.kt",
        "auth/app/SessionStore.kt",
        "account/app/AccountApi.kt",
        "learning/app/LearningApi.kt",
        "learning/app/VocabularyTestApi.kt",
        "feed/app/FeedApi.kt",
        "settings/app/SourcesApi.kt",
        "notification/app/DeviceTokenApi.kt",
        "notification/app/PushTokenSource.kt",
        "notification/app/NotificationPermission.kt",
        "podcast/app/PodcastApi.kt",
        "podcast/app/NetworkMonitoring.kt",
    )

    /** `app/` に置くリードモデル（android-dec-130）。 */
    val readModelFiles: Set<String> = setOf(
        "podcast/app/PlaybackReadModels.kt",
        "podcast/app/PlaybackNotice.kt",
        "catalog/app/EpisodeRow.kt",
        "preferences/app/PreferencesView.kt",
        "account/app/SessionRow.kt",
        "account/app/RevokedSessionsReceipt.kt",
        "passkey/app/PasskeyRow.kt",
        "passkey/app/PasskeyOutcomes.kt",
        "learning/app/LearningReadModels.kt",
        "feed/app/ArticleRow.kt",
        "feed/app/FeedNotice.kt",
        "settings/app/SourcesOutcomes.kt",
    )

    /** 上から順に最初に当たった層を返す。当たらなければ `null`（`*ViewModel.kt` の置き場の検査は `ArchitectureScanner.placementViolations`）。 */
    fun layerOf(path: String): Layer? {
        val segments = path.split('/')
        return when {
            path in entryFiles -> Layer.ENTRY
            path in portFiles -> Layer.PORT
            path in readModelFiles -> Layer.READ_MODEL
            path == "AppScaffold.kt" -> Layer.PRESENTATION
            segments[0] == "core" -> Layer.DOMAIN
            segments[0] in setOf("network", "observability", "model") -> Layer.ADAPTER
            segments[0] == "designsystem" -> Layer.PRESENTATION
            segments[0] == "di" -> Layer.COMPOSITION_ROOT
            segments.size >= 3 -> when (segments[1]) {
                "domain" -> Layer.DOMAIN
                "app" -> Layer.APPLICATION
                "infra" -> Layer.ADAPTER
                "ui" -> Layer.PRESENTATION
                else -> null
            }
            else -> null
        }
    }

    /** main の走査から導く。規則に当たらないファイルは入らない（その存在は `placementViolations` が報告する）。 */
    val byFile: Map<String, Layer> by lazy {
        ArchitectureScanner.readSources(ArchitectureScanner.resolveMainSourceRoot())
            .mapNotNull { source -> layerOf(source.path)?.let { source.path to it } }
            .toMap()
    }

    /** `NowPlaying` は android-slice-playback-03 が `PlaybackSession.kt` に足す。宣言が無い間は検査を skip する（CI-13）。 */
    val typeRows: List<TypeRow> = listOf(
        TypeRow("podcast/app/PlaybackReadModels.kt", "NowPlaying", Layer.READ_MODEL, "android-slice-playapp-02"),
    )

    val permanentExceptions: List<PermanentException> = listOf(
        // RF17: foreground service を起動するため entry の PlaybackService を import する（Spec §4 android-dep-05）。
        PermanentException("PE-1", "android-dep-05", "podcast/infra/ExoPlayerController.kt", "layer:ENTRY"),
        // PE-3 の生成に要る CredentialManagerPasskeyProvider の import（MainActivity.kt:35）。
        PermanentException("PE-2", "android-dep-04", "MainActivity.kt", "layer:ADAPTER"),
        // Activity の Context が要るので MainActivity で生成する（Spec §4 android-dep-06）。
        PermanentException("PE-3", "android-dep-06", "MainActivity.kt", "CredentialManagerPasskeyProvider"),
    )
}
