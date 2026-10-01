## android リファクタ A-T8a: Catalog の記事（`Article`・`PendingCuration`・`ArticleRow`・`FeedApi`）

> **決定 SG-D10**（2026-10-01 user 判断。Spec §10.3 の D-A8a-1。台帳 = 親 docs `research-reports/2026-09-23-design-docs-mino-audit.md` §5）: `feed/FeedViewModel.kt` の `= e.message` 3 箇所（`:89` フィードの読み込み・`:106` 読み直し・`:204` Star / Dismiss の確定の失敗）を 3 文にする。通信の失敗（`ApiException.NetworkError`）は「オフラインです。接続を確認してから、もう一度お試しください」（iOS の `FeedViewModel.offlineMessage` と同じ）。取得の失敗（それ以外）は「記事を取得できませんでした。通信状況を確かめて、もう一度お試しください」。操作の失敗（それ以外）は「操作できませんでした。もう一度お試しください」。生成の上限（`ApiException.RateLimited`）の専用文言（`generationLimitMessage`）は変えない。一括 Star の件数の集計（`bulkActionResult`）は対象外。
>
> **補正（2026-10-01）**: SG-D10 の確定に合わせ、「着手前に決める項目」を消し、文面の写像を §5・§7・§8 に書いた。本 order は ready。

## 1. 目的と、応える要求・設計・契約の ID

フィードの記事を DTO から domain（`Article`・`PendingCuration`）とリードモデル（`ArticleRow`）へ移す。読み込みの前の保留の確定（§6 の 2）を command と query に分ける（順序は変えない）。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(3)・NFR-10、F-FEED-04・06・07 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-4・AQ-6 | `docs/design/architecture.md` §2・§5 |
| 決定 | ADR-110 決定 4〜6、ADR-044（カードのジェスチャ）、SG-D6・SG-D11（再生の文面。通信の失敗の分け方を本 slice とそろえる）、SG-D10（本 slice の記事の文面） | `docs/adr/` |
| Spec | TA-M-CT（`Article`・`PendingCuration`・`ArticleRow`）、TA-C-CT1・CT2・TA-Q-CT2、TA-R-CT3・CT4、§6 の 2、TA-D2（3 ファイル）・TA-D7（`onStarConfirmed`）・TA-D9（`= e.message` の `feed/` の 3 箇所）、TA-V7（実行時）、§5.2「port」（`FeedApi`）、§8.4「A-T8a」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |

## 2. 前提（着手条件）

- A-T7b2（A-T7b の後半。前半 A-T7b1 はその前に入っている）の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| `= e.message` | `grep -n '= e\.message' app/src/main/java/com/rioikeda/newslisten/feed/FeedViewModel.kt` | `:89,106,204` |
| 保留の確定 | `grep -n 'commitPendingInternal' app/src/main/java/com/rioikeda/newslisten/feed/FeedViewModel.kt`／`grep -n 'ON_STOP\|commitPending' app/src/main/java/com/rioikeda/newslisten/feed/FeedScreen.kt` | `FeedViewModel.kt:82,101` ほか／`FeedScreen.kt:108-112`（ライフサイクルの事象を command へ写す。presentation のまま） |
| 公開の `var` | `grep -nE '^\s*var on' app/src/main/java/com/rioikeda/newslisten/feed/FeedViewModel.kt` | `:43` `onStarConfirmed`（TA-V5 の残り 1 件） |
| 公開操作 | `grep -nE 'fun (star|dismiss|undoLast|commitPending|bulkStar|loadFeed|refresh)' app/src/main/java/com/rioikeda/newslisten/feed/FeedViewModel.kt` | `:118,125,160,171,239` と `:79,98` |
| DTO の import | `grep -ln 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/feed/{FeedViewModel,FeedScreen,PendingArticleAction}.kt` | 3 ファイル |
| 画面の全文 | `wc -l app/src/main/java/com/rioikeda/newslisten/feed/FeedScreen.kt` | **着手のときに全文を読む**（Spec §11） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `feed/domain/Article.kt`、`feed/domain/PendingCuration.kt`（Star / Dismiss の保留 1 件と取り消し。TA-R-CT4）、`feed/app/ArticleRow.kt`（リードモデル）、`feed/app/FeedApi.kt`（port: `fetchFeed`・`star`・`dismiss`）、`network/FeedApiAdapter.kt`。
**変える（main）**: `feed/FeedViewModel.kt`（command と query を分ける。`= e.message` を無くす。`onStarConfirmed` を `feedbackEvents` に）、`feed/PendingArticleAction.kt`（`PendingCuration` へ。文言は `feed/ui/` の側）、`feed/FeedScreen.kt`（`ArticleRow` を読む。入口が `commitPending()` → `loadFeed()` の順に呼ぶ）、`di/AppContainer.kt`。
**変える（test）**: `feed/FeedViewModelTest.kt`（TA-V7 の実行時を足す。期待値は変えない。文面のテストだけ決定 ID を理由に変える）、`PendingCurationTest`、TA-V6 のテスト、`Allowlist.kt`。

