# android リファクタ計画（2026-09-16 設計レビュー反映）— takt 委譲用の指示書

2026-09-16 の android 設計レビュー（`android/docs/research-reports/2026-09-16-code-design-review.md`）と user 承認済みの Implementation Spec（`android/docs/design/2026-09-16-implementation-spec-playback-auth.md`）、2026-09-23 の主体離脱の設計決定（親 docs [ADR-104](../../../../docs/adr/104-subject-departure-and-subject-scoped-assets.md)）を、takt の `sdd-governed` ワークフローへ slice 単位で委譲するための指示書（order）一式。正本は親 docs の設計書（`design/android-design.md` §7・`design/shared-playback-spec.md` §4・§6）→ ADR → Spec の順で、本フォルダの各 order はその該当 slice を takt の 1 タスクに切り出したもの。slice ID は親 plan `docs/plan/2026-09-16-design-review-refactor.md` の接頭辞付き ID（A-*）を正本とする（2026-09-23 に旧 S2 の一括切替を 3 段へ再スライス。同日夜の点検で A-S2b を A-S2b1 / A-S2b2 の 2 境界に分けた。親 plan への ID 反映は router 側。旧 S0〜S4 の番号は使わない。完了済みの `S0-auth.md` はファイル名のまま残す）。実装完了後、本フォルダは削除し、確定内容は親 docs `design/android-design.md`（§7 target 節を現状記述へ書き換え）へ移す（`agent-rules/30` の plan ライフサイクル）。

共有仕様の先行 PR（`shared-playback-spec.md` §6.4・§6.5）は news-listen-docs #133 で **完了**。§6.7 の Selection Gate は 2026-09-16 に全て確定し、SG-X3 は 2026-09-23 に ADR-104 で「待たない＋主体識別」へ改訂済み。**未決の設計判断は残っていない**（親 plan）。各 order は確定値を実装対象に含む。

## slice と投入順

| 順 | ID | order | 内容 | 依存 | 検証する行 ID（android-design §7.3） | 型 |
|---|---|---|---|---|---|---|
| 1 | A-S0（完了） | [S0-auth.md](S0-auth.md) | `ApiException.Unauthorized`・`refreshAuth` の分岐・`AuthInterceptor.onUnauthorized`・`onSubjectLeave` rename・`SessionStore.save` の失敗返却 | なし | SL-03・SL-05（T-T10 / T-T21 の意味で green。行 ID の付与は A-S4） | 小ステップ |
| 2 | A-S1（wave 1・takt 実行中） | [A-S1-test-foundation.md](A-S1-test-foundation.md) | `BaseFakeApiClient` と 9 Fake の継承化・throwing default 9 箇所削除・`PodcastApi` 切り出し・`FakePodcastApi`・`FakePlayerController` の `state` 注入経路 | A-S0 | CI-T16（挙動不変） | 挙動不変 |
| 3 | A-S2a | [A-S2a-playback-domain.md](A-S2a-playback-domain.md) | `PlaybackState` union（`ExoPlayerController` に `onPlayerError`）・`PlaybackSession`（11 遷移）・`core/ResumeRule` の新設。既存コードから呼ばない | A-S1 | RS-01〜RS-07・PS-04（述語）・T-T1 の 11 遷移・T-T2a | ① domain（新規のみ） |
| 4 | A-S2b1 | [A-S2b1-queue-speed-contracts.md](A-S2b1-queue-speed-contracts.md) | `PlaybackQueue.init` の不変条件と `setQueue` dedupe・`PlaybackConstants.speeds: List<Double>` 8 段・`setDefaultPlaybackSpeed` の値域外拒否（直前値維持）・`AudioCacheManager.invalidate` の追加。`PodcastViewModel` は `setSpeed` の型追随のみ | A-S2a（技術的には A-S1 のみ。同一 submodule のため直列） | CI-T7 / T15 / T9（操作部） | ②-1 契約（Coordinator 不変） |
| 5 | A-S2b2 | [A-S2b2-playback-entry.md](A-S2b2-playback-entry.md) | `PodcastViewModel` の再生 use case を `PlaybackSession` 経由へ差し替え: `nowPlaying`・完聴順序・位置同期の送信条件・既定速度のセッション適用・`invalidate` 接続・`stopForSubjectLeave`。§2・Q-* は挙動不変。暫定互換 TP-A1（派生 `currentPodcast`）・TP2（`onPlaybackCompleted`）。`errorMessage` と `stopPlayback` は維持 | A-S2a ＋ A-S2b1 | PS-01〜PS-03・PS-05 / PS-05b・PS-06（SG-X1）・PS-08・PS-04・CI-T4/T5/T6/T8/T9/T19/T20（PS-07 は学習サイクルで対象外） | ②-2 entry（変更行を列挙） |
| 6 | A-S2c | [A-S2c-playback-cleanup.md](A-S2c-playback-cleanup.md) | `_currentPodcast` / `keepCurrentPodcast` / `stopPlayback`・TP-A1・TP2・設定画面の速度 5 段・UI 3 ファイル＋`PodcastRowView` の読み替え完了（旧 `invalidate` 経路は実測で空集合） | A-S2b2 | CI-T17（参照 0 件の grep） | ③ cleanup（削除のみ） |
| 7 | A-S3 | [A-S3-ci.md](A-S3-ci.md) | `ci.yml` を `testDebugUnitTest` / `lintDebug` / `assembleDebug` の独立ステップに、`jvmToolchain(17)` | A-S2c | CI-T18（差分レビュー） | — |
| 8 | A-S4 | [A-S4-subject-cache.md](A-S4-subject-cache.md) | 主体別音声キャッシュ `{cacheDir}/audio/{user_id}/`・起動時の回収・平置きの初回全削除・ダウンロードジョブの主体固定と離脱時 cancel（待たない）・主体離脱の遷移導出と順序（トークン破棄 → 未認証または次の主体 → 後始末）・`CleanupIncomplete`・logout は捕捉トークンで既存 `Authorization: Bearer` を付け await しない・client の FCM 登録解除呼出を削除（サーバ連鎖・決定 10）・主体依存 4 key の削除（`seen_achievement_ids` 含む）・`user_id` は任意・`Unknown` の間は起動回収しない | **backend 契約**（`/auth/me`・login 応答の `user_id`、セッション削除の FCM 連鎖 = B-S5）＋ A-S1 | SL-01・SL-02・SL-04・SL-06・SL-07（＋SL-03 / SL-05 の行 ID 付与）・CI-T12 / T13 | 適用 slice |
| 保留 | — | （order 未作成） | RF6 全面（意味型）・RF8（Screen owner 分散）・RF9（週目標・難易度の値域）・RF10（UiState 排他）・RF2・PS-07 | 学習機能・設定サイクル | — | — |

