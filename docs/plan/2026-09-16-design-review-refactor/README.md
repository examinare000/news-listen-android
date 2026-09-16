# android リファクタ計画（2026-09-16 設計レビュー反映）— takt 委譲用の指示書

2026-09-16 の android 設計レビュー（`android/docs/research-reports/2026-09-16-code-design-review.md`）と user 承認済みの Implementation Spec（`android/docs/design/2026-09-16-implementation-spec-playback-auth.md`）を、takt の `sdd-governed` ワークフローへ slice 単位で委譲するための指示書（order）一式。正本は Spec であり、本フォルダの各 order は Spec の該当 slice を takt の 1 タスクに切り出したもの。実装完了後、本フォルダは削除し、確定内容は親 docs の `design/android-design.md`（§7 target 節を現状記述へ書き換え）へ移す（`agent-rules/30` の plan ライフサイクル）。

共有仕様の先行 PR（親 docs `shared-playback-spec.md` §6.4 resume 規則・§6.5 主体離脱の事後条件の新節）は news-listen-docs #133 で **完了**（main 済み）。ただし §6.7 の Selection Gate SG-X1〜SG-X5 は **pending**。gate が pending の項目は各 order で現行値を pin する。

## slice と投入順

| 順 | order | 内容 | 依存 | Selection Gate | 切替方式 |
|---|---|---|---|---|---|
| 1 | [S0-auth.md](S0-auth.md) | `ApiException.Unauthorized`・`refreshAuth` の分岐・`AuthInterceptor.onUnauthorized`（発火条件は三点一致＋`Authorization` 付与 401 のみ）・`onLogoutCleanup → onSubjectLeave` rename（音声＋FCM のみ、再生停止は S2）・`CleanupIncomplete`・`SessionStore.save` の失敗返却 | なし | なし | 小ステップ |
| 2 | [S1-test-foundation.md](S1-test-foundation.md) | `BaseFakeApiClient`（全メソッド error）と 9 Fake の継承化、production interface の throwing default 9 箇所削除、`PodcastApi`（5 メソッド）切り出し、`FakePodcastApi`、`FakePlayerController` の `state` 注入 | S0 | なし | 挙動不変 |
| 3 | [S2-playback.md](S2-playback.md) | `PlaybackState` union・`PlaybackSession`（11 遷移）・`ResumeRule`・Queue `init`／`setQueue` dedupe・速度 8 段統一と既定速度適用・`nowPlaying`・`_currentPodcast` 削除・完聴順序・`invalidate`・`stopForSubjectLeave`・位置同期の送信条件・UI 3 ファイルの読み替え | S1 | **SG-X1 / SG-X3 / SG-X4** が pending の間は現行値で pin | **一括切替**（特性テスト 8 ファイル＋T-T20 green が入口条件） |
| 4 | [S3-ci.md](S3-ci.md) | `ci.yml` を `testDebugUnitTest` / `lintDebug` / `assembleDebug` の独立ステップに、`jvmToolchain(17)` | S2 | なし | — |
| 保留 | （S4） | RF6 全面（意味型）・RF8（Screen owner 分散）・RF9（週目標・難易度の値域）・RF10（UiState 排他）・RF2 | 学習機能・設定サイクル | — | order 未作成 |

- Selection Gate の正本は親 docs `design/shared-playback-spec.md` §6.7（SG-X1〜SG-X5、owner: user）。pending を選択済みとして扱わない。
- **S2 が依存する gate と pin 内容**:
  - **SG-X1**（完聴時にサーバーへ送る位置。候補 (a) 0 / (b) duration＝Android Spec SG-R14）: pending の間は Android の**現行挙動（完聴時点の再生位置をそのまま送る。`PodcastViewModel.kt:538` の `positionSeconds`）で pin**する。SG-R14 の「明示的に duration を送る」は本計画では実装せず、SG-X1 が (b) で satisfied になってから差分 PR として起票する。
  - **SG-X3**（主体離脱で cleanup 完了を待つか。候補 (a) 待たない / (b) 待つ＝Android 現行）: pending の間は**現行の「待つ」を維持**する。
  - **SG-X4**（一時停止中も位置を周期送信するか。候補 (a) 送る＝Android 現行 / (b) 再生中のみ）: pending の間は**現行の「送る」を維持**する。
  - SG-X2（web の resume 規則追随）・SG-X5（iOS の速度段数）は Android に無関係。
- 他モジュールとの契約: パスワード規則は backend 正本（[ADR-101](../../../../docs/adr/101-password-policy-cross-client-unification.md)）。Android は現状の文言のまま変更なしのため、本フォルダに order を作らない。
- 共有仕様 §6.4／§6.5（新節）は news-listen-docs #133 で main 済み。S2 の準拠テストは行 ID（PS-* / SL-* / RS-*）をテスト名に含める。

## takt への投入手順（親リポ `news-listen` の作業ツリーで）

order はサブモジュール内の docs にあるが、takt のタスク単位は親リポ（`.takt/config.yaml` の `submodules: all`）。order 本文をタスク内容として渡す。

```bash
# 例: S0
takt add -w sdd-governed -b takt/refactor/android-s0-auth \
  -t "$(cat android/docs/plan/2026-09-16-design-review-refactor/S0-auth.md)"
takt run
```

- ブランチ名は `takt/refactor/android-<slice>`。1 slice = 1 タスク = 1 PR（サブモジュール PR → 親の draft PR の順。`.takt/facets/knowledge/project-context.md`）。
- 前 slice の PR が main に merge されてから次を投入する（takt の worktree は main を基点に clone するため）。
- analyze_order は order を「承認済み指示書」として**検証モード**で受ける（新規設計をしない）。generate_spec の `spec.md` / `plan.md` は Spec の該当 slice の契約（CI-T*）の抜粋で足り、新しい契約 ID を作らない。
- 各 order の「特性テスト（baseline）」が green でなければ着手しない（bootstrap_worktree のベースライン verify とは別に、slice 固有の baseline）。
- 検証コマンド: `JAVA_HOME=<Android Studio 同梱 JBR> ./gradlew clean testDebugUnitTest --console=plain`（S3 の merge 後は `jvmToolchain(17)` により `./gradlew clean testDebugUnitTest` のみで良い）。JDK 26 では Kotlin コンパイラが起動不能なため JBR を明示する。JDK 不適合の実行の直後は増分キャッシュが汚れるため `clean` を挟む（`android/docs/research-reports/2026-09-16-code-design-review/verification-run.md`）。

## 完了後
- 各 slice の merge 後、`docs/trial-log/` に棄却・方針転換があれば追記（takt の record_trial_log が行う）。
- 全 slice 完了で本フォルダを削除し、親 docs `design/android-design.md` §7 を「target 設計（未着手）」から現状記述へ書き換える。
