## android リファクタ A-S2a: 再生ドメイン層の新設（`PlaybackState`・`PlaybackSession`・`ResumeRule`）

## 概要
再生の状態を型で表す 3 つの新規コード（境界の出力型 `PlaybackState` union、use case 側の `PlaybackSession` union（11 遷移）、`core/` の純関数 `resolveResumePosition`）を**新設だけ**する。既存の `PodcastViewModel`・UI からは呼ばない。正本は user 承認済みの Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1 Playback モデル・§4 CI-T1・CI-T2a・CI-T2b・CI-T3・§5 CP1/CP3/CP4・§6 S2 行）と親 docs `docs/design/android-design.md` §7.3（A-S2a 行）。旧 `S2-playback.md`（一括切替）を 3 段に分けた第 1 段（親 plan `docs/plan/2026-09-16-design-review-refactor.md`「3 段分割の型 ①」）。本タスクは**承認済み指示書に従う実装**であり、analyze_order は**検証モード（再設計しない）**。generate_spec の spec.md は CI-T1・T2a・T2b・T3 の抜粋で足り、新しい契約 ID を作らない。

着手順: A-S1 → **A-S2a** → A-S2b → A-S2c。

## 前提・着手条件
- 依存 slice: A-S1（`PodcastApi`・`BaseFakeApiClient`・`FakePlayerController` の `state` 注入経路）が main に merge 済み。
- baseline: `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` が exit 0（A-S1 完了時点の全 unit テスト）。
- Selection Gate: 本 slice で新たに効くものは無い（SG-X1 / SG-X4 は A-S2b で実装）。
- 棄却済み案（再提案しない。Spec §5 rejected_overdesign）: `PlaybackService` の interface 化、`Episode` decode（判別共用体）、旧 field と新 `PlaybackSession` の併存移行。
- `docs/trial-log/`（android・親）を最初に読む。

## 対象（android サブモジュールのみ。ファイル単位）
**新規（main）**
1. `podcast/PlaybackState.kt`: sealed `PlaybackState` = `Idle` / `Loading` / `Playing(position, duration)` / `Paused(position, duration)` / `Ended(duration)` / `Failed(reason)`。`reason: PlaybackFailureReason` = `Source` / `Decode` / `Network` / `Unknown(code)`。純関数 `classifyPlaybackError(errorCode: Int): PlaybackFailureReason`（表駆動。判定順: `ERROR_CODE_IO_FILE_NOT_FOUND` / `ERROR_CODE_IO_BAD_HTTP_STATUS` → `Source`、他の `ERROR_CODE_IO_*` → `Network`、`ERROR_CODE_DECODING_*` / `ERROR_CODE_PARSING_*` → `Decode`、他 → `Unknown(code)`。定数の集合は本プロジェクトが依存する Media3 の `PlaybackException` から取り、表をテストに書く＝Spec UK1 の確定）。
2. `podcast/PlaybackSession.kt`（置き場は Spec §2 の表どおり `podcast/`。`android.*`・`network`・`kotlinx.coroutines` を import しない純粋クラス）: sealed union `NothingPlaying` / `Starting(episode, resumePosition, speed)` / `Active(episode, speed)` / `Completed(episode)` / `Stopped(episode)` / `Errored(ref, reason)`。`ref` は `episode`（DTO）または `episodeRef(id)`。`reason: SessionErrorReason` = `SourceUnavailable` / `FetchFailed` / `NotPlayable` / `Player(PlaybackFailureReason)`。遷移は純関数（新しい値を返す）で **11 遷移のみ**: NothingPlaying→Starting, Starting→Active, Starting→Errored, Active→Completed, Active→Errored, Active→Starting(playNow), Completed→Starting(advance), Completed→Stopped, Stopped→Starting, Errored→Starting(retry), Errored→Starting(start)。表外は `IllegalStateException`。INV-P1 の session 側を純粋述語 `satisfiesInvariantP1(queueCurrentId: String?): Boolean`（`NothingPlaying` なら真、それ以外は `ref.id == queueCurrentId`）として持つ。
3. `core/ResumeRule.kt`: `fun resolveResumePosition(serverSeconds: Double, durationSeconds: Int): Double`。規則は共有仕様 §6.4 の表（`durationSeconds > 0 && serverSeconds >= durationSeconds − 2` → 0、`serverSeconds > 0` → その値、それ以外 0）。

**変更（main。書き手だけを足す。読み手を増やさない）**
4. `podcast/PlayerController.kt`: `val state: StateFlow<PlaybackState>` を追加。`isPlaying` / `positionSeconds` / `durationSeconds` / `playbackSpeed` / `onPlaybackCompleted` は現状維持（派生値・TP2 は A-S2b/A-S2c で扱う）。Media3 の型を interface に出さない。
5. `podcast/ExoPlayerController.kt`: `state` を実装する。写像は Spec §3.1 の表（`stop()`/`STATE_IDLE` → `Idle`、`prepare()`〜`STATE_READY` 前（`STATE_BUFFERING` 含む）→ `Loading`、`onIsPlayingChanged(true)` → `Playing`、`onIsPlayingChanged(false)` かつ `STATE_READY` → `Paused`、`STATE_ENDED` → `Ended`、`onPlayerError`（**新規購読**）→ `Failed(classifyPlaybackError(errorCode))`）。`Playing` / `Paused` が持つ position・duration は遷移時点の値（500 ms ポーリングで `state` を再発行しない。連続値は既存 `positionSeconds` / `durationSeconds` が正本）。既存の `_isPlaying` 等の更新・`onPlaybackCompleted` の発火は変えない。

