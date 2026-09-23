## android リファクタ S1: テスト基盤（`BaseFakeApiClient`・`PodcastApi` port）

## 概要
production interface の throwing default（9 箇所）を削除し、test 側に基底 Fake 1 つを置いて 9 Fake を継承化する。再生 use case が使う 5 操作だけを狭い port `PodcastApi` として切り出す。正本は user 承認済みの Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§2 abstraction gate・§4 CI-T16・§6 S1 行）。本タスクは**承認済み指示書に従う実装**であり、analyze_order は検証モード（新規設計をしない）。generate_spec の spec.md は Spec の該当契約（CI-T16）の抜粋で足り、契約 ID は Spec のものを再利用する。

着手順 2（S0 に依存）。**検証モード**: port の切り方（`PodcastApi` の 5 メソッド）は Spec で決定済みであり、本タスクで再設計しない。

## 前提・着手条件
- 依存 slice: S0（`ApiException.Unauthorized`・`onSubjectLeave` rename・`SessionStore.save` の失敗返却）が main に merge 済みであること。
- Selection Gate 依存なし。
- 語彙登録（`fetchVocabulary` / `saveVocabulary`）とクイズ中継（`submitQuizAnswers`）の 3 操作は RF8 保留のため分離せず、既存の `ApiClient` 経由のまま残す（gate 指摘 3。完全分離は保留 slice）。
- consumer 別の全 port 分割（RO4、10 port 分割）は不採用（棄却済み。再提案しない）。
- `docs/trial-log/` を最初に読み、棄却済み案を再試行しない。

## 対象（android サブモジュールのみ。ファイル単位）
1. **`network/ApiClient`（interface）**: throwing default（`error("...")`）を持つ 9 メソッドから default 実装を削除し、抽象メソッド化する（`OkHttpApiClient` は既存実装ですべて override 済みのため影響なし）。
2. **`network/PodcastApi`（新規 interface）**: `fetchPodcast` / `fetchPodcasts` / `updatePlaybackPosition` / `markCompleted` / `downloadAudio` の 5 メソッドを持つ狭い port。`network/LearningApi.kt`・`VocabularyTestApi.kt` と同方式。
3. **`network/ApiClient`**: `PodcastApi` を継承するよう宣言変更（`interface ApiClient : PodcastApi`）。
4. **`network/OkHttpApiClient`**: 変更しない（既に 5 メソッドを実装済み。継承関係の変更のみで実装は不変）。
5. **test 側 `BaseFakeApiClient`（新規、test ソースセット）**: `ApiClient` の全メソッドを `error("...")` で実装する基底クラス。
6. **test 側 9 Fake の継承化**: `settings/FakeApiClient.kt`・`passkey/FakeApiClient.kt`・`auth/FakeApiClient.kt`・`observability/FakeApiClient.kt`・`account/FakeApiClient.kt`・`onboarding/FakeApiClient.kt`・`feed/FakeFeedApiClient.kt`・`notification/FakeNotificationApiClient.kt`・`podcast/FakePodcastApiClient.kt` を `BaseFakeApiClient` 継承へ書き換え、各テストが必要とするメソッドだけを override する（override しないメソッドは基底の `error` に委譲。既存テストの呼出範囲は変えない＝挙動不変）。
7. **test 側 `FakePodcastApi`（新規）**: `PodcastApi` の 5 メソッドだけを持つ Fake（S2 の `PodcastViewModel` 再生テストが使う）。
8. **test 側 `FakePlayerController` の `state` 注入**: `PlayerController` の状態注入用フィールド（S2 の `PlaybackState` union のための準備）を追加する。本 slice では union 型自体は導入せず、注入経路のみ用意する。

## 契約（CI → T の対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T16 | production interface に throwing default が無い（`OkHttpApiClient` が全メソッドを override、main ソースセット全体で `error(` の grep が 0 件）。`PodcastApi` の 5 メソッドを `OkHttpApiClientTest`（MockWebServer）で経路確認 | T-T16: 構造検査（grep）＋ MockWebServer 経路 5 件 |

## 特性テスト（baseline。着手前に green を確認）
全 ViewModel テスト（191 件。9 Fake を使う全テストクラス）。加えて `network/OkHttpApiClientTest.kt`（46 件）。

## 手順
1. baseline: `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` green（全 ViewModel テスト 191 件を含む）を記録。
2. `PodcastApi` interface を新規作成 → `ApiClient : PodcastApi` へ変更 → コンパイルが通ることを確認（`OkHttpApiClient` は変更不要）。
3. `BaseFakeApiClient` を新規作成（全メソッド `error`）。
4. 9 Fake を 1 ファイルずつ `BaseFakeApiClient` 継承へ書き換え、都度 `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` で該当テストクラスが green のままであることを確認する。
5. `ApiClient` の throwing default 9 箇所を削除（抽象メソッド化）。
6. T-T16: `error("` の grep が 0 であることを確認するテスト（構造検査）を追加。
7. `FakePodcastApi` を新規作成。
8. `FakePlayerController` に `state` 注入経路を追加（S2 が使う準備。本 slice では未使用のままでよい）。
9. 1 slice = 1 PR。temporary path なし（Fake の継承化は挙動不変）。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green（既存 191 件の ViewModel テストが変わらず通る）。
- **`grep -rn 'error(' app/src/main/java/com/rioikeda/newslisten` が 0 件。** 対象集合は main ソースセット全体であり、`OkHttpApiClient` だけではない。2026-09-23 実測の現状は **9 件で、すべて `network/ApiClient.kt`**（削除対象の throwing default と 1 対 1）。main 配下の他ファイルには 1 件も無いため、9 件を消せば 0 件になる。
- T-T16 が `verifies: CI-T16` をテスト名またはコメントに持つ。
- 9 Fake すべてが `BaseFakeApiClient` を継承し、override していないメソッドを呼ぶテストが存在しない（存在すれば基底の `error` で fail する）。
- `PodcastViewModel` の再生 5 操作の型が `PodcastApi` 経由になっている（語彙・クイズ 3 操作は `ApiClient` のまま）。

## 禁止事項 / scope 外
- consumer 別の全 port 分割（10 port 分割、RO4）はしない（棄却済み）。
- 語彙登録・クイズ中継の 3 操作の port 分離はしない（RF8 保留）。
- `PlaybackState` union・`PlaybackSession`（S2）は導入しない。`FakePlayerController` の `state` 注入経路は用意するが、S2 まで未使用のままでよい。
- `OkHttpApiClient` の実装ロジックは変更しない（継承関係の宣言のみ）。
- 仕様にない業務条件を足さない。

## 参照
- Spec: `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §2（abstraction gate・port を置く根拠）・§4（CI-T16）・§5（CP9・abstraction_decisions・rejected_overdesign）・§6（S1 行）
- レビュー: `android/docs/research-reports/2026-09-16-code-design-review.md` §8.2（SG-R8）・§8.3（順 2）
