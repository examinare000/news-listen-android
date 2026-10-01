## android リファクタ A-T8a: Catalog の記事（`Article`・`PendingCuration`・`ArticleRow`・`FeedApi`）

> **着手前に決める項目（1 件。決まるまで投入しない）**: `feed/FeedViewModel.kt` の `= e.message` 3 箇所（`:89` フィードの読み込み・`:106` 読み直し・`:204` Star / Dismiss の確定の失敗）の文面。Spec §8.4「A-T8a」は「例外の message しか無い箇所は A-T3b と同じ判断に従う」とし、§10.3 の 1 は影響先に「A-T8a の `feed/` の 3 箇所も同じ扱い」と書く。決定 SG-D6（2026-10-01）は**再生**の文面（「エピソードを取得できませんでした。…」「再生できませんでした。…」）だけを決めており、記事の取得と Star / Dismiss の文面は決まっていない。
> - 推奨（SG-D6 と同じ形）: 読み込みと読み直しの失敗「記事を取得できませんでした。通信状況を確かめて、もう一度お試しください」、Star / Dismiss の確定の失敗「操作できませんでした。もう一度お試しください」。生成上限（`RateLimited`）は現行の `generationLimitMessage` のまま。
> - 決まったら、この節を決定 ID つきの「変わる挙動」へ移し、本 order を ready にする。文面以外の本文は決定に依らず、このまま使える。

## 1. 目的と、応える要求・設計・契約の ID

フィードの記事を DTO から domain（`Article`・`PendingCuration`）とリードモデル（`ArticleRow`）へ移す。読み込みの前の保留の確定（§6 の 2）を command と query に分ける（順序は変えない）。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(3)・NFR-10、F-FEED-04・06・07 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-4・AQ-6 | `docs/design/architecture.md` §2・§5 |
| 決定 | ADR-110 決定 4〜6、ADR-044（カードのジェスチャ）、SG-D6（再生の文面。本 slice の文面は上の「決める項目」） | `docs/adr/` |
| Spec | TA-M-CT（`Article`・`PendingCuration`・`ArticleRow`）、TA-C-CT1・CT2・TA-Q-CT2、TA-R-CT3・CT4、§6 の 2、TA-D2（3 ファイル）・TA-D7（`onStarConfirmed`）・TA-D9（`= e.message` の `feed/` の 3 箇所）、TA-V7（実行時）、§5.2「port」（`FeedApi`）、§8.4「A-T8a」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |

## 2. 前提（着手条件）

- A-T7b2（A-T7b の後半。前半 A-T7b1 はその前に入っている）の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- **上の「着手前に決める項目」が決まっている**。
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
| presentation | 失敗の種類から文面を選ぶ（上の「決める項目」の文面と、既存の `generationLimitMessage`） |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| ファイルの置き場（`feed/` 直下） | 各ファイル | — | 目標の package へ | A-T9 |

許可リスト: TA-D2 の 3 ファイル（`FeedViewModel`・`FeedScreen`・`PendingArticleAction`）、TA-D7 の `onStarConfirmed`（公開の `var` 1 → 0）、TA-V4 の `= e.message` の `feed/` の 3 件が消える。増やさない。

## 7. 変わる挙動

上の「着手前に決める項目」で決まる文面だけ（決定 ID は決定のときに付く）。フィードの読み込み・読み直し・Star / Dismiss の確定に失敗したとき、例外の message の代わりに決まった文面を出す。ほかは変えない（保留の確定の順序・429 の戻し方・生成上限の文言）。

## 8. 契約と検査

- TA-R-CT4 が `PendingCuration` と `FeedViewModel` に（§9 の grep）。
- TA-V7（実行時。`feed/FeedViewModelTest.kt`）: `loadFeed()`・`refresh()` を呼んでも Fake の `star`・`dismiss` が 0 回。入口が `commitPending()` → `loadFeed()` の順に呼ぶ。テスト名に `TA-V7`。
- TA-V5: 公開の `var` が 0（許可リストの TA-D7 が空）。TA-V6: `ArticleRow` のカプセル化。
- 文面のテストは決定 ID をテスト名に含める。既存のテストのうち `errorMessage` の文字列を見ていたものは決定 ID を理由に反転する。

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

適用 slice。ただし文面 1 件が判断待ちで、**決まるまで投入しない**。

## 12. 規模の目安と返却事項

- 規模 ≈ 700 行（Spec §8.4）。1 PR。
- 返却事項: 記事の文面の決定を台帳に登録する旨（親 docs）。Spec §8.4「A-T8a」の「A-T3b と同じ判断に従う」を、決まった文面へ直す旨。
