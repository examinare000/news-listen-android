## android リファクタ A-T2a: `Episode`（domain）と判別、`EpisodeDecoder`・`PodcastApiAdapter` を新設する（既存の入口からは呼ばない）

## 1. 目的と、応える要求・設計・契約の ID

再生可能の判定（fail-closed）の正本を domain の `Episode` に置き、通信の DTO から domain へ写す decoder と、`ApiClient` を包む `PodcastApi` の adapter を**新規ファイルだけで**作る。既存のコードからは呼ばない（置き換えは A-T2b）。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、機能 F-POD-01 | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-1・AQ-3・AQ-6 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2・8、ADR-102（失敗の識別子）・ADR-108（`partial_failed` は再生不可）、SG-C64、導出 A-24・A-26 | `docs/adr/110-…md`・`102-…md`・`108-…md`、Spec §10.1 |
| 共有仕様 | PS-07・PS-07b（§4.4）、§6.6（fail-closed）、§6.8（同名異義） | `docs/design/shared-playback-spec.md` |
| Spec | TA-M-CT・TA-R-CT1・TA-R-CT2（§5.2）、`PodcastApi` の port（§5.1「port と adapter」）、TA-V5・TA-V6、§8.4「A-T2a」、§10.2 の 1・9 行目、§10.3 の 3（A-T2b で適用。確認済み） | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |

## 2. 前提（着手条件）

