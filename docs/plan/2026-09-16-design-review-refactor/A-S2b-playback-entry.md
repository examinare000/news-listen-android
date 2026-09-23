## android リファクタ A-S2b: 再生の入口を `PlaybackSession` へ差し替える（Queue `init`・速度・`nowPlaying`・完聴順序・送信条件・`invalidate`）

## 概要
`PodcastViewModel` の再生 use case（開始・完聴・位置同期・主体離脱時の停止）を A-S2a で新設した `PlaybackSession` / `ResumeRule` / `PlaybackState` 経由に差し替える。共有仕様 §2・Q-01〜Q-32 は**挙動不変**（特性テストで判定）。**変わる挙動は下記「変更行」に限る**（準拠テストで判定）。正本は Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1・§4 CI-T4〜T9・T15・T19・T20・§5 CP2/CP5/CP6・§6 S2 行・TP2）、親 docs `docs/design/android-design.md` §7.2〜§7.3（A-S2b 行）、共有仕様 §4.4・§6.4・§6.6、ADR-103。旧 `S2-playback.md` を 3 段に分けた第 2 段（親 plan「3 段分割の型 ②」）。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T4〜T9・T15・T19・T20 の抜粋で足り、新しい契約 ID を作らない。

着手順: A-S2a → **A-S2b** → A-S2c。

