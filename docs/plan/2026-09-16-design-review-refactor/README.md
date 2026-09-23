# android リファクタ計画（2026-09-16 設計レビュー反映）— takt 委譲用の指示書

2026-09-16 の android 設計レビュー（`android/docs/research-reports/2026-09-16-code-design-review.md`）と user 承認済みの Implementation Spec（`android/docs/design/2026-09-16-implementation-spec-playback-auth.md`）、2026-09-23 の主体離脱の設計決定（親 docs [ADR-104](../../../../docs/adr/104-subject-departure-and-subject-scoped-assets.md)）を、takt の `sdd-governed` ワークフローへ slice 単位で委譲するための指示書（order）一式。正本は Spec・ADR・親 docs の設計書で、本フォルダの各 order はその該当 slice を takt の 1 タスクに切り出したもの。slice ID は親 plan `docs/plan/2026-09-16-design-review-refactor.md` の接頭辞付き ID（A-*）を正本とする（2026-09-23 に旧 S2 の一括切替を 3 段へ再スライス。旧 S0〜S4 の番号は使わない。完了済みの `S0-auth.md` はファイル名のまま残す）。実装完了後、本フォルダは削除し、確定内容は親 docs `design/android-design.md`（§7 target 節を現状記述へ書き換え）へ移す（`agent-rules/30` の plan ライフサイクル）。

共有仕様の先行 PR（`shared-playback-spec.md` §6.4・§6.5）は news-listen-docs #133 で **完了**。§6.7 の Selection Gate は 2026-09-16 に全て確定し、SG-X3 は 2026-09-23 に ADR-104 で「待たない＋主体識別」へ改訂済み。**未決の設計判断は残っていない**（親 plan）。各 order は確定値を実装対象に含む。

## slice と投入順

| 順 | ID | order | 内容 | 依存 | 検証する行 ID（android-design §7.3） | 型 |
|---|---|---|---|---|---|---|
| 1 | A-S0（完了） | [S0-auth.md](S0-auth.md) | `ApiException.Unauthorized`・`refreshAuth` の分岐・`AuthInterceptor.onUnauthorized`・`onSubjectLeave` rename・`SessionStore.save` の失敗返却 | なし | SL-03・SL-05（T-T10 / T-T21 の意味で green。行 ID の付与は A-S4） | 小ステップ |
| 2 | A-S1 | [A-S1-test-foundation.md](A-S1-test-foundation.md) | `BaseFakeApiClient` と 9 Fake の継承化・throwing default 9 箇所削除・`PodcastApi` 切り出し・`FakePodcastApi`・`FakePlayerController` の `state` 注入経路 | A-S0 | CI-T16（挙動不変） | 挙動不変 |
| 3 | A-S2a | [A-S2a-playback-domain.md](A-S2a-playback-domain.md) | `PlaybackState` union（`ExoPlayerController` に `onPlayerError`）・`PlaybackSession`（11 遷移）・`core/ResumeRule` の新設。既存コードから呼ばない | A-S1 | RS-01〜RS-07・PS-04（述語）・T-T1 の 11 遷移・T-T2a | ① domain（新規のみ） |
| 4 | A-S2b | [A-S2b-playback-entry.md](A-S2b-playback-entry.md) | Queue `init` と dedupe・速度 8 段（Double）と既定速度適用・`nowPlaying`・完聴順序・位置同期の送信条件・`invalidate`・`stopForSubjectLeave` で入口を差し替え。§2・Q-* は挙動不変。暫定互換 TP-A1（派生 `currentPodcast`）・TP2（`onPlaybackCompleted`） | A-S2a | PS-01〜PS-03・PS-05 / PS-05b・PS-06（SG-X1）・PS-08・PS-04・CI-T4/T7/T9/T15/T19/T20（PS-07 は学習サイクルで対象外） | ② entry（変更行を列挙） |
| 5 | A-S2c | [A-S2c-playback-cleanup.md](A-S2c-playback-cleanup.md) | `_currentPodcast` / `keepCurrentPodcast`・TP-A1・TP2・設定画面の速度 5 段・UI 3 ファイル＋`PodcastRowView` の読み替え完了（旧 `invalidate` 経路は実測で空集合） | A-S2b | CI-T17（参照 0 件の grep） | ③ cleanup（削除のみ） |
| 6 | A-S3 | [A-S3-ci.md](A-S3-ci.md) | `ci.yml` を `testDebugUnitTest` / `lintDebug` / `assembleDebug` の独立ステップに、`jvmToolchain(17)` | A-S2c | CI-T18（差分レビュー） | — |
| 7 | A-S4 | [A-S4-subject-cache.md](A-S4-subject-cache.md) | 主体別音声キャッシュ `{cacheDir}/audio/{user_id}/`・起動時の回収・平置きの初回全削除・ダウンロードジョブの主体固定と離脱時 cancel（待たない）・主体離脱の遷移導出と順序（トークン破棄 → 未認証または次の主体 → 後始末）・`CleanupIncomplete`・logout は捕捉トークンで `Authorization: Bearer` を付け await しない・client の FCM 登録解除呼出を削除（サーバ連鎖・決定 10）・主体依存 4 key の削除（`seen_achievement_ids` 含む）・`user_id` は任意・`Unknown` の間は起動回収しない | **B-S5**（`user_id` 公開）＋ A-S1 | SL-01・SL-02・SL-04・SL-06・SL-07（＋SL-03 / SL-05 の行 ID 付与）・CI-T12 / T13 | 適用 slice |
| 保留 | — | （order 未作成） | RF6 全面（意味型）・RF8（Screen owner 分散）・RF9（週目標・難易度の値域）・RF10（UiState 排他）・RF2・PS-07 | 学習機能・設定サイクル | — | — |