**対象外**: 記事の URL の検査（TA-R-CT3。`feed/ArticleUrlValidator.kt` のまま）。ライフサイクルの事象を command へ写す画面の仕事（`FeedScreen.kt:108-112`）。生成上限の文言（`generationLimitMessage`）。ファイルの package 移動のうち新規以外（A-T9）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `Article`、`PendingCuration`（TA-R-CT4: 保留は 1 件。次の操作・画面の離脱・読み直しの前に確定する。失敗と 429 は元の位置へ戻す） |
| application（command） | TA-C-CT1 `star(articleId, difficulty?)`・`dismiss(articleId)`・`undoLast()`・`commitPending()`（結果は無し。失敗は通知の種類）、TA-C-CT2 `bulkStar(ids)`（receipt: 成功と失敗の件数、上限のときの待ち秒数） |
| application（query） | TA-Q-CT2 `articles: StateFlow<List<ArticleRow>>`・`pendingAction`・`isLoading`・`isRefreshing`・`loadFeed()`・`refresh()`。`loadFeed()`・`refresh()` は保留を確定しない（§6 の 2: 画面の入口が `commitPending()` を先に呼ぶ。順序は変えない） |
| application（事象） | `onStarConfirmed` を `feedbackEvents`（スワイプの確定。TA-Q-LN5）の読み取りに替える |
| presentation | 失敗の種類から文面を選ぶ（下の表の 3 文と、既存の `generationLimitMessage`。SG-D10） |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| ファイルの置き場（`feed/` 直下） | 各ファイル | — | 目標の package へ | A-T9 |

許可リスト: TA-D2 の 3 ファイル（`FeedViewModel`・`FeedScreen`・`PendingArticleAction`）、TA-D7 の `onStarConfirmed`（公開の `var` 1 → 0）、TA-V4 の `= e.message` の `feed/` の 3 件が消える。増やさない。

## 7. 変わる挙動

SG-D10 の文面だけ。フィードの読み込み・読み直し・Star / Dismiss の確定に失敗したとき、例外の message の代わりに次の文面を出す（例外の型ごとの写像。`errorMessage` に入る値）。ほかは変えない（保留の確定の順序・429 の戻し方・生成上限の文言・一括 Star の件数の集計）。

| 決定 ID | 場面（現行の箇所） | 例外の型 | 文面（変更後） | 現行 |
|---|---|---|---|---|
| SG-D10 | フィードの取得（`loadFeed` `:89`・`refresh` `:106`） | `ApiException.NetworkError` | 「オフラインです。接続を確認してから、もう一度お試しください」 | 例外の message（`Network error: …`） |
| SG-D10 | 同上 | `ApiException.RateLimited` | 変えない（現行の固定の文面「リクエストが多すぎます。しばらくしてからお試しください。」。`network/ApiException.kt:19` の message。技術的な文言ではなく、SG-D10 の対象外）。order の実装では、この文面を例外の message に頼らず定数として持つ | 同左 |
| SG-D10 | 同上 | それ以外の `ApiException` | 「記事を取得できませんでした。通信状況を確かめて、もう一度お試しください」 | 例外の message |
| SG-D10 | Star / Dismiss の確定（`commitPendingInternal` から呼ぶ送信 `:204`） | `ApiException.NetworkError` | 「オフラインです。接続を確認してから、もう一度お試しください」 | 例外の message |
| SG-D10 | 同上 | `ApiException.RateLimited` | 変えない（`generationLimitMessage(retryAfterSeconds)`。`:198-201`） | 同左 |
| SG-D10 | 同上 | それ以外の `ApiException` | 「操作できませんでした。もう一度お試しください」 | 例外の message |

