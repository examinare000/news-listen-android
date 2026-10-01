## android リファクタ A-T7a: Learning のダッシュボード・ストリーク・実績（`LearningDashboardQuery`・`StreakQuery`・`Achievement`・`LearningApi`）

## 1. 目的と、応える要求・設計・契約の ID

学習ダッシュボード・ストリーク・実績を、DTO を読む画面から、query とリードモデル（`LearningDashboardView`・`StreakBadge`）へ移す。読み込みの中で書いている実績の既読（§6 の 1）を、query と command に分ける（保存の時点は変えない）。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(3)・NFR-10、F-LRN-01・02・04・05・06・09〜11 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-4・AQ-6 | `docs/design/architecture.md` §2・§5 |
| 決定 | ADR-110 決定 4〜6・9、ADR-062（ストリーク）・ADR-072・ADR-086（実績）・ADR-087（単語テスト）、SG-A5 の解除、導出 A-23 | `docs/adr/`、Spec §10.1 |
| 学習仕様 | L-R01・L-R02・L-R04・L-R09・L-R10・L-R21、実績 | `docs/design/learning-engagement-spec.md`、Spec §5.5 の対応表 |
| Spec | TA-M-LN（`Achievement`・`LearningDashboardView`・`StreakBadge`）、TA-C-LN4・TA-Q-LN1・LN2・LN5、TA-R-LN4〜LN6、§6 の 1 と 3、TA-D2（4 ファイル）・TA-D7（`onStreakIncreased`）、TA-V7（実行時）、§8.4「A-T7a」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |

## 2. 前提（着手条件）

- A-T6b（A-T6 の後半。前半 A-T6a はその前に入っている）の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い（module をまたぐ「習得」の語の論点は A-T7b の語に効くだけ。Spec §10.3 末尾）。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| 読み込みの中の書込 | `grep -n 'markAchievementsSeen\|seenAchievementIds' app/src/main/java/com/rioikeda/newslisten/learning/LearningViewModel.kt` | `:52-54`（§6 の 1） |
| 単語テストがあるか | `grep -n 'test-session\|fetchVocabularyTestSession\|hasVocabularyTest' app/src/main/java/com/rioikeda/newslisten/learning/LearningViewModel.kt` | `:47-51`（§6 の 3。backend の明示の例外の route） |
| DTO の計算 | `grep -n 'progressText\|progressFraction' app/src/main/java/com/rioikeda/newslisten/model/LearningEngagementModels.kt` | `:39-43` |
| 画面の計算 | `grep -nF '/ 31.0' app/src/main/java/com/rioikeda/newslisten/learning/LearningScreen.kt`／`grep -n 'com.rioikeda.newslisten.model\.' app/src/main/java/com/rioikeda/newslisten/learning/LearningScreen.kt \| grep -v import` | `:425`／完全修飾名 5 箇所（`:233,234,323,364,365`）。`:394,497` の率と推移の計算、`:237,288-289` の実績の突き合わせ |
| ストリークの条件 | `grep -n 'streak' app/src/main/java/com/rioikeda/newslisten/AppScaffold.kt`／`grep -n 'onStreakIncreased\|e.code' app/src/main/java/com/rioikeda/newslisten/engagement/ListeningStreakStore.kt` | `AppScaffold.kt:201-202`／`:38`（公開の `var`）・`:51-53`（増えた判定）。`:56` の code の比較は A-T4 で消えている |
| 画面の全文 | `wc -l app/src/main/java/com/rioikeda/newslisten/learning/LearningScreen.kt` | **着手のときに全文を読む**（Spec §11） |
| `LearningApi` | `grep -n 'suspend fun' app/src/main/java/com/rioikeda/newslisten/network/LearningApi.kt` | 3 操作（戻り値が DTO） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `learning/domain/Achievement.kt`（7 件の id と「新しく解錠された」の判定）、`learning/app/LearningDashboardQuery.kt`（`load()`・`view: StateFlow<LearningDashboardView?>`）、`learning/app/StreakQuery.kt`（`streak: StateFlow<StreakBadge?>`・`refresh()`）、`learning/app/LearningReadModels.kt`（`LearningDashboardView`・`StreakBadge`）、`learning/app/LearningCommands`（`markAchievementsSeen(ids)`）、`learning/app/LearningApi.kt`（port。ダッシュボード・ストリーク・「単語テストがあるか」の読み取り）、`network/LearningApiAdapter.kt`。
**変える（main）**: `learning/LearningViewModel.kt`（query と command を §6 の順で呼ぶ）、`learning/LearningScreen.kt`（リードモデルを読む。DTO の完全修飾名を無くす）、`learning/AchievementCatalog.kt`（id を domain へ、名前と説明を presentation へ）、`engagement/ListeningStreakStore.kt`（`StreakQuery` へ。公開の `var` を事象の読み取り `feedbackEvents` に替える。TA-Q-LN5）、`AppScaffold.kt`（`StreakBadge` を読む）、`model/LearningEngagementModels.kt`（`progressText`・`progressFraction` を外す）、`network/LearningApi.kt`（port を `learning/app/` に置き、戻り値を domain / リードモデルの部品に）、`di/AppContainer.kt`。
**変える（test）**: `LearningViewModelTest`・`ListeningStreakStore` のテスト・`StreakBadgeTest`、新しい `learning/LearningDashboardQueryTest.kt`（TA-V7 の実行時）、`AchievementTest`、TA-V6 のテスト、`Allowlist.kt`。