- 直列の依存: A-S1 → A-S2a → A-S2b → A-S2c → A-S3。A-S4 は B-S5 ＋ A-S1 の後で、A-S2a〜c と独立に投入できる（A-S4 の後始末の手順集合は着手時点の `onSubjectLeave` を引き継ぐ。A-S2b が先なら `stopForSubjectLeave` を含む）。SL-01 の「再生停止」部分は A-S2b と A-S4 の両方が merge されて初めて green。
- 3 段分割の判定基準（親 plan）: ① は新規ファイルの一覧と契約テスト green、② は「§2・Q-* 挙動不変（特性テスト）」＋「変更行の列挙（準拠テスト）」、③ は削除対象の列挙と参照 0 件の grep。
- Android に効く確定済み Selection Gate: **SG-X1**（完聴時に `duration` を 1 回送る。A-S2b）、**SG-X3**（待たない＋主体識別。ADR-104。A-S4）、**SG-X4**（一時停止中は周期送信しない。A-S2b）、**SG-A6**（主体依存 4 key を消す。A-S4）。2026-09-23 user 判断 Q10（`Unknown` 中は回収しない）・Q11（FCM 解除はサーバ連鎖）・Q13（`user_id` 任意は 3 platform 共通）は A-S4 に反映済み。SG-X2 / SG-X5 は Android に無関係。
- 他モジュールとの契約: パスワード規則は backend 正本（[ADR-101](../../../../docs/adr/101-password-policy-cross-client-unification.md)）。Android は現状の文言のまま変更なしのため order を作らない。
- 準拠テストは行 ID（`PS-*` / `SL-*` / `RS-*`）をテスト名に含める（共有仕様 §5）。

## takt への投入手順（親リポ `news-listen` の作業ツリーで）

order はサブモジュール内の docs にあるが、takt のタスク単位は親リポ（`.takt/config.yaml` の `submodules: all`）。order 本文をタスク内容として渡す。

```bash
# 例: A-S2a
takt add -w sdd-governed -b takt/refactor/android-a-s2a-playback-domain \
  -t "$(cat android/docs/plan/2026-09-16-design-review-refactor/A-S2a-playback-domain.md)"
takt run
```

- ブランチ名は `takt/refactor/android-<slice>`。1 slice = 1 タスク = 1 PR（サブモジュール PR → 親 PR。draft は作らない。`.takt/facets/knowledge/project-context.md`）。
- 前 slice の PR が main に merge されてから次を投入する（takt の worktree は main を基点に clone するため）。
- **投入前に、指示書を `analyze_order` の受入検査（全称命題の対象集合の数え上げ／条項どうしの矛盾／未決の選択）へ自分で通す。** 未決が 1 件でも残っていれば投入しない（親 `docs/trial-log/order-acceptance-inspection-finds-design-defects.md`）。
- analyze_order は order を「承認済み指示書」として**検証モード**で受ける（新規設計をしない）。generate_spec の `spec.md` / `plan.md` は Spec の該当契約（CI-T*）の抜粋で足り、新しい契約 ID を作らない。
- 各 order の「特性テスト（baseline）」が green でなければ着手しない。
- 検証コマンド: `JAVA_HOME=<Android Studio 同梱 JBR> ./gradlew clean testDebugUnitTest --console=plain`（A-S3 の merge 後は `jvmToolchain(17)` により `./gradlew clean testDebugUnitTest` のみで良い）。JDK 26 では Kotlin コンパイラが起動不能なため JBR を明示する。JDK 不適合の実行の直後は増分キャッシュが汚れるため `clean` を挟む（`android/docs/research-reports/2026-09-16-code-design-review/verification-run.md`）。

## 完了後
- 各 slice の merge 後、`docs/trial-log/` に棄却・方針転換があれば追記（takt の record_trial_log が行う）。
- A-S2a〜c 完了で共有仕様 §4.3（RS）・§4.4（PS-01〜06・08）の android 保留を解除、A-S4 完了で SL-06 / SL-07 と SL-01 / SL-02 / SL-04 の android 保留を解除する（§5 の解除条件）。
- 全 slice 完了で本フォルダを削除し、親 docs `design/android-design.md` §7 を「target 設計（未着手）」から現状記述へ書き換える。