対象外: 一括 Star（`bulkStar`）の件数の集計（`bulkActionResult`）と、その上限の文言（`:278`）。

## 8. 契約と検査

- TA-R-CT4 が `PendingCuration` と `FeedViewModel` に（§9 の grep）。
- TA-V7（実行時。`feed/FeedViewModelTest.kt`）: `loadFeed()`・`refresh()` を呼んでも Fake の `star`・`dismiss` が 0 回。入口が `commitPending()` → `loadFeed()` の順に呼ぶ。テスト名に `TA-V7`。
- TA-V5: 公開の `var` が 0（許可リストの TA-D7 が空）。TA-V6: `ArticleRow` のカプセル化。
- 文面のテストは決定 ID（`SG-D10`）をテスト名に含める。既存のテストのうち `errorMessage` の文字列を見ていたものは SG-D10 を理由に反転する。
- テストの期待値（`feed/FeedViewModelTest.kt`。Fake の `FeedApi` が投げる例外 → `errorMessage`）:

| 操作 | Fake が投げる | 期待する `errorMessage` |
|---|---|---|
| `loadFeed()` | `ApiException.NetworkError(IOException())` | 「オフラインです。接続を確認してから、もう一度お試しください」 |
| `loadFeed()` | `ApiException.HttpError(500)` | 「記事を取得できませんでした。通信状況を確かめて、もう一度お試しください」 |
| `refresh()` | `ApiException.NetworkError(IOException())` | 「オフラインです。接続を確認してから、もう一度お試しください」 |
| `refresh()` | `ApiException.DecodingError(RuntimeException())` | 「記事を取得できませんでした。通信状況を確かめて、もう一度お試しください」 |
| `star(…)` → `commitPending()` | `ApiException.NetworkError(IOException())` | 「オフラインです。接続を確認してから、もう一度お試しください」（記事は元の位置へ戻る） |
| `dismiss(…)` → `commitPending()` | `ApiException.HttpError(500)` | 「操作できませんでした。もう一度お試しください」（記事は元の位置へ戻る） |
| `star(…)` → `commitPending()` | `ApiException.RateLimited(120)` | `generationLimitMessage(120)` の現行の文面（変えない） |
| `loadFeed()` | `ApiException.RateLimited(null)` | 「リクエストが多すぎます。しばらくしてからお試しください。」（現行の文面。変えない） |

  `ApiException.Unauthorized` も「それ以外」に入る（失効の扱いは現行のまま。文面だけが変わる）。フィードの取得の `RateLimited` は「それ以外」に入れず、現行の固定の文面を保つ（main セッションの補正 2026-10-01。SG-D10 は技術的な文言を置き換える決定で、既に利用者向けの文面はその対象ではないため）。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -rn 'e\.message' app/src/main/java/com/rioikeda/newslisten/feed` = 0（集合 = `feed/` の全 Kotlin）。
- `grep -rn 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/feed` = 0。
- `grep -rnE '^\s*var on[A-Z]' app/src/main/java/com/rioikeda/newslisten` = 0（TA-D7 の公開の callback が main に無い）。
- `grep -n 'commitPendingInternal' app/src/main/java/com/rioikeda/newslisten/feed/FeedViewModel.kt` の一致が `loadFeed`・`refresh` の関数範囲に無い（レビューで確認）。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- TA-V7 の実行時テストがある。反転したテストがすべて決定 ID を理由に持つ。

相互矛盾の突き合わせ: 「保留の確定の順序を変えない」と「query が書かない」— 入口が command を先に呼ぶので両立する。

## 11. 決定 slice か適用 slice か

適用 slice（文面は SG-D10 で確定。判断待ちは無い）。

## 12. 規模の目安と返却事項

- 規模 ≈ 700 行（Spec §8.4）。1 PR。
- 返却事項: 無し（SG-D10 は台帳と Spec §8.4「A-T8a」・§10.3 に 2026-10-01 に反映済み）。