**対象外**: クイズ・語彙の登録・単語テスト（A-T7b）。「単語テストがあるか」の route を副作用の無いものに替えること（backend の契約の変更が要る。Spec §6 の末尾で求めない）。効果音と触覚を鳴らす実体（`designsystem/DSFeedback.kt`）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `Achievement`（TA-R-LN6: 新しく解錠された = 解錠済み − 既読） |
| application（query） | `LearningDashboardQuery`（TA-Q-LN1。週の目標の進み・月の活動日数の率（31 日で割る）・週の記録の達成率・成績の推移は直近 3 件 = TA-R-LN5。「新しく解錠された実績」を含む view を返すだけで、書かない）、`StreakQuery`（TA-Q-LN2。出す条件 = 日数 1 以上かつ聴いた日がある、「増えた」の判定 = TA-R-LN4）、`feedbackEvents`（TA-Q-LN5。ストリークの増加・実績の解錠） |
| application（command） | `markAchievementsSeen(ids)`（TA-C-LN4） |
| §6 の 1 | 画面の入口（`LearningViewModel`）が、読み込みが済んだ直後に続けて `markAchievementsSeen` を呼ぶ。保存の時点（読み込みの直後）は変えない（A-23） |
| §6 の 3 | 「単語テストがあるか」は `LearningApi` の読み取りとして置き、adapter がこの route を呼ぶ。Android の側では状態を変えない（A-23） |
| adapter | `LearningApiAdapter` が DTO を写す。DTO（`model/`）は計算を持たない |
| presentation | 実績の名前・説明と、文言（`progressText` に当たる「今週 x/目標 y 本」）は presentation |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `LearningApi`・`VocabularyTestApi` の残りの操作（語彙・クイズ・単語テスト）が DTO を返す | 既存 | 既存 | A-T7b で型を替える | A-T7b |
| ファイルの置き場（`learning/`・`engagement/` 直下） | 各ファイル | — | 目標の package へ | A-T9 |

許可リスト: TA-D2 の 4 ファイル（`LearningViewModel`・`LearningScreen`・`ListeningStreakStore`・`AppScaffold`）、TA-D7 の `onStreakIncreased`（公開の `var` 2 → 1）、TA-V4 の `/ 31.0`、`model/LearningEngagementModels.kt` の計算が消える。増やさない。

## 7. 変わる挙動

無い（§6 の 1 は保存の時点を変えず、§6 の 3 は同じ route を読む）。

## 8. 契約と検査

- TA-R-LN4〜LN6 の「今どこに居るか」が正本へ（§9 の grep）。
- TA-V7（実行時。`learning/LearningDashboardQueryTest.kt`）: query を呼んでも Fake の書き込みの操作（`markAchievementsSeen`・語彙の登録・送信）が 0 回。入口が「query → command」の順で呼ぶ（呼出の順を Fake で観測）。テスト名に `TA-V7`・`A-23`。
- TA-V6: `LearningDashboardView`（実績の既読の集合を含む）のカプセル化。
- L-R01・02・04・09・10・21 の表示の既存テストが期待値を変えずに green。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -rn 'progressText\|progressFraction' app/src/main/java/com/rioikeda/newslisten/model` = 0。
- `grep -rnF '/ 31.0' app/src/main` の一致が `learning/app/` の 1 ファイルだけ。
- `grep -n 'com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/learning/LearningScreen.kt app/src/main/java/com/rioikeda/newslisten/learning/LearningViewModel.kt app/src/main/java/com/rioikeda/newslisten/engagement/ListeningStreakStore.kt app/src/main/java/com/rioikeda/newslisten/AppScaffold.kt` = 0（集合 = 4 ファイル。import と完全修飾名の両方）。
- `grep -rnE '^\s*var on[A-Z]' app/src/main/java/com/rioikeda/newslisten/engagement` = 0。
- `grep -n 'markAchievementsSeen' app/src/main/java/com/rioikeda/newslisten/learning/app/LearningDashboardQuery.kt` = 0（query が書かない）。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§7 の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- TA-V7 の実行時テストがある。
- TA-V10（PR の説明）: 「ダッシュボードに導出した値を 1 つ足したら」の問いに、変わるファイル（`LearningDashboardView` と `LearningDashboardQuery` と画面）を答える。

相互矛盾の突き合わせ: 「保存の時点を変えない」と「query が書かない」— 書込を command に移し、入口が読み込みの直後に続けて呼ぶので両立する。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 700 行（Spec §8.4）。1 PR。
- 返却事項: 学習仕様の「model への写像は学習サイクルまで保留（SG-A5）」を Android について解いた旨（Spec §10.2）。architecture.md §5 の「明示の例外」の表に Android の §6 の 1・3 の扱いを足す旨。