**変更（test）**
6. `podcast/FakePlayerController.kt`: A-S1 で用意した注入経路を `PlaybackState` に接続し `setState(PlaybackState)` で任意状態を注入できるようにする。既存の `completePlayback()` 等は残す。

**新規（test）**: `podcast/PlaybackStateTest.kt`（T-T2a）、`podcast/PlaybackSessionTest.kt`（T-T1・PS-04）、`core/ResumeRuleConformanceTest.kt`（T-T3・RS-01〜07）。

## 契約（CI → T の対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T1 | 状態は §3.1 の union のみ。11 遷移以外は起きない。`Errored` は `Paused` / `Stopped` と区別できる | T-T1: 純粋な遷移関数への事象列で 11 遷移を各 1 ケース観測（分母 11）＋表外 2 例（Errored→Active、Stopped→Completed）が throw。PS-04 は `satisfiesInvariantP1` の表駆動（各状態 × 一致／不一致） |
| CI-T2a | `Failed(reason)` の分類表 | T-T2a: `classifyPlaybackError` の表駆動＋`FakePlayerController.setState(Failed(...))` が `Paused` / `Idle` と区別されること |
| CI-T2b | `onPlayerError` が購読され `Failed` へ写像される | **JVM 不可**。実装のみ行い、未検証として記録（実機観測は A-S2b の UV3 で 1 回） |
| CI-T3 | `resolveResumePosition` の表 | T-T3: 共有仕様 §4.3 RS-01〜RS-07 の全行＋Spec の (599, 600)→0 を表駆動。テスト名に行 ID を含める |

## 特性テスト（baseline）
A-S1 完了時点の全 unit テストが green（新規コードは既存経路から呼ばれないため、slice 固有の特性テストは不要）。

## 手順
1. baseline green を記録。
2. T-T3（RS-01〜07）→ RED → `core/ResumeRule.kt` → GREEN。
3. T-T2a → RED → `podcast/PlaybackState.kt`（union・`classifyPlaybackError`）→ GREEN。
4. `PlayerController.state` を追加 → `FakePlayerController.setState` → `ExoPlayerController` に写像と `onPlayerError` 購読を実装（コンパイルと既存テスト green で確認。T-T2b は未検証と記録）。
5. T-T1（11 遷移＋表外 2 例＋PS-04 述語）→ RED → `podcast/PlaybackSession.kt` → GREEN。
6. 1 slice = 1 PR。temporary path なし。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green（既存テストの件数・名前が変わらない）。
- 新規ファイル 3（main）＋テスト 3 が存在し、T-T1・T-T2a・T-T3 が `verifies: CI-T1` / `CI-T2a` / `CI-T3` をテスト名またはコメントに持ち、RS-01〜RS-07 と PS-04 がテスト名に含まれる。
- **既存コードから呼ばれていない**: `grep -rn 'PlaybackSession\|resolveResumePosition\|classifyPlaybackError' app/src/main` の一致が `podcast/PlaybackSession.kt`・`core/ResumeRule.kt`・`podcast/PlaybackState.kt`・`podcast/ExoPlayerController.kt`（`classifyPlaybackError` の呼出 1 箇所）の 4 ファイルに閉じる。`grep -rn '\.state\b' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0。
- `grep -rn 'kotlinx.coroutines\|android\.\|com.rioikeda.newslisten.network' app/src/main/java/com/rioikeda/newslisten/core/ResumeRule.kt app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackSession.kt` = 0。
- grep oracle 回帰なし: `error("` = 0（A-S1）、`code == 401` = 0（A-S0）。`currentPodcast` の件数は本 slice で変えない（A-S2c で 0 にする）。
- CI-T2b が「未検証（JVM 不可・UV3 で確認）」として PR 本文に記録されている。

## 禁止事項 / scope 外
- `PodcastViewModel`・UI 3 ファイル（`AudioPlayerSection` / `PodcastScreen` / `QueueSheet`）・`PlaybackQueue`・`PlaybackConstants`・`DataStorePreferencesStore`・`AudioCacheManager`・`SettingsScreen` を変更しない（A-S2b / A-S2c）。
- `onPlaybackCompleted` を削除しない（A-S2c）。
- `PlaybackSession` を `core/` に置かない（Spec §2 の表: `podcast/`）。`PlaybackSession` に coroutine・StateFlow を持たせない。
- 棄却済み案（併存移行・`PlaybackService` interface 化・`Episode` decode）を持ち込まない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 上記 grep 3 本の結果を PR 本文に貼る。

## 記録
- Media3 `PlaybackException` の分類表（UK1 の確定値）をテストとして固定し、PR 本文に転記する。
- 逸脱・棄却があれば `android/docs/trial-log/` に追記。親 docs への返却事項: 共有仕様 §4.3 の保留（RS-01〜07・android A-S2a）を解除できる旨。
