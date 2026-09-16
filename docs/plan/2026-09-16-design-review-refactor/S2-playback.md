## android リファクタ S2: 再生セッションの一本化（`PlaybackSession`・`ResumeRule`・一括切替）

## 概要
「現在再生中」の二重表現（`Queue` と `_currentPodcast`）、速度の未統一（5 段設定画面 vs 8 段定数）、再生失敗時の未定義挙動を解消し、`PlaybackSession` union と `ResumeRule` を core/ に導入する。正本は user 承認済みの Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1 Playback モデル・§4 CI-T1〜T9・T15・T17・T19（T20 は baseline）・§5 CP1〜CP6・§6 S2 行）。本タスクは**承認済み指示書に従う実装**であり、analyze_order は検証モード（新規設計をしない）。generate_spec の spec.md は Spec の該当契約（CI-T1〜T9・T15・T17・T19）の抜粋で足り、契約 ID は Spec のものを再利用する。

着手順 3（S1 に依存）。**検証モード**: 状態遷移・失敗方針・速度規則は Spec で決定済みであり、本タスクで再設計しない。

## 前提・着手条件
- 依存 slice: S1（`PodcastApi`・`BaseFakeApiClient`・`FakePlayerController` の `state` 注入経路）が main に merge 済みであること。
- **一括切替（SG-R15、user 2026-09-16 承認）**: S2 は小ステップではなく一括切替。入口条件 = 下記「特性テスト（baseline）」の**8 ファイル＋T-T20 が green** になってから切替に入る。それ以前は revert 以外の回復手段がない（Spec §6 rollback）。
- **Selection Gate は確定済み（共有仕様 §6.7、2026-09-16 user 判断）。本 slice で実装する**:
  - **SG-X1**: 完聴時に **`duration` を明示的に 1 回送る**（Spec SG-R14 のとおり。順序: `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。共有仕様 PS-06）。
  - **SG-X4**: **一時停止中は 15 秒周期の送信をしない**。送るのは再生中の周期と状態変化（pause / stop / 完聴）時の 1 回（共有仕様 §6.4・PS-05b）。Spec SG-R9 の「現行維持」は、backend の streak が位置 PATCH 到達で記録される事実（U1 解消）により覆る。
  - SG-X3（cleanup を待たない）は S0 で順序変更済みの前提。
- 棄却済み案（再提案しない、Spec §5 rejected_overdesign）: 旧 `PodcastViewModel` の再生 field と新 `PlaybackSession` の併存移行（正本一意と二重 owner が両立しないため不採用）、`AuthState` の 4 状態化、`PlaybackService` の interface 化、`Episode` decode（判別共用体）、preferences 消去契約の追加。
- `docs/trial-log/` を最初に読み、棄却済み案を再試行しない。

## 対象（android サブモジュールのみ。ファイル単位）
1. **`podcast/ExoPlayerController`**: `PlaybackState` union（`Idle` / `Loading` / `Playing(position, duration)` / `Paused(position, duration)` / `Ended(duration)` / `Failed(reason)`）を `state: StateFlow<PlaybackState>` として公開。`onPlayerError` を新規購読し `classifyPlaybackError(code)`（純関数、表駆動）で `reason: Source|Decode|Network|Unknown(code)` へ分類する。`isPlaying` は派生値として残す。`Player` 型は interface に出さない（`PlaybackService` だけが具象を読む現状維持）。
2. **`core/PlaybackSession`（新規、非 Android クラス）**: `NothingPlaying` / `Starting(episode, resumePosition, speed)` / `Active(episode, speed)` / `Completed(episode)` / `Stopped(episode)` / `Errored(episodeRef|episode, reason)` の union。分母 11 遷移（Spec §3.1 の表）。
3. **`core/ResumeRule`（新規、純関数）**: `resolveResumePosition(serverSeconds: Double, durationSeconds: Int): Double`。規則: `durationSeconds > 0 && serverSeconds >= durationSeconds − 2` なら 0、`serverSeconds > 0` ならその値、それ以外 0。
4. **`core/PlaybackQueue`**: `init` で不変条件 1〜3 を検査し違反は `require` で throw。`setQueue`（`core/PlaybackQueue.kt:39-44`）は入力を**先勝ちで dedupe**してから構築する。
5. **`preferences/PlaybackConstants`**: 値域を 8 段（0.5〜2.5）の `speeds: List<Double>` に一本化。
6. **`settings/SettingsScreen.kt:1361`**: 独自の `PLAYBACK_SPEEDS`（5 段）を削除し `PlaybackConstants.speeds` を参照する。
7. **`preferences/DataStorePreferencesStore.setDefaultPlaybackSpeed`**: 8 段の値域外を**拒否して直前の妥当値を維持**する。
8. **`podcast/PodcastViewModel`（PlaybackCoordinator）**: `startEpisode`（source 解決 → `PlaybackSession.start` → `prepare → setSpeed(既定速度) → seekTo(resume, resume > 0 のとき) → play`）、`onEnded`（`markCompleted` 1 セッション 1 回 → `updatePlaybackPosition(duration)` 1 回（SG-X1 確定）→ `queue.advance` → 次があれば `startEpisode`、無ければ `Stopped`）、`stopForSubjectLeave()`（位置同期 1 回 → `playerController.stop()` → `queue = PlaybackQueue()` → `NothingPlaying`。`AppContainer.onSubjectLeave` から呼ぶ）、位置同期の事前条件（`Starting`/`Active`/`Completed` のときだけ送る）を実装する。
9. **`network/AudioCacheManager`**: `invalidate(id)` を追加。CACHED 経路で player が `Failed(Source|Decode)` になったら Coordinator が `invalidate` して `Errored(Player)` へ（次回は NETWORK）。
10. **`podcast/PodcastViewModel.nowPlaying: StateFlow<NowPlaying?>`（新規）**: `episodeId` / `displayTitle` / `japaneseIntroText` / `segments` / `vocabulary` / `quiz` / `difficulty` の 7 field（`durationSeconds` は含めない。player が正本）。`session` が `NothingPlaying` または `Errored(episodeRef のみ)` のとき `null`。
11. **`podcast/PodcastViewModel.kt:83-86` の `_currentPodcast` 削除**。`keepCurrentPodcast` フラグも削除。
12. **UI 3 ファイルの読み替え**: `AudioPlayerSection.kt:65,75,101,115,125-126,142`・`PodcastScreen.kt:61,160,204`・`QueueSheet.kt:58,83` の `currentPodcast` 直読みを `nowPlaying` へ置換。行 UI の「再生中」ハイライトは `nowPlaying?.episodeId == podcast.id`（一時停止でもハイライト維持＝現状の観測挙動を保存）。`PodcastScreen` の `isPlaying = currentPodcast?.id == podcast.id` は `isNowPlaying` へ rename（LF11 解消）。
13. **`AppContainer.onSubjectLeave`**: S0 で rename 済みの関数に `stopForSubjectLeave()` の呼出を追加する（音声・FCM に加えて再生停止。S0 では呼んでいなかった部分）。

## 契約（RED テストの対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T1 | 状態は §3.1 の union のみ。11 遷移以外は起きない。`Errored` は `Paused`/`Stopped` と区別できる | T-T1: `FakePlayerController` の状態注入と Coordinator 操作（start/playNow/retry/advance）の入力列で 11 遷移を観測（分母 11。各遷移 1 ケース） |
| CI-T2a | `Failed(reason)` の分類表（Network/Decode/Source/Unknown） | T-T2a（JVM: `FakePlayerController` の状態注入と `classifyPlaybackError(code)` の表駆動） |
| CI-T2b | `onPlayerError` が購読され `Failed` へ写像される | **JVM 不可**。androidTest 不在のため未検証として記録し、S2 の実機観測（UV3）で 1 回確認 |
| CI-T3 | `resolveResumePosition` の表: (0,600)→0, (120,600)→120, (598,600)→0, (599,600)→0, (120,0)→120, (−5,600)→0 | T-T3（表駆動） |
| CI-T4 | 開始後の position は resume に等しく、speed は既定速度に等しい。CACHED 経路は一覧 DTO の位置、NETWORK 経路は fresh DTO の位置 | T-T4: `FakePlayerController` の `seekTo`/`setSpeed` 引数を観測 |
| CI-T5 | INV-P1。完聴→advance→NETWORK 失敗の系列で `queue.current.id == session.episodeRef.id` かつ `Errored(FetchFailed)`。`retry()` が `startEpisode(queue.current)` を再実行 | T-T5（既存 `PodcastViewModelTest` の系列を拡張） |
| CI-T6 | `UNAVAILABLE` → network 取得なし、`Errored(SourceUnavailable)`。文言「オフライン」 | T-T6 |
| CI-T7 | `init` は不変条件 1〜3 違反で throw。公開操作（`setQueue` の dedupe を含む）は正規化し throw しない。Q-01〜Q-32 不変 | 既存 conformance 32 件（不変）＋ T-T7: 不正構築 3 例（index 範囲外・空で index 0・id 重複）で throw、`copy` も同様、`setQueue([a,a,b], 0)` → `[a,b]` |
| CI-T8 | 完聴時の順序 = `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。同一 episode の `markCompleted` は 1 セッション 1 回（SG-X1 確定・PS-06） | T-T8: `FakePodcastApi` の呼出列 |
| CI-T9 | CACHED 経路で player `Failed(Source|Decode)` → `invalidate(id)` → `isCached=false`、次回 `resolvePlaybackSource` は NETWORK | T-T9 |
| CI-T15 | `setDefaultPlaybackSpeed` は 8 段以外を拒否（既定へ正規化）。server 同期経路も通る | T-T15（`DataStorePreferencesStoreTest` に境界値） |
| CI-T17 | `currentPodcast` は存在せず、UI は `nowPlaying` だけを読む | T-T17: 構造検査（grep `currentPodcast` = 0）＋ `QueueSheet` の両者揃い条件が消える |
| CI-T19 | 位置同期は session が `Starting`/`Active`/`Completed` のときだけ送る。`Errored`/`Stopped`/`NothingPlaying` では送らない。**周期送信は再生中のみ**で、一時停止中は pause 時の 1 回の後は送らない（SG-X4 確定・PS-05b） | T-T19: `Errored(FetchFailed)` 後に timer を進めても `FakePodcastApi.updatePlaybackPosition` が呼ばれない。T-T19b: pause 後に timer を進めても追加の呼出がない |

