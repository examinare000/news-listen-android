## android リファクタ A-T1: 層の対応表と構造の検査（依存の向き・データモデルの漏れ・composition root・規則の置き場・公開面）を入れ、今の違反を許可リストに固定する

## 1. 目的と、応える要求・設計・契約の ID

main の全 `.kt` を「ファイル → 層」の対応表で分類し、依存の向き・DTO の漏れ・composition root・規則の置き場・公開面を JVM の unit テストで検査する。今の違反は全件を許可リストに固定し、以後の slice が減らし、A-T9 で空にする（ADR-110 決定 10）。**main は変えない（test だけ）**。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (5)（依存の向きに反する import を CI が落とす）・NFR-10（カプセル化） | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-5・AQ-6・AQ-7（AQ-1〜AQ-3 の検査の枠） | `docs/design/architecture.md` §2・§8 |
| 決定 | ADR-110 決定 1・7・10 | `docs/adr/110-refactor-target-domain-centered-onion-cqrs.md` |
| Spec | 層と path の対応表 §3.2、依存の規則 TA-D1〜TA-D9 §4、検査 TA-V1〜TA-V5・TA-V6（`PlaybackQueue`）・TA-V8・TA-V9 §7、slice §8.4「A-T1」、trace §9 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T16（`error("` = 0）、A-S0 の到達点（`code == 401` = 2） | `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §4、本フォルダ README「grep oracle」 |
| 既存のテスト | `test/…/network/NoThrowingDefaultStructureTest.kt`（走査の方式の手本） | android `app/src/test/java/com/rioikeda/newslisten/network/` |

## 2. 前提（着手条件）

- 依存する slice: **なし**（A-S2a 完了時点の main `7e400f0b` で足りる）。親リポ `news-listen` の submodule ポインタが android main と一致している（親で `git submodule status` を実行し `android` 行に `+` が無い）。
- baseline: `JAVA_HOME=<Android Studio 同梱の JBR> ./gradlew clean testDebugUnitTest --console=plain` が exit 0（`@Test` 599 件。2026-09-30 実測）。
- 判断待ち: 無い（適用 slice）。
- `docs/trial-log/`（android・親）を最初に読む。

## 3. 着手前の前提点検（投入の直前に `android/` で数え直す。値が違えば order の許可リストの初期値を直してから投入する）

Spec §4 の実測（2026-09-30。2026-10-01 に main `7e400f0b` で再測して一致）を、そのまま許可リストの初期値にする。

| 検査 | コマンド | 2026-10-01 の値 |
|---|---|---|
| main の規模 | `find app/src/main/java/com/rioikeda/newslisten -name '*.kt' \| wc -l`／`cat $(find … -name '*.kt') \| wc -l` | 121 ファイル・13,074 行 |
| test の規模 | `find app/src/test -name '*.kt' \| wc -l`／`grep -r '@Test' app/src/test --include='*.kt' \| wc -l` | 87 ファイル・599 件 |
| TA-D1 | `grep -nE '^import (android|androidx|kotlinx\.coroutines|kotlinx\.serialization|okhttp3|com\.rioikeda\.newslisten\.(model|network|di))' <domain の各ファイル>`（集合 = `core/*.kt`・`podcast/PlaybackSession.kt`・`PlaybackState.kt`・`PlaybackConstants.kt`・`auth/AuthState.kt`・`preferences/ArticleOpenMode.kt`・`TimeFormat.kt`・`feed/ArticleUrlValidator.kt`） | 3 件: `podcast/PlaybackSession.kt:3`（`model.PodcastResponse`）・`auth/AuthState.kt:3`（`model.UserResponse`）・`podcast/PlaybackState.kt:3`（`androidx.media3.common.PlaybackException`） |
| TA-D2（import） | `grep -rE '^import com\.rioikeda\.newslisten\.model\.' app/src/main/java/com/rioikeda/newslisten --include='*.kt' \| grep -vE '/(network|model|observability)/'` | 27 ファイル・43 行（`observability/` を含めると 29 ファイル・46 行） |
| TA-D2（完全修飾名） | `grep -n 'com\.rioikeda\.newslisten\.model\.' app/src/main/java/com/rioikeda/newslisten/learning/LearningScreen.kt \| grep -v import` | 5 箇所（`:233,234,323,364,365`） |
| TA-D2（`model/` の側） | `grep -rnE '^import (com\.rioikeda\.newslisten|android)' app/src/main/java/com/rioikeda/newslisten/model/` | 3 行: `model/PodcastResponse.kt:3`（`core.QueueItem`）・`model/FeaturedCategory.kt:3-4`（`androidx.annotation.StringRes`・`R`） |
| TA-D3 | `grep -rE '^import com\.rioikeda\.newslisten\.network\.' app/src/main/java/com/rioikeda/newslisten --include='*.kt' \| grep -vE '/(network|di|observability)/'` | 15 ファイル・34 行（application 14 ファイル・33 行 ＋ presentation `podcast/QuizSheet.kt:46` の 1 行。TA-D4 の分） |
| TA-D3（port の戻り値が DTO） | `network/PodcastApi.kt`（3 操作）・`LearningApi.kt`（3）・`VocabularyTestApi.kt`（2） | 8 操作 |
| TA-D4 | `grep -rln 'PreferencesStore' app/src/main/java/com/rioikeda/newslisten \| grep -E 'MainActivity|AppScaffold|DSFeedback|SettingsScreen'`／`grep -nE 'preferencesStore\.set' app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt` | 4 ファイル／setter の呼出 4 箇所（`:245,259,271,278`） |
| TA-D5 | adapter（`network/**`・`observability/**`・`podcast/ExoPlayerController.kt`・`preferences/DataStorePreferencesStore.kt`）の import に presentation・application の実装 class が無い | 0 件。明示の例外 1: `podcast/ExoPlayerController.kt` が `playbackservice.PlaybackService` を import（RF17。現状維持） |
| TA-D6 | `grep -rnE '(OkHttpApiClient|KeystoreSessionStore|DataStorePreferencesStore|ExoPlayerController|AudioCacheManager|ConnectivityNetworkMonitor|JavaFileSystem|CredentialManagerPasskeyProvider)\(' app/src/main … \| grep -v 'class \|fun \|import'`／`grep -rn 'getAppContainer()' … \| grep -v 'fun '`／`grep -n '== "' app/src/main/java/com/rioikeda/newslisten/di/AppContainer.kt` | 生成は `di/AppContainer.kt` の 7 箇所（`:69,97,111,112,120,215,328`）＋明示の例外 `MainActivity.kt:101`（`CredentialManagerPasskeyProvider`）。`getAppContainer()` は `MainActivity.kt:65,84`・`FcmTokenService.kt:45`・`PlaybackService.kt:37` の 4 箇所。`== "` は 1（`:416` `role == "admin"`） |
| TA-D7 | `grep -rnE '^\s*var ' app/src/main … \| grep -vE 'private|internal|protected|remember|mutableStateOf'`（Compose の `remember` と関数内のローカル `var` は除く） | 公開の `var` 3 件: `feed/FeedViewModel.kt:43`・`engagement/ListeningStreakStore.kt:38`・`podcast/PlayerController.kt:45`（実装 `ExoPlayerController.kt:71`）。公開の `MutableStateFlow` 0 |
| TA-D9 | `grep -rnE 'e\.code' app/src/main … \| grep -v '/network/'`／`grep -rn '= e\.message' app/src/main … \| grep -v '/network/'` | `code` の比較 6 箇所（`account/AccountViewModel.kt:122`・`settings/SettingsViewModel.kt:160`・`engagement/ListeningStreakStore.kt:56`・`onboarding/OnboardingViewModel.kt:86`・`passkey/PasskeyRegistrationViewModel.kt:54`・`podcast/QuizSheet.kt:195`）。`= e.message` 8 箇所（`feed/FeedViewModel.kt:89,106,204`・`podcast/PodcastViewModel.kt:137,214,216,253,311`） |
| TA-V4 の式 | `grep -rnF '<式>' app/src/main …` を式ごとに | `"processing"` 1・`"failed"` 1・`"partial_failed"` 1（すべて `podcast/PodcastStatusBadge.kt:25-26`）・`"completed"` 0・`PLAYBACK_SPEEDS` 7（`settings/SettingsScreen.kt`）・`listOf(3, 5, 7, 10)` 1（`SettingsScreen.kt:1362`）・`setOf(3, 5, 7, 10)` 1（`SettingsViewModel.kt:237`）・`/ 7.0` 1（`SettingsScreen.kt:305`）・`/ 31.0` 1（`learning/LearningScreen.kt:425`）・`correctRate >=` 1（`podcast/QuizSheet.kt:50`）・`limit == 0` 1（`SettingsScreen.kt:511`）・`moveUpNext(index` 2（`podcast/QueueSheet.kt:137,142`）・`role ==` 2（`AppScaffold.kt:115`・`di/AppContainer.kt:416`） |
| TA-V8 | `grep -rln 'okhttp3.mockwebserver' app/src/test` | `network/OkHttpApiClientTest.kt`・`network/OkHttpApiClientLearningTest.kt` の 2 ファイル（`network/` の外は 0） |
| TA-V9 | `grep -rn 'error("' app/src/main \| wc -l`／`grep -rn 'code == 401' app/src/main \| wc -l` | 0／2（`network/AuthInterceptor.kt:48`・`network/OkHttpApiClient.kt:459`） |
| test double が main に居る | `grep -rn 'InMemoryPreferencesStore\|InMemorySessionStore' app/src/main \| grep -v 'class InMemory'` | main からの参照 0（KDoc の相互参照 2 行だけ） |

## 4. 対象の path と対象外

**作る（test だけ）**: `app/src/test/java/com/rioikeda/newslisten/architecture/ArchitectureStructureTest.kt`・`LayerMap.kt`・`Allowlist.kt`（新規 3 ファイル）と、`app/src/test/java/com/rioikeda/newslisten/core/PlaybackQueueEncapsulationTest.kt`（新規）。

**対象外**: main の全ファイル（1 行も変えない）。違反を直すこと。`detekt`・`ktlint`・`konsist`・`archunit` の導入（ADR-066・SG-R11）。`build.gradle.kts`・`ci.yml`（構造テストは `testDebugUnitTest` に入るので CI の変更は要らない。A-S3 と重ならない）。既存テストの変更。

## 5. 変更の責務（何をどこへ置くか。コードは書かない）

| 置くもの | 内容 |
|---|---|
| `LayerMap.kt`（対応表） | Spec §3.2 の「現状の置き場 → 層（移行中の判定）」を、ファイルの相対 path（`app/src/main/java/com/rioikeda/newslisten/` から）→ 層（`domain` / `application` / `port` / `readModel` / `adapter` / `presentation` / `entry` / `compositionRoot` / `testDouble`）の表として持つ。**main の 121 ファイル全部**が載る（対応表に無いファイルが 0）。`podcast/PlaybackSession.kt` の中の `NowPlaying`（A-S2b2 が足す）を「リードモデル（移行中は domain のファイルに同居）」として登録する行を、A-S2b2 が来る前に置いておく（Spec §8.3 A-S2b2 補正 14。ファイル単位の分類は domain のまま） |
| `Allowlist.kt`（許可リスト） | 違反を「ファイル → 禁止された package（または式・宣言）」の組で列挙する。§3 の表の全件を初期値にする。1 組ごとに、持ち主（減らす slice の ID）を書く（Spec §8.4 の各 slice の「green にする」から写す） |
| `ArchitectureStructureTest.kt` | `NoThrowingDefaultStructureTest` と同じ方式（main の `.kt` を走査し、`package`・`import`・宣言の行を読む。走査の根が見つからない・対象が 0 件なら失敗）。検査は TA-V1（TA-D1・D3・D4・D5）・TA-V2（TA-D2 と port の宣言行）・TA-V3（TA-D6）・TA-V4（式の列挙）・TA-V5（公開の `var`・`MutableStateFlow`・`data class` の `val` と mutable な型・構築子の公開）・TA-V7 の静的な枠（`*Queries` / `*Commands` の interface。A-T3a までは対象 0 件で、「対象 0 件のときに落とす」規則は TA-V7 だけ適用しない＝対象が現れるまで skip と明示する）・TA-V8（test の走査）・TA-V9（`error("` = 0・`code == 401` = 2 の自動化）。判定は「許可リストに無い違反が 0」**かつ**「許可リストにあるのに違反が無い組が 0」（縮め忘れを落とす） |
| `PlaybackQueueEncapsulationTest.kt` | TA-V6 の `PlaybackQueue`: ①`setQueue` に渡した list を渡した後で書き換える ②返した `items`・`upNext` を `MutableList` に cast して書き換える。どちらの後も次に読んだ値と不変条件が変わらない |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| 許可リスト（§3 の実測の全件） | 各組に書いた slice | A-T1 | 各 slice が「green にする」で消す。最後に空 | A-T9 |
| 対応表（ファイル単位の分類） | A-T1 | A-T1 | package の規則（`**/domain/**` など）に置き換える | A-T9 |
| TA-V7 の静的な検査が対象 0 件 | A-T1 | A-T1 | `PlaybackCommands` / `PlaybackQueries` が現れる | A-T3a1 |

許可リストの増減: 本 slice で「増える」（0 → §3 の全件）。以後の slice は増やさない（各 order の完了条件）。例外は A-S4a の `OfflineLibrary` → `AudioCacheManager` の 1 件（A-S4a の order。削除は A-T3a1）。

## 7. 変わる挙動

無い（test だけ）。

## 8. 契約と検査

green にする: TA-V1・TA-V2・TA-V3・TA-V4・TA-V5・TA-V8・TA-V9、TA-V6 のうち `PlaybackQueue`。テスト名に `TA-V1`〜`TA-V9` の ID を含める（例: `TA_V1_依存の向きが許可リストの外で0件`）。`PlaybackQueueEncapsulationTest` のテスト名に `TA-V6` と `AQ-6` を含める。

`PlaybackQueue` の実行時のテストが現行で落ちる場合（`setQueue` に渡した list や、返した `items` を書き換えると中身が変わる）は、直すのは A-S2b1（`init` と `setQueue` を触る slice）とし、本 slice では該当のテストに「A-S2b1 で green にする」の印（`@Ignore` と理由のコメント）を付けて登録する。main は変えない。

## 9. 受入とテストのコマンド

- `JAVA_HOME=<Android Studio 同梱の JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0（`android/` で実行）。
- 正の対照: `ArchitectureStructureTest` の全テストが green で、許可リストの件数 = §3 の表の合計。
- 負の対照（PR の説明に記録する。コードは元に戻す）: (a) 対応表に無いファイル（例: `app/src/main/java/com/rioikeda/newslisten/probe/Probe.kt`）を足すと TA-V1 が落ちる。(b) 許可リストの 1 件（例: `podcast/PlaybackSession.kt → model`）を消すと「許可リストにあるのに違反が無い」ではなく「許可リストに無い違反」で落ちる。(c) 許可リストに架空の組を 1 件足すと「許可リストにあるのに違反が無い組」で落ちる。
- `grep -rn 'error("' app/src/main | wc -l` = 0、`grep -rn 'code == 401' app/src/main | wc -l` = 2（TA-V9 が同じ判定を自動化する）。

## 10. 完了条件

- 全 unit テスト green。main の差分が 0 行（`git diff --stat main -- app/src/main` が空）。
- `LayerMap` に main の 121 ファイル全部が載る（対応表に無いファイル 0。集合 = `app/src/main/java/com/rioikeda/newslisten/**/*.kt`）。
- 許可リストの初期値が §3 の表と一致し、各組に持ち主の slice ID がある（集合 = `Allowlist.kt` の全行）。
- TA-V1〜V5・V8・V9 のテストがあり、テスト名に ID を含む。TA-V6（`PlaybackQueue`）のテストがある（green か、「A-S2b1 で green にする」の印つき）。
- 負の対照 (a)(b)(c) の結果が PR の説明にある。
- `detekt`・`ktlint`・`konsist`・`archunit` の依存が `build.gradle.kts` に無い。
- TA-V10（PR の説明）: 「依存の向きに反する import を書いたら」の問いに、(a) の結果を答える。

相互矛盾の突き合わせ: 対象は test だけ／対象外は main／完了条件は main の差分 0 — 矛盾なし。TA-V6 が落ちる場合の扱い（印を付けて登録）は「main を変えない」と両立する。

## 11. 決定 slice か適用 slice か

適用 slice。判断待ちに依存しない。

## 12. 規模の目安と返却事項

- 規模 ≈ 400 行（test だけ。Spec §8.4 の見込み。測っていない）。1 PR。
- 返却事項: 許可リストの初期値（件数と組）を親 docs `research-reports` と Spec §4 の「現状の違反」に返す（違えば Spec を直す）。TA-V6 の `PlaybackQueue` が現行で落ちた場合は、その旨を A-S2b1 の order の baseline に返す。TA-V7 の静的な検査が対象 0 件で skip している旨を A-T3a1 の order に返す。