- 直列の依存: A-S1 → A-S2a → A-S2b1 → A-S2b2 → A-S2c → A-S3。A-S4 は backend 契約 ＋ A-S1 の後で、A-S2a〜c・A-S3 と独立に投入できる（A-S4 の後始末の手順集合は着手時点の `onSubjectLeave` を引き継ぐ。A-S2b2 が先なら `stopForSubjectLeave` を含む）。SL-01 の「再生停止」部分は A-S2b2 と A-S4 の両方が merge されて初めて green。
- A-S2b を 2 つに分けた理由（2026-09-23 夜）: 旧 A-S2b は「Coordinator に触らない契約（Queue の不変条件・速度値域・`invalidate` 操作）」と「Coordinator の差し替え（現在再生中の正本・再生状態と失敗・完聴順序・送信条件）」の 2 境界を抱え、1 PR の見込みが `PodcastViewModel.kt` 560 行＋`PodcastViewModelTest.kt` 1,218 行の書き換えを含めて 1,000 行を超えていた。前者は `PlaybackSession` に依存せず単独で契約テスト green にでき、失敗時の巻き戻しを Coordinator 側に閉じられる。
- 3 段分割の判定基準（親 plan）: ① は新規ファイルの一覧と契約テスト green、② は「§2・Q-* 挙動不変（特性テスト）」＋「変更行の列挙（準拠テスト）」、③ は削除対象の列挙と参照 0 件の grep。
- Android に効く確定済み Selection Gate: **SG-X1**（完聴時に `duration` を 1 回送る。A-S2b2）、**SG-X3**（待たない＋主体識別。ADR-104。A-S4）、**SG-X4**（一時停止中は周期送信しない。A-S2b2）、**SG-A6**（主体依存 4 key を消す。A-S4）、**SG-B3**（`Unknown` 中は回収しない。A-S4）、**SG-B4**（FCM 解除はサーバ連鎖。A-S4）、**SG-B6**（`user_id` 任意は 3 platform 共通。A-S4）。SG-X2 / SG-X5 は Android に無関係。
- 正本間の差（order 側で採用先を明記済み。docs 反映は完了時）: CI-T15 の拒否時挙動は Spec「既定へ正規化」ではなく親 docs §7.2「直前の妥当値を維持」を採る（A-S2b1）。主体依存 key は §7.2・親 plan の「3 key」ではなく共有仕様 §6.5 分類表の「4 key」、FCM は §7.2 の「トークン解除」ではなく §6.5 Android 行の「連鎖削除に任せ client 呼出を削除」を採る（A-S4）。
- grep oracle `code == 401` の到達点は 0 件ではなく **2 箇所**（`AuthInterceptor.kt:48` の発火条件・`OkHttpApiClient.kt:459` の写像。A-S0 の帰結）。各 order は「2 箇所から増えない」で書く。
- 他モジュールとの契約: パスワード規則は backend 正本（[ADR-101](../../../../docs/adr/101-password-policy-cross-client-unification.md)）。Android は現状の文言のまま変更なしのため order を作らない。
- 準拠テストは行 ID（`PS-*` / `SL-*` / `RS-*`）をテスト名に含める（共有仕様 §5）。