## 特性テスト（baseline。着手前に green を確認。**8 ファイル**）
`podcast/PodcastViewModelTest`（52）、`core/PlaybackQueueConformanceTest`（32）、`core/PlaybackSourceResolverTest`（4）、`network/AudioCacheManagerTest`（14）、`podcast/PlaybackMetadataTest`、`podcast/PodcastStatusBadgeTest`、`settings/SettingsViewModelTest`（24。速度）、`preferences/DataStorePreferencesStoreTest`（5）。加えて **T-T20 を baseline に先に追加**（gate 指摘 8、CI-T20: `play`/`playNow` の連続呼出は直列化され位置同期 timer は常に 1 本。2 回連続 `playNow` 後の `updatePlaybackPosition` の id が最後の episode のみ。現行 `playMutex`/`syncJob` の挙動を pin）。

## 手順
1. baseline: 上記 8 ファイル＋T-T20 すべて green を確認・記録（一括切替の入口条件）。
2. T-T3（`ResumeRule`）→ RED → `core/ResumeRule.kt` 実装 → GREEN。
3. T-T7（Queue `init`/`setQueue` dedupe）→ RED → `core/PlaybackQueue` 実装 → GREEN。
4. T-T2a（`classifyPlaybackError`）→ RED → 純関数実装 → GREEN。`onPlayerError` の購読は `ExoPlayerController` に追加（T-T2b は JVM 不可のため実装のみ、検証は UV3）。
5. T-T1（`PlaybackSession`）→ RED → `core/PlaybackSession.kt` 実装 → GREEN。
6. T-T4〜T6・T9（Coordinator: startEpisode・source 分岐・invalidate）→ RED → `PodcastViewModel` 実装 → GREEN。
7. T-T8（完聴順序・`duration` 送信）・T-T19 / T-T19b（位置同期の送信条件・一時停止中は送らない）→ RED → 実装 → GREEN。
8. T-T15（速度 8 段拒否）→ RED → `DataStorePreferencesStore` 実装 → `SettingsScreen` の `PLAYBACK_SPEEDS` 削除 → GREEN。
9. `nowPlaying` を実装し T-T17（`currentPodcast` grep 0）→ RED → `_currentPodcast`/`keepCurrentPodcast` 削除 → UI 3 ファイルの読み替え → GREEN。
10. `stopForSubjectLeave()` を実装し `AppContainer.onSubjectLeave` から呼ぶ（S0 の rename 済み関数に再生停止を追加）。
11. 8 特性テスト＋T-T20 が green であることを確認してから、旧 `_currentPodcast` 経路から `PlaybackSession`/`nowPlaying` への**一括切替**を行う。
12. **TP2（temporary path）を導入する**: `PlayerController.onPlaybackCompleted` コールバックを当面維持する（`state.Ended` で置換可能になるまで）。owner: user、導入: S2、削除条件: `PodcastViewModel` が `state` の `Ended` だけを購読するようになった時。
13. UV3（エミュレータ `newslisten_e2e` で手動観測）: resume 位置の適用、既定速度の適用、次エピソード失敗時の停止表示の 3 項目を確認・記録する。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。
- grep oracle: `error("` = 0（S1 で達成済み・回帰なし）、`currentPodcast` = 0、`code == 401` = 0（S0 で達成済み・回帰なし）。
- T-T1〜T9・T15・T17・T19 が `verifies: CI-T*` をテスト名またはコメントに持つ。T-T20 は baseline として存在し green。
- 準拠テスト PS-01〜PS-08（`shared-playback-spec.md` §4.4）のうち S2 で検証できる行 ID をテスト名に含める。
- 完聴時に `updatePlaybackPosition(duration)` が 1 回送られ（PS-06）、一時停止中に周期送信が起きない（PS-05b）ことがテストで固定されている。
- UV3 の 3 項目（resume・既定速度・次エピソード失敗時の停止表示）の観測結果が記録されている。
- レビュー観点: `PlayerController` の interface に Media3 の型が出ていない。`PodcastViewModel` の再生 use case が `PodcastApi` だけに依存している（S1 の境界を維持）。