- A-T1・A-S2b1 の android PR が main に merge 済み、かつ親リポの submodule ポインタが進んでいる（親で `git submodule status` の `android` 行に `+` が無い）。A-S2b1 の後に置くのは、A-S2b1 の点検済みの行番号を保つため（Spec §8.2）。
- baseline: 全 unit テストが green、`ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に `android/` で数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| `catalog/` が無い | `ls app/src/main/java/com/rioikeda/newslisten/catalog` | 存在しない |
| DTO の field | `grep -n 'val ' app/src/main/java/com/rioikeda/newslisten/model/PodcastResponse.kt` | `id`・`type`・`articleIds`・`difficulty`・`audioUrl`・`japaneseIntroText`・`durationSeconds: Int`・`status`・`errorMessage: String?`・`playbackPositionSeconds: Double`・`title`・`segments`・`vocabulary`・`quiz`・`createdAt` の 15 |
| 内容の DTO | `grep -n 'data class' app/src/main/java/com/rioikeda/newslisten/model/{TranscriptSegment,VocabularyEntry,QuizQuestion}*.kt`（ファイル名は `grep -rln 'class TranscriptSegment\|class VocabularyEntry\|class QuizQuestion' app/src/main` で特定） | 3 型 |
| 現行の判別 | `grep -n '"processing"\|"failed"' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastStatusBadge.kt` | `:25`・`:26`（`status` だけで判定し、未知の値を再生可能に倒す） |
| 表示用の題名 | `grep -n 'displayTitle' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackMetadata.kt` | `:23-29` の 3 段（題名 → 日本語イントロ → 「ニュースポッドキャスト」） |
| `PodcastApi` の操作 | `grep -n 'suspend fun' app/src/main/java/com/rioikeda/newslisten/network/PodcastApi.kt` | 5 操作 |
| 許可リスト | `test/…/architecture/Allowlist.kt` の件数 | 記録する（増やさない） |

## 4. 対象の path と対象外

**作る（main）**: `catalog/domain/Episode.kt`（`Episode` と、内容の型・失敗の識別子）、`network/EpisodeDecoder.kt`、`network/PodcastApiAdapter.kt`。
**作る（test）**: `catalog/EpisodeTest.kt`（判別の表）、`network/EpisodeDecoderTest.kt`（decode）、`catalog/EpisodeEncapsulationTest.kt`（TA-V6）、`network/PodcastApiAdapterTest.kt`。
**変える（test）**: `architecture/LayerMap.kt` に新しい 3 ファイルを登録（`catalog/domain/Episode.kt` = domain、`network/*` = adapter）。

**対象外**: 既存の main のファイル（`PodcastStatusBadge.kt`・`PlaybackMetadata.kt`・`PodcastApi.kt`・`ApiClient.kt`・`PlaybackSession.kt`・`PodcastViewModel.kt`・`AppContainer.kt`・画面）は変えない。`ApiClient` の `PodcastApi` 継承をやめない（A-T2b）。`PodcastApi` の戻り値の型を変えない（A-T2b）。リードモデル（A-T3a）。

## 5. 変更の責務

| 層 | 置くもの | 名前（Spec のとおり） |
|---|---|---|
| domain | 判別共用体 | `Episode` = `Playable` / `Generating` / `Failed`。共通の値: `id`・`kind`（単体 / ダイジェスト）・`title`・`japaneseIntroText`・`difficultyCode`・`durationSeconds`・`createdAt`・`resumeHintSeconds`・`articleIds`・内容（`transcript`・`glossary`・`quiz`）、導出 `displayTitle`（TA-R-CT2）。`Playable` は `audioUrl`、`Failed` は失敗の識別子を持つ |
| domain | 生成の入口 1 つ | 検査つきの生成関数（構築子は外から呼べない。TA-V5）。判別の規則 TA-R-CT1: `completed` かつ音声 URL が空でなく失敗の識別子が無い → `Playable`。`processing` → `Generating`。それ以外（`failed`・`partial_failed`・矛盾する組合せ・未知の値・`completed` で URL が空・`completed` で識別子あり）→ `Failed`（A-26） |
| domain | `QueueItem` の実装 | `Episode` が `core.QueueItem` を実装する（`PlaybackQueue<Episode>` を A-T2b で使うため） |
| adapter | `EpisodeDecoder` | DTO の文字列を domain の語へ写してから生成関数を呼ぶ。状態の文字列（`"processing"`・`"failed"` など）が現れるのは、ここと `model/` だけ（TA-V4） |
| adapter | `PodcastApiAdapter` | `ApiClient` を包み、`PodcastApi` の 5 操作を実装する形を用意する（戻り値を `Episode` にした port は A-T2b が作るので、本 slice では decoder を通した `Episode` を返す内部の関数として用意し、A-T2b で port に繋ぐ） |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `Episode` と `PodcastStatusBadge` が併存（判別の規則が 2 箇所） | A-T2a | A-T2a | `PodcastStatusBadge.kt` の削除 | A-T2b |
| `PodcastApiAdapter` が未配線 | A-T2a | A-T2a | `AppContainer` が配線する | A-T2b |

許可リスト: 増やさない。TA-V4 の状態の文字列は `network/EpisodeDecoder.kt` に現れるが、Spec §7 TA-V4「状態の文字列は `network/` の decoder にだけ残る」のとおり違反に数えない（`network/` は adapter）。

## 7. 変わる挙動

無い（既存の入口から呼ばない）。

## 8. 契約と検査

- PS-07・PS-07b: 判別の表のテスト（`EpisodeTest`）。行は「`completed`＋URL＋識別子なし → Playable」「`completed`＋識別子あり → Failed（PS-07）」「`partial_failed`＋URL → Failed（PS-07b）」「`failed` → Failed」「`processing` → Generating」「`completed`＋URL 空 → Failed」「未知の `status` → Failed」の 7 行以上。テスト名に `PS-07`・`PS-07b`・`TA-R-CT1` を含める。
- TA-R-CT2: `displayTitle` の 3 段（題名・日本語イントロ・既定の文言）。
- TA-V5: `Episode` の構築子が公開でない、property が `val`、mutable な型を持たない（`ArchitectureStructureTest` が対象に入れる）。
- TA-V6: 生成に渡した list（`articleIds`・内容）を渡した後で書き換えても `Episode` の値が変わらない。返した list を `MutableList` に cast して書き換えても次に読んだ値が変わらない。テスト名に `TA-V6`。
- decode: DTO の全 15 field の写し、未知の `type` の扱い（`kind` の既定）。

## 9. 受入とテストのコマンド

- `JAVA_HOME=<Android Studio 同梱の JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0（`android/` で実行。takt の実行中は、takt の worktree の外で Gradle を走らせない）。
- 正の対照: 上の 7 行の判別がすべて期待どおり。負の対照: 判別の表の「未知の `status`」の行を `Playable` に倒す実装に一時的に替えると、その行のテストが落ちる（PR の説明に記録し、戻す）。
- `grep -rn 'PodcastResponse\|com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/catalog` = 0（domain に DTO が無い。集合 = `catalog/` の全 Kotlin）。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。許可リストの件数が着手前と同じか少ない。
- 対象の新規 3 ファイルが `LayerMap` にあり、既存の main のファイルの差分が 0（`git diff --stat main -- app/src/main` は新規 3 ファイルだけ）。
- `catalog/domain/Episode.kt` が `android.*`・`androidx.*`・`kotlinx.serialization.*`・`com.rioikeda.newslisten.{model,network,di,R}` を import しない（TA-D1）。
- 判別の表のテストがテスト名に `PS-07`・`PS-07b` を含む。

相互矛盾の突き合わせ: 「既存のファイルを変えない」と「`Episode` が `QueueItem` を実装する」は、新規ファイルの側で実装するので両立する。

## 11. 決定 slice か適用 slice か

適用 slice。判別の規則は共有仕様 §6.6・ADR-103・ADR-108 で決定済み（Spec §10.3 の 3 は「確認だけ」で、A-T2b で適用する）。

## 12. 規模の目安と返却事項

- 規模 ≈ 450 行（Spec §8.4。測っていない）。1 PR。
- 返却事項: `Episode` の field と判別の表を Spec §5.2 に照合した結果。共有仕様 §4.4 PS-07・PS-07b の Android の保留は A-T2b の完了で解除する旨。