## 着手条件（全 order 共通）

- 依存 slice の **android PR が main に merge 済み** かつ **親リポ `news-listen` の submodule ポインタが進んでいる**（親で `git submodule status` を実行し `android` 行に `+` が無い）。takt の worktree は親 main から fresh clone され、bootstrap が submodule と親の記録の一致を検査するため（不一致は `tree_incomplete`）。
- 他モジュールに依存する slice（A-S4 ← B-S5）は PR 番号ではなく「契約が親 main にあること」で判定する（A-S4 の「依存契約」）。

## 投入順と release トリガ（wave 2 以降）

| 順 | ID | 依存 | release トリガ（この PR が main に入り、親ポインタが進んだら） | 並行 |
|---|---|---|---|---|
| 1 | A-S2a | A-S1 | A-S1 の android PR ＋ 親ポインタ PR | A-S4 と並行可（backend 契約が先に揃っていれば） |
| 2 | A-S2b1 | A-S2a | A-S2a の android PR ＋ 親ポインタ PR | 同上 |
| 3 | A-S2b2 | A-S2a・A-S2b1 | A-S2b1 の android PR ＋ 親ポインタ PR | 同上 |
| 4 | A-S2c | A-S2b2 | A-S2b2 の android PR ＋ 親ポインタ PR | 同上 |
| 5 | A-S3 | A-S2c | A-S2c の android PR ＋ 親ポインタ PR | 同上 |
| 任意 | A-S4 | A-S1 ＋ backend 契約 | B-S5 の backend PR ＋ 親ポインタ PR（A-S1 は wave 1 で先行） | A-S2a〜A-S3 のどの位置にも挟める。ただし同一 submodule のため同時に 2 つは走らせない |

## takt への投入手順（親リポ `news-listen` の作業ツリーで）

order はサブモジュール内の docs にあるが、takt のタスク単位は親リポ（`.takt/config.yaml` の `submodules: all`）。order 本文をタスク内容として渡す（投入時にコピーされるため、投入後の order 修正は反映されない）。

```bash
# 例: A-S2a（takt 0.66 は非対話の add を受けないため、実際は .takt/enqueue-orders.mjs の ORDERS 配列で投入する。親 plan「投入の型」）
node .takt/enqueue-orders.mjs
node .takt/hold-tasks.mjs release takt/refactor/android-a-s2a-playback-domain
takt run
```

- ブランチ名は `takt/refactor/android-<slice>`。1 slice = 1 タスク = 1 PR（サブモジュール PR → 親 PR。draft は作らない。`.takt/facets/knowledge/project-context.md`）。
- 前 slice の android PR が main に merge され、親ポインタ PR も merge されてから次を release する。
- **投入前に、指示書を `analyze_order` の受入検査（全称命題の対象集合の数え上げ／条項どうしの矛盾／未決の選択）へ自分で通す。** 未決が 1 件でも残っていれば投入しない（親 `docs/trial-log/order-acceptance-inspection-finds-design-defects.md`）。
- analyze_order は order を「承認済み指示書」として**検証モード**で受ける（新規設計をしない）。generate_spec の `spec.md` / `plan.md` は Spec の該当契約（CI-T*）の抜粋で足り、新しい契約 ID を作らない。
- 各 order の「特性テスト（baseline）」が green でなければ着手しない。
- 検証コマンド: `JAVA_HOME=<Android Studio 同梱 JBR> ./gradlew clean testDebugUnitTest --console=plain`（A-S3 の merge 後は `jvmToolchain(17)` により `./gradlew clean testDebugUnitTest` のみで良い）。JDK 26 では Kotlin コンパイラが起動不能なため JBR を明示する。JDK 不適合の実行の直後は増分キャッシュが汚れるため `clean` を挟む（`android/docs/research-reports/2026-09-16-code-design-review/verification-run.md`）。

## 完了後
- 各 slice の merge 後、`docs/trial-log/` に棄却・方針転換があれば追記（takt の record_trial_log が行う）。
- A-S2a〜c 完了で共有仕様 §4.3（RS）・§4.4（PS-01〜06・08）の android 保留を解除、A-S4 完了で SL-06 / SL-07 と SL-01 / SL-02 / SL-04 の android 保留を解除する（§5 の解除条件）。
- 全 slice 完了で本フォルダを削除し、親 docs `design/android-design.md` §7 を「target 設計（未着手）」から現状記述へ書き換える（§7.2 の「3 key」「FCM トークン解除」・Spec CI-T15 の「既定へ正規化」もこのとき揃える）。