## 禁止事項 / scope 外
- 旧 `_currentPodcast` と新 `PlaybackSession`/`nowPlaying` の併存移行はしない（棄却済み）。
- `AuthState` の 4 状態化、`PlaybackService` の interface 化、`Episode` decode（判別共用体）はしない（棄却済み）。
- 語彙登録・クイズ中継の 3 操作の port 分離はしない（RF8 保留）。
- preferences 消去契約は追加しない。
- 仕様にない業務条件を足さない。

## 参照
- Spec: `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §3.1（Playback モデル）・§4（CI-T1〜T9, T15, T17, T19〜T20）・§5（CP1〜CP6・naming_decisions・rejected_overdesign）・§6（S2 行・TP2）
- レビュー: `android/docs/research-reports/2026-09-16-code-design-review.md` §8.2（SG-R1〜R4・SG-R7・SG-R9・SG-R10）・§8.3（順 3）
- 親 docs: `docs/design/shared-playback-spec.md` §2.11（advance 後の再生失敗）・§4.3 RS-01〜07（再開位置）・§4.4 PS-01〜08 / SL-01〜05（再生セッション・主体離脱）・§6.4〜§6.7（SG-X1〜X5）。準拠テストは行 ID（`PS-*`/`SL-*`/`RS-*`）をテスト名に含める。