## 前提・着手条件
- 依存 slice: A-S2a が main に merge 済み。
- **入口条件**: 下記「特性テスト（baseline）」8 ファイル＋T-T20 が green。
- Selection Gate は確定済み（共有仕様 §6.7、ADR-103）。本 slice で実装する: **SG-X1** 完聴時に `duration` を 1 回明示送信（順序 `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。PS-06）。**SG-X4** 一時停止中は 15 秒周期送信をしない（PS-05b）。
- 棄却済み案（再提案しない）: 旧 `_currentPodcast` を owner のまま残す併存移行、`AuthState` 4 状態化、preferences 消去契約の追加（A-S4 で SG-A6 により別途扱う）、`Episode` decode。
- `docs/trial-log/`（android・親）を最初に読む。

## 変更行（挙動が変わるのはこの行だけ。準拠テスト名に行 ID を含める）
| 行 | 変わる内容 | 検証 |
|---|---|---|
| PS-01 | advance 後の NETWORK 取得失敗 → `Errored(FetchFailed)`・`queue.current` は次のまま・自動で進まない・player 停止（現行: `errorMessage` のみで session 概念なし） | T-T5 |
| PS-02 | PS-01 の状態から手動 `retry()` → `startEpisode(queue.current)` を再実行 | T-T5 |
| PS-03 | advance 後にオフライン未キャッシュ → network 取得なし・`Errored(SourceUnavailable)`・文言「オフライン」 | T-T6 |
| PS-05 | `Errored` / `Stopped` / `NothingPlaying` では位置同期を送らない（現行は `_currentPodcast ?: return` が代替） | T-T19 |
| PS-05b（SG-X4） | 一時停止中は周期送信しない。pause への遷移時に 1 回送る | T-T19b |
| PS-06（SG-X1） | 完聴順序 = `markCompleted`（1 セッション 1 回）→ `updatePlaybackPosition(duration)` 1 回 → `advance`（現行: `markCompleted` → advance → 次 play の `stopInternal` が現在位置を送る） | T-T8 |
| PS-08 | セッション速度は既定速度で開始し、セッション中の変更は既定速度を書き換えない（現行: 既定速度を player に適用していない） | T-T4 |
| CI-T4（resume 適用） | 開始位置に `resolveResumePosition` の値を適用（`resume > 0` のとき `seekTo`）。CACHED は一覧 DTO、NETWORK は fresh DTO の値 | T-T4 |
| CI-T7 | `PlaybackQueue.init` が不変条件 1〜3 違反で throw、`setQueue` は先勝ち dedupe（Q-01〜Q-32 は不変） | T-T7 |
| CI-T9 | CACHED 経路で `Failed(Source\|Decode)` → `invalidate(id)` → `Errored(Player)`、次回は NETWORK | T-T9 |
| CI-T15 | `setDefaultPlaybackSpeed` は 8 段以外を拒否して直前の妥当値を維持 | T-T15 |

PS-04（INV-P1）は A-S2a の述語を Coordinator の全公開操作後に assert する形で本 slice でも検証する。**PS-07 は対象外**（学習サイクル。`PodcastStatusBadge` の gate は現状維持）。

## 対象（android サブモジュールのみ。ファイル単位）
**変更（main）**
1. `core/PlaybackQueue.kt`: `init` で不変条件 1〜3（id 一意・`currentIndex` 範囲内・空なら null）を `require`。`setQueue` は入力を先勝ちで dedupe してから構築。他の公開操作は不変。
2. `podcast/PlaybackConstants.kt`: `speeds: List<Double>`（0.5〜2.5 の 8 段）に統一。`PlayerController.setSpeed(Float)` への変換は `PodcastViewModel` の境界で行う。追随: `AudioPlayerSection.kt:283`（型追随のみ）・`PodcastViewModel.setSpeed` の引数型・`PodcastViewModelTest.kt:401-403`。
3. `preferences/DataStorePreferencesStore.kt`: `setDefaultPlaybackSpeed` は `PlaybackConstants.speeds` に無い値を拒否し直前の妥当値を維持（`AuthViewModel.syncPreferences` の server 同期経路も通る）。`InMemoryPreferencesStore` も同じ規則。
4. `network/AudioCacheManager.kt`: `invalidate(id)` を追加（`remove` と同じ削除だが意味を分ける）。
5. `podcast/PodcastViewModel.kt`（PlaybackCoordinator）: 追加 `session: StateFlow<PlaybackSession>`・`nowPlaying: StateFlow<NowPlaying?>`（`episodeId` / `displayTitle` / `japaneseIntroText` / `segments` / `vocabulary` / `quiz` / `difficulty` の 7 field。`NothingPlaying` と `Errored(episodeRef)` で `null`）・`retry()`・`stopForSubjectLeave()`。`PreferencesStore` をコンストラクタ注入（既定速度の読み手）。`play` / `playNow` / `playNext` / `addToQueue` / `handlePlaybackEnded` / `stopInternal` / `startPositionSync` / `syncPosition` を `startEpisode` / `onEnded` / 送信条件に置き換える。完聴の検知は `playerController.state` の `Ended` を購読（`onPlaybackCompleted` は登録しない）。`Errored` の `ref` は prepare 前の失敗（`NotPlayable` / `SourceUnavailable` / `FetchFailed`）では `episodeRef`、`Player` では `episode`。**TP-A1（暫定互換）**: `currentPodcast: StateFlow<PodcastResponse?>` は `session` からの派生値（`nowPlaying` と同じ非 null 条件で DTO を返す）として残し、`_currentPodcast` への書込は 1 箇所（session 適用関数）だけにする。owner: user、導入: A-S2b、削除条件: UI 3 ファイルと `PodcastViewModelTest` が `nowPlaying` / `session` だけを読むようになった時（A-S2c）。`keepCurrentPodcast` 引数は `Stopped` が代替するため呼出 0 になる（シンボル削除は A-S2c）。
6. `di/AppContainer.kt`: `PodcastViewModel` へ `preferencesStore` を渡す。`onSubjectLeave` ラムダに `_podcastViewModel.stopForSubjectLeave()` を独立 try/catch で追加（順序: 停止 → `cancelDownloadsAndClearCache` → FCM。A-S0 で保留していた再生停止部分）。

**変更（test）**: `podcast/PodcastViewModelTest.kt`（T-T20 を baseline に先に追加。変更行に該当するテストだけを反転し、反転理由に行 ID を書く）、`core/PlaybackQueueConformanceTest.kt`（T-T7 追加。既存 32 件不変）、`preferences/DataStorePreferencesStoreTest.kt`（T-T15）、`network/AudioCacheManagerTest.kt`（T-T9 の `invalidate`）。`stopForSubjectLeave()` は `PodcastViewModelTest` で観測する（位置同期 1 回 → `stop()` → `queue` 空 → `NothingPlaying` → `nowPlaying == null`。SL-01 の再生停止部分。`AppContainer` の配線は unit テスト対象外）。`FakePodcastApi`（A-S1）と `FakePlayerController.setState`（A-S2a）を使う。

**変更しない**: `AudioPlayerSection` / `PodcastScreen` / `QueueSheet` の `currentPodcast` 読み（TP-A1 で動く。読み替えは A-S2c）、`SettingsScreen.kt:1361 PLAYBACK_SPEEDS`（5 段は 8 段の部分集合なので CI-T15 と両立。削除は A-S2c）、`PlayerController.onPlaybackCompleted` の宣言と `ExoPlayerController` の発火（TP2。削除は A-S2c）。

## 契約（CI → T の対応）
| CI | T-T |
|---|---|
| CI-T4（resume・既定速度。PS-08） | T-T4: `FakePlayerController` の `seekTo` / `setSpeed` 引数。既定速度 1.5 でセッション中 2.0 へ変更 → 次エピソードは 1.5、`PreferencesStore` は 1.5 のまま |
| CI-T5（INV-P1・PS-01・PS-02） | T-T5: 完聴 → advance → NETWORK 失敗の系列。`queue.current.id == session.ref.id`、`Errored(FetchFailed)`、`retry()` が再実行 |
| CI-T6（PS-03） | T-T6 |
| CI-T7 | T-T7: 不正構築 3 例（index 範囲外・空で index 0・id 重複）と `copy` が throw、`setQueue([a,a,b], 0)` → `[a,b]` |
| CI-T8（PS-06） | T-T8: `FakePodcastApi` の呼出列。同一セッション内で `Ended` を 2 回注入しても `markCompleted` は 1 回 |
| CI-T9 | T-T9 |
| CI-T15 | T-T15: 境界値（0.0・3.0・1.0001）を拒否し直前値を維持 |
| CI-T19（PS-05・PS-05b） | T-T19: `Errored(FetchFailed)` 後に timer を進めても `updatePlaybackPosition` なし。T-T19b: pause 後に timer を進めても pause 時の 1 回以外なし |
| CI-T20（baseline） | T-T20: 2 回連続 `playNow` 後の `updatePlaybackPosition` の id が最後の episode のみ |
| CI-T1 | T-T1（再確認）: Coordinator 経由（`FakePlayerController.setState` ＋ start / playNow / retry / advance）で A-S2a と同じ 11 遷移を観測 |

## 特性テスト（baseline。着手前に green。8 ファイル）
`podcast/PodcastViewModelTest`（52）、`core/PlaybackQueueConformanceTest`（32）、`core/PlaybackSourceResolverTest`（4）、`network/AudioCacheManagerTest`（14）、`podcast/PlaybackMetadataTest`（5）、`podcast/PodcastStatusBadgeTest`（5）、`settings/SettingsViewModelTest`（24）、`preferences/DataStorePreferencesStoreTest`（5）。加えて **T-T20 を先に追加**して green にする。

## 手順
1. baseline 8 ファイル＋T-T20 green を記録。
2. T-T7 → RED → `PlaybackQueue` → GREEN（既存 32 件不変）。
3. T-T15 → RED → `DataStorePreferencesStore` / `InMemoryPreferencesStore`・`PlaybackConstants.speeds: List<Double>` → GREEN。
4. T-T9 → RED → `AudioCacheManager.invalidate` → GREEN（Coordinator 側は 5 で）。
5. T-T4・T-T5・T-T6・T-T8・T-T19・T-T19b・T-T1（Coordinator）・PS-04 → RED → `PodcastViewModel` を `PlaybackSession` 経由へ差し替え（TP-A1 の派生 `currentPodcast` を含む）→ GREEN。8 特性テストが green のままであることを各段で確認。
6. `stopForSubjectLeave()`（`PodcastViewModelTest` で SL-01 の再生停止部分を観測）→ RED → 実装 → `AppContainer.onSubjectLeave` へ追加 → GREEN。
7. UV3（エミュレータ `newslisten_e2e`）: resume 位置の適用・既定速度の適用・次エピソード失敗時の停止表示・`onPlayerError` → `Failed`（CI-T2b）の 4 項目を手動観測し記録。
8. 1 slice = 1 PR。temporary path: TP-A1（本書 5）・TP2（`onPlaybackCompleted`。owner user、導入 2026-09-16 Spec、削除条件 = `PodcastViewModel` が `state.Ended` だけを購読する状態＝本 slice で満たす。削除は A-S2c）。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。
- T-T1・T-T4〜T-T9・T-T15・T-T19・T-T19b・T-T20 が `verifies: CI-T*` をテスト名またはコメントに持ち、PS-01・PS-02・PS-03・PS-04・PS-05・PS-05b・PS-06・PS-08 がテスト名に含まれる。
- 既存 `PlaybackQueueConformanceTest` 32 件（Q-01〜Q-32）が名前・期待値とも不変。
- 反転した既存テストはすべて上記変更行のいずれかを理由に持つ（PR 本文に「テスト名 → 行 ID」の表）。
- `grep -n 'onPlaybackCompleted' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0（登録しない）。`grep -n 'keepCurrentPodcast' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` の一致は宣言行のみ（呼出 0）。`grep -rn '_currentPodcast.value =' app/src/main` = 1（session 適用関数）。
- `grep -rn 'PlaybackConstants.speeds' app/src/main` の要素型が Double（`SettingsScreen` の `PLAYBACK_SPEEDS` は本 slice では触らない）。
- grep oracle 回帰なし: `error("` = 0、`code == 401` = 0。
- レビュー観点: `PlayerController` interface に Media3 の型が無い。`PodcastViewModel` の再生 use case が `PodcastApi` だけに依存（A-S1 の境界維持）。`core/` が `kotlinx.coroutines` を import しない。
- UV3 の 4 項目の観測結果が記録されている。

## 禁止事項 / scope 外
- 上記「変更行」以外の挙動を変えない（Q-01〜Q-32・`resolvePlaybackSource`・語彙登録・クイズ中継・ダウンロード・`PodcastStatusBadge` の gate・PS-07）。
- UI 3 ファイルの `currentPodcast` → `nowPlaying` 読み替え、`_currentPodcast` / `keepCurrentPodcast` / `onPlaybackCompleted` / `SettingsScreen.PLAYBACK_SPEEDS` の削除はしない（A-S2c）。
- `_currentPodcast` を owner にしない（書込 1 箇所・session からの派生のみ）。
- 主体別キャッシュ・主体離脱の順序変更・logout ヘッダはしない（A-S4）。背景遷移時の位置同期は Android 現行に無く追加しない（Spec CI-T19 の契機は 15 秒・停止直前・完聴時の 3 つ）。
- 棄却済み案を持ち込まない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 上記 grep 5 本の結果と UV3 の記録を PR 本文に貼る。

## 記録
- 反転した特性テストの一覧（テスト名 → 行 ID）を PR 本文と `android/docs/trial-log/` に残す。
- 親 docs への返却事項: 共有仕様 §4.4 の保留（PS-01〜PS-06・PS-08・android A-S2b）を解除できる旨。PS-07 は未追随のまま（学習サイクル）。
