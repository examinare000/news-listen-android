# news-listen-android Implementation Spec — 再生セッション・認証失効・テスト基盤（design mode）

日付: 2026-09-16 ／ mode: design（read-only、実装は別フェーズ）／ owner: user ／ decision_maturity: **approved**（2026-09-16 user 承認 Q20。P9 事前実装ゲート = revise → 必須 10・推奨 6 を反映済み。SG-R13〜SG-R18 は Q14〜Q19 で確定）
入力: `docs/research-reports/2026-09-16-code-design-review.md`（finding RF*・§8 人間判断 SG-R1〜SG-R12・着手順）と同ディレクトリの Function package（F*/G*/IV*/OB-C*/CI*/LF*/RO*）。
位置づけ: 共有再生仕様 `docs/design/shared-playback-spec.md` §2/§6 の android 実装に対する **target 版設計**。web の Spec（`web/docs/design/2026-09-16-implementation-spec-domain-model.md`）と同じ判断順・同じ語（term ledger）を使い、platform 固有の差だけを書く。

> 設計原則（本書の判断順）: actor の目的 → use case → その判断に必要な概念・不変条件 → 契約 → カプセル（公開操作と隠す技術）→ 依存方向 → 移行。pattern 名・class 数は成果にしない。1 実装しかない箇所に factory / Strategy を作らない（Boundary RO1〜RO8 を踏襲）。設計書に実装コードを載せない。


> **追記（2026-09-16・共有仕様 §6.7 の確定による上書き）**: 親 docs `shared-playback-spec.md` §6.7 の Selection Gate が user 判断で確定し、本書の次の記述を上書きする（本書は改訂せず、この追記と各 slice の order `docs/plan/2026-09-16-design-review-refactor/` を優先する）。
> - SG-X1: 完聴時に `duration` を明示送信する（SG-R14 のとおり。3 platform 共通で確定）。
> - SG-X3: 主体離脱は **cleanup 完了を待たず**、`sessionStore.clear()` → `Unauthenticated` → `onSubjectLeave()` の順にする（§3.2 の遷移表の事後条件は同じ。順序だけ改める。S0 で実施）。
> - SG-X4: **一時停止中の 15 秒 PATCH はやめる**（SG-R9「現行維持」を置換。backend は位置 PATCH 到達ごとに `listeningDays` を書くため、U1 は「依存する」で解消。CI-T19 に一時停止中の非送信を追加）。

## 0. Decision frame と function_plan

```yaml
decision_frame:
  mode: design
  requested_outcome: Implementation Spec（use case catalog・context 分割・model・契約・capsule・移行・検証計画）
  decision_owner: user
  mutation_authorized: false
  in_scope: [podcast/ auth/ network/ core/ preferences/（値域のみ） di/ の責務再配置, 契約と test obligation, 移行手順, CI 分割]
  out_of_scope: [backend 契約変更, iOS/web の実装, 意匠, 学習機能・設定画面・admin の model（§8 保留: RF6 全面 / RF8 / RF9 / RF10 / RF2）, 共有仕様の改訂文面そのもの（本書は改訂内容を「要求」として書く）]
  reversibility: reversible（slice 単位・特性テストで保護）
  public_contract_change_allowed: false   # backend API・共有仕様 §2 の QueueState 表現・Q-01〜Q-32 は不変。共有仕様 §6 系の改訂は SG-R17（新節＋3 platform 合意 gate）
  decision_maturity: {status: approved, owner: user, scope: [android/], evidence_status: confirmed, approval_evidence: ["review §8（SG-R1〜SG-R12 satisfied、P8 confirmed）", "本書 §8 SG-R13〜SG-R18（2026-09-16 Q14〜Q19）"]}
function_plan:
  - {function: architecture, run_if: "context 分割・data authority・依存方向", status: completed, note: "§2・§5。SG-R1/R2/R5/R6/R8 の決定を target に反映"}
  - {function: completeness, run_if: "model の概念・状態・失敗", status: completed, note: "§3。G1〜G10, G16〜G19 / IV1〜IV4, IV7〜IV9 を target model で閉じる。G11〜G15（失敗の意味型全面・設定・UiState）は保留 slice へ"}
  - {function: contract, run_if: "capsule の公開操作", status: completed, note: "§4。既存 CI* を再利用し、target 固有は CI-T*"}
  - {function: boundary, run_if: "capsule の interface / implementation 分離", status: completed, note: "§5。LF1〜LF3, LF6, LF8〜LF15 の解消先を明示。LF16〜LF21 は保留"}
  - {function: change_safety, run_if: "既存挙動の変更", status: completed, note: "§6。slice・特性テスト・temporary path"}
  - {function: discovery, status: not_applicable, not_applicable_reason: "用語は §1.2 の term ledger で確定（共有仕様・web Spec 由来）"}
```

## 1. Use Case catalog と用語

### 1.1 actor と目的

| actor | 目的（product value） |
|---|---|
| Listener（聴く人） | 移動中・オフラインでも英語ニュース音声を途切れず聴き、前回の続きから、自分の速度で再開できる |
| Account owner | 自分の認証状態が正しく（失効したら未認証に、通信断ではログアウトされずに）保たれる |
| System（MediaSession・FCM） | ロック画面操作・通知・トークン登録が認証状態と整合する |
| 開発者 | 再生・認証の変更を 1 箇所で行い、production 経路を通るテストで守れる |

### 1.2 Use Case（UC）

| UC | actor | 内容 | 主要な判断（=ドメインルール） | context |
|---|---|---|---|---|
| UC-P1 エピソードを再生する | Listener | 一覧行タップ / 次に再生 / キューに追加 | 再生可能性（PodcastStatusBadge.None）、音源（cached / network / unavailable）、キュー挿入規則（現状維持）、**resume 位置（SG-R4）**、**開始速度 = 既定速度（SG-R2）** | Playback ← Catalog |
| UC-P2 再生を操作する | Listener | play / pause / seek / ±秒 / 速度 / 停止 | seek の clamp、速度の値域（8 段・SG-R3）、セッション速度の保持 | Playback |
| UC-P3 連続再生 | Listener | ended → 完聴記録 → 次へ or 停止 | 完聴記録の順序（completed → 位置 → advance）、次の取得失敗は**停止＋手動再試行（SG-R7）** | Playback |
| UC-P4 キューを編成する | Listener | 追加・次に再生・削除・並べ替え | 共有仕様 §2（Q-01〜Q-32）＋不変条件の構築時保証（SG-R10） | Playback |
| UC-P5 オフライン保存 | Listener | 保存・削除・全削除 | 成功応答のみ保存、破損キャッシュは再生失敗時に無効化（RF15） | Playback（OfflineLibrary） |
| UC-P6 再生位置の同期 | Listener | 15 秒ごと＋停止直前に server へ | 一時停止中も送る（SG-R9 現行維持）、完聴時の順序 | Playback |
| UC-A1 認証状態の解決 | Account owner | 起動時 refresh / login / passkey / logout / **失効** | 失効（unauthorized）のみトークン破棄、到達不能は保持＋再試行（SG-R6） | Account |
| UC-A2 主体が離れる | Account owner / System | logout・失効の事後条件 | 音声キャッシュ・FCM トークン・**再生状態**を消す。preferences は消さない（SG-R5） | Account → Playback / Notifications |
| UC-S1 ロック画面・通知 | System | MediaSession が Player を共有 | 所有権は現状維持（RF17 記録のみ） | Platform |

### 1.3 term ledger（web Spec §1.3 と同じ語。android 固有の別義だけ追記）

| term | 意味 | 区別する別義（android） | 正本 |
|---|---|---|---|
| Episode | 聴く対象（`PodcastResponse` DTO をドメインで読んだもの） | DTO そのもの | Catalog（本書では DTO のまま。decode 分離は保留 slice） |
| 再生可能（Playable） | `PodcastStatusBadge.from(podcast)` が `None` | 生成中（Processing）・失敗（Failed） | `podcast/PodcastStatusBadge.kt` |
| 現在再生中 | `Queue.current`（共有仕様 §2.1 不変条件 4） | `_currentPodcast`（削除）、行 UI の「選択中」、ExoPlayer の MediaItem | Playback Queue |
| 完聴（completed listen） | `STATE_ENDED` 到達の事象（`markCompleted`） | 生成完了（`status == "completed"`） | Playback |
| 既定速度 | 新しい再生の初期速度（設定・永続・server 同期） | セッション速度（今回の再生・非永続） | Preferences ／ Playback |
| resume 位置 | server 保存位置から `resolveResumePosition` で導いた開始位置 | 15 秒同期で書く位置 | Playback（`core/`） |
| 認証済み | `AuthState.Authenticated(user)` | 保存トークンの存在 | Account |
| 失効（unauthorized） | 保存トークンで API が 401 を返した事象 | 到達不能（NetworkError）・server 障害（5xx） | Account / Platform |
| 主体が離れる | logout または失効による `Authenticated → Unauthenticated` の遷移 | — | Account |

## 2. Bounded context と依存方向（Architecture target）

```text
ui/ (Compose Screen)                  …… 画面。ViewModel の状態を描き操作を呼ぶ。ルールを持たない
  ↓
podcast/ auth/ (ViewModel = use case orchestration)  …… UC 単位。capsule を組み合わせる。android.* 依存 0（現状維持）
  ↓ owns                                ↑ implements
core/ (純粋 model・policy: Queue / PlaybackSource / ResumeRule)   ←  network/ podcast/ExoPlayerController preferences/（port の adapter）
di/AppContainer (composition root)     …… adapter を生成し capsule を配線。関数注入で層を切る（現状の pattern を踏襲）
```

| context | purpose | 所有する概念（source of truth） | 置き場（target） |
|---|---|---|---|
| **Playback** | 聴き続ける | `PlaybackSession`（再生セッション状態 union・現在の Episode DTO・セッション速度・resume 適用）, `Queue`（順序と現在位置）, `PlaybackSource`, `ResumeRule`, `OfflineLibrary`（AudioCacheManager） | `core/`（純粋）、`podcast/PlaybackSession.kt`（新）、`podcast/PodcastViewModel.kt`（orchestration のみ） |
| **Account** | 誰であるか | `AuthState`（既存 sealed。`Unknown` を「判定保留」として再利用）, 失効 event, 主体離脱の事後条件 | `auth/` |
| **Preferences** | 自分の使い方 | 既定速度・既定難易度・週目標（値域は本書では速度のみ確定: 8 段） | `preferences/` |
| **Notifications** | 通知 | FCM トークンの登録／解除 | 現状維持 |
| **Platform** | 技術境界 | `ApiClient`（＋失敗型 `ApiException`）, `PodcastApi`（狭い port・新）, `PlayerController`（状態 union へ拡張）, `SessionStore`, `AuthInterceptor`（401 検出を追加）, `AudioCacheManager` | `network/`, `podcast/ExoPlayerController.kt` |

**依存方向の禁止事項（prohibited_structures）**
- `core/` は `android.*`・`network`・`kotlinx.coroutines` を import しない（現状維持。`ResumeRule` も純関数）。
- `podcast/PodcastViewModel` の**再生 use case**（`startEpisode` / 完聴 / 位置同期 / DL）は `PodcastApi`（5 メソッド）だけを知る（SG-R8）。語彙登録（`fetchVocabulary` / `saveVocabulary`）とクイズ中継（`submitQuizAnswers`）の 3 操作は RF8 保留のため S2 では分離せず、既存の `ApiClient` 経由のまま残す（gate 指摘 3。完全分離は RF8 の slice で）。
- `network/AuthInterceptor` は `auth/` を import しない。401 の通知は関数注入 `onUnauthorized: () -> Unit`（`tokenProvider` と同型）。**発火条件は「三点一致かつ `Authorization` ヘッダを付与した（tokenProvider が非 null だった）リクエストの 401」のみ**。トークン無しの 401（login / passkey login の資格情報誤り、`AuthViewModel.kt:137,151-156`）では呼ばない（gate 指摘 1: さもないとパスワード誤入力で主体離脱 cleanup が走る）。
- `PlayerController` の interface に Media3 の型を出さない（`player: Player` の公開は `ExoPlayerController` 具象に留め、`PlaybackService` だけが使う。RF17 現状維持）。

**port を置く根拠（abstraction gate）**: 追加するのは 1 つだけ。

| port | 根拠 | 既存の萌芽 |
|---|---|---|
| `PodcastApi`（fetchPodcast / fetchPodcasts / updatePlaybackPosition / markCompleted / downloadAudio） | SG-R8。再生 RED テストの Fake が 5 メソッドで書ける（QL2）。`OkHttpApiClient` が実装し、`ApiClient` が継承する | `network/LearningApi.kt`・`VocabularyTestApi.kt` と同方式 |

`PlayerController` / `SessionStore` / `PreferencesStore` / `FileSystem` は既存 port をそのまま使う。consumer 別の全 port 分割（RO4）・Strategy・Clock port は作らない。

## 3. ドメインモデル（Completeness target）

### 3.1 Playback

**PlayerController の再生状態（境界の出力型）** — 排他 union。`isPlaying` は派生値として残す（段階移行のため）。

| 状態 | 保持する値 | 遷移（Media3 イベント → 状態） |
|---|---|---|
| `Idle` | — | `stop()` / `STATE_IDLE` |
| `Loading` | — | `prepare()` 直後〜`STATE_READY` 前（`STATE_BUFFERING` を含む） |
| `Playing` | position, duration | `onIsPlayingChanged(true)` |
| `Paused` | position, duration | `onIsPlayingChanged(false)` かつ `STATE_READY` |
| `Ended` | duration | `STATE_ENDED` |
| `Failed(reason)` | `reason: Source \| Decode \| Network \| Unknown(code)` | `onPlayerError`（**新規購読**。`PlaybackException.errorCode` から分類） |

**PlaybackSession（use case 側の再生セッション。`PodcastViewModel` から分離する非 Android クラス）**

| 状態 | 保持する値 | 遷移（コマンド／イベント） |
|---|---|---|
| `NothingPlaying` | — | `start(episode, resume, speed)` → `Starting` |
| `Starting` | episode（fresh DTO）, resumePosition, speed | player `Loading→Playing/Paused` → `Active`／player `Failed` → `Errored` |
| `Active` | episode, speed | player 状態は `PlayerController` に委譲（派生）／`ended` → `Completed`／player `Failed` → `Errored` |
| `Completed` | episode | Coordinator が完聴記録 → `advance` → `Starting(next)` か `Stopped` |
| `Stopped` | episode（最後に聴いたもの。キューが尽きた） | `start` |
| `Errored` | episode（取得前なら `episodeRef: {id}`）, `reason: SourceUnavailable(offline) \| FetchFailed(ApiException) \| NotPlayable(gate) \| Player(Failed.reason)` | `retry()`（手動）→ `Starting`／`start` |

**不変条件 INV-P1（SG-R1）**: `session` が `NothingPlaying` でないとき `session.episode.id == queue.current?.id`。`Stopped` のとき `queue.currentIndex` は末尾のまま（Q-18 不変）で、`session` が「停止」を表す。UI が「何が再生中か」を問う唯一の入口は `PodcastViewModel.nowPlaying: StateFlow<NowPlaying?>`。field は実読み手（`AudioPlayerSection.kt:75,101,115,125-126,142`、`PodcastScreen.kt:160,204`、`QueueSheet.kt:58,83`）から逆算した **episodeId / displayTitle / japaneseIntroText / segments / vocabulary / quiz / difficulty** の 7 つ（`durationSeconds` は player が正本なので含めない。gate 指摘 7）。`session` が `NothingPlaying` または `Errored(episodeRef のみ)` のとき `null`（空のプレイヤーを出さない。`Errored` の文言は `session` を読む error 表示経路が担う）。`_currentPodcast`（`PodcastViewModel.kt:83-86`）は削除し、`AudioPlayerSection.kt:65` / `PodcastScreen.kt:61,160,204` / `QueueSheet.kt:58,83` の読み出しを `nowPlaying` へ置換。行 UI の「再生中」ハイライトは `nowPlaying?.episodeId == podcast.id`（意味は「現在再生中」。一時停止でもハイライトは維持＝現状の観測挙動を保存）。

遷移表の分母（T-T1 用に固定）: NothingPlaying→Starting, Starting→Active, Starting→Errored, Active→Completed, Active→Errored, Active→Starting(playNow), Completed→Starting(advance), Completed→Stopped, Stopped→Starting, Errored→Starting(retry), Errored→Starting(start) の **11 遷移**。表外（例: Errored→Active、Stopped→Completed）は禁止。

**Queue** — 共有仕様 §2 の `PlaybackQueue<T>` をそのまま採用。**`init` で不変条件 1〜3 を検査し違反は `require` で throw**（SG-R10、programmer error）。公開操作は §2 どおり正規化し throw しない。ただし現行 `setQueue`（`core/PlaybackQueue.kt:39-44`）は入力の id 重複を除去しないため `init` の一意性検査と衝突する（gate 指摘 9）→ `setQueue` は入力を **先勝ちで dedupe** してから構築する（不変条件 1 は「すべての操作の前後で保たれる」と規定されており §2.4 と矛盾しない。Q-04〜Q-07 は重複なし入力なので不変）。Q-01〜Q-32 不変。`copy` も `init` を通るため、`PodcastViewModel` 内の `_queue.value = ...` はすべて検査済みの値になる。

**ResumeRule（`core/`、純関数、SG-R4）** — `resolveResumePosition(serverSeconds: Double, durationSeconds: Int): Double`。規則: `durationSeconds > 0 && serverSeconds >= durationSeconds − 2` なら 0（完聴済み＝先頭から）、`serverSeconds > 0` ならその値、それ以外 0。iOS `PodcastViewModel.swift:304-307` と同一。共有仕様 §6.2 へ「完聴境界 = 末尾 2 秒」を追記する（**spec 先行**。3 platform 共通規則。web も同規則へ追随するかは web 側判断）。CACHED 経路は `fetchPodcast` を通らないため、resume は一覧 DTO の `playbackPositionSeconds`（一覧取得時点の値）を使う。NETWORK 経路は fresh DTO の値を使う。

**速度（SG-R2 / SG-R3）** — 既定速度は `PreferencesStore.defaultPlaybackSpeed`（Double、値域 = `PlaybackConstants.speeds` 8 段）。`PlaybackSession.start` は `speed = preferences.defaultPlaybackSpeed` で初期化し `playerController.setSpeed` を呼ぶ。以後 `setSpeed` はセッション内で保持。設定画面の `PLAYBACK_SPEEDS`（5 段・`SettingsScreen.kt:1361`）は削除し `PlaybackConstants.speeds` を参照。値域の正本は **Double 1 本**（`PlaybackConstants.speeds: List<Double>`。`PlayerController.setSpeed(Float)` へは境界で変換。gate 推奨）。`DataStorePreferencesStore.setDefaultPlaybackSpeed` は値域外を**拒否して直前の妥当値を維持**（server が 0.0 を返しても無音再生にならない。既定 1.0 への置換は保存済みの妥当値を失うため採らない）。

**PlaybackSource / OfflineLibrary** — `resolvePlaybackSource` は現状維持（§6.1 一致）。`AudioCacheManager` に `invalidate(id)`（削除と同義だが意味を分ける）を足し、CACHED 経路で player が `Failed(Source|Decode)` になったら Coordinator が `invalidate` して `Errored(Player)` へ（次回は NETWORK へ退避。RF15 / OB-C18）。

**PlaybackCoordinator（`PodcastViewModel` 内の非 Android 関数群。`PodcastApi` と port だけに依存）**
- `startEpisode(podcast)`: `playabilityError` → `Errored(NotPlayable)`；`source = resolvePlaybackSource(...)`；`CACHED` → 一覧 DTO＋cachedUri／`NETWORK` → `podcastApi.fetchPodcast`（失敗は `Errored(FetchFailed)`、queue は進めたまま INV-P1 は `episodeRef` で維持）／`UNAVAILABLE` → `Errored(SourceUnavailable)`；`resume = resolveResumePosition(dto.playbackPositionSeconds, dto.durationSeconds)`；`session.start(dto, resume, speed = prefs.defaultPlaybackSpeed)` → `playerController.prepare → setSpeed → seekTo(resume)（resume > 0 のとき）→ play`。
- `onEnded`: `markCompleted(id)`（best-effort、1 セッション 1 回。backend first-write-wins 依存をコメントとテストに明記）→ 位置同期を 1 回（**値は duration**。完聴を「末尾」で記録し、次回 resume は ResumeRule が 0 に写す）→ `queue.advance` → `next` があれば `startEpisode(next)`、無ければ `Stopped`。失敗は `Errored`、`retry()` で `startEpisode(queue.current)`。
- `stopForSubjectLeave()`（UC-A2）: 位置同期 1 回 → `playerController.stop()` → `queue = PlaybackQueue()` → `NothingPlaying`。`AppContainer` の `onLogoutCleanup` から呼ぶ（cancelDownloadsAndClearCache と同列）。
- 位置同期（UC-P6）: 15 秒 timer は現状維持（一時停止中も送る。SG-R9）。**事前条件: session が prepare 済み（`Starting` / `Active` / `Completed`）のときだけ送る**。`Errored` / `Stopped` / `NothingPlaying` では送らない（gate 指摘 2: 現行は `_currentPodcast ?: return`（`PodcastViewModel.kt:503`）が守っており、削除後に `Errored(FetchFailed)` から `updatePlaybackPosition(id, 0.0)` を送ると server の resume 位置を破壊する）。CACHED 経路の resume は一覧取得時点の値なので、別端末で進めた位置より古い可能性がある（stale resume。許容し明記）。

### 3.2 Account

**AuthState** — 既存 sealed（`Unknown | Unauthenticated | Authenticated(user)`）を維持し、**`Unknown` の意味を「判定保留（起動直後 or 到達不能で未確定）」に拡張**。新状態は足さない（web の `unavailable` 相当は `Unknown` + `lastFailure: ApiException?` の別 StateFlow で表す。理由: 4 状態化は MainActivity / AppScaffold の分岐を増やし、`Unknown` 表示（ローディング）に「再試行」導線を足すだけで済む）。

| 遷移 | 条件 | 事後条件 |
|---|---|---|
| `Unknown → Authenticated(user)` | `me()` 成功 | preferences 同期、FCM 登録 |
| `Unknown → Unauthenticated` | トークン無し、または `me()` が **`ApiException.Unauthorized`** | `sessionStore.clear()` |
| `Unknown → Unknown` | `me()` が `NetworkError` / `HttpError(5xx)` / `DecodingError` | トークン保持、`lastFailure` 設定、UI は再試行導線 |
| `Authenticated → Unauthenticated`（失効） | 任意 API が 401（`AuthInterceptor` が検出 → `onUnauthorized`） | `sessionStore.clear()` ＋ **主体離脱の事後条件**（下記） |
| `Authenticated → Unauthenticated`（logout） | `logout()` | 同上（`apiClient.logout()` は best-effort） |

**主体が離れる事後条件（SG-R5、UC-A2）**: 音声キャッシュ全削除・進行中 DL の cancel（既存 `cancelDownloadsAndClearCache`）、FCM トークン解除（既存）、**再生状態の停止と空化**（`stopForSubjectLeave`、新）。preferences は消さない。各手順は独立 try/catch（既存 pattern）。失敗は `CleanupIncomplete(parts)` として `AuthViewModel` の StateFlow に残し観測可能にする（OB-C10）。

**ApiException（Platform）** — `Unauthorized` を追加（`validateResponse` で `code == 401` を写像。既存の `HttpError(401)` 比較 `AuthViewModel.kt:152` は `Unauthorized` catch へ置換）。他の意味型（not_found / conflict / forbidden / server）は保留 slice（RF6）。

**SessionStore** — `save` の失敗を返す（`Boolean` か `Result`）。`login` は保存失敗時 `Authenticated` へ遷移せずエラー表示（OB-C20 / RF16）。`KeystoreSessionStore.clearBrokenState` は `onUnauthorized` と同じ通知経路を使わず（復号失敗は失効ではない）、`load()` が null を返した時点で `refreshAuth` が `Unauthenticated` へ落とす現状のままとする。

### 3.3 保留（境界と obligation のみ）

- Catalog（`Episode` decode・404/409 の意味・quota 期間）: RF6 全面導入は学習サイクル。OB-C11/C12/C14 を obligation として保持。
- Preferences registry（RF9）: 本書は速度の値域だけ確定。週目標・難易度は OB-C15 を保持。
- UiState の排他化（RF10）: OB-C16 / OB-B9 を保持。
- Screen 層の owner 分散（RF8）: `PodcastViewModel` の責務分割は INV-P1 導入後（RO6）。語彙登録・クイズ中継の分離は保留。

## 4. 契約（Contract target）

既存 Contract Package の CI*（`docs/research-reports/2026-09-16-code-design-review/contract-package.md`）を再利用し、target 固有の契約を CI-T* として追加する。テスト仕様 T-T* は Given-When-Then と oracle（公開 API 経由）。

| CI | 対象 capsule | statement（要約） | 由来 | test |
|---|---|---|---|---|
| CI-T1 | PlaybackSession | 状態は §3.1 の union のみ。11 遷移以外は起きない。`Errored` は `Paused` / `Stopped` と区別できる | OB-C1, CI-P02, SG-R7 | T-T1: `FakePlayerController` の状態注入（Loading→Playing / Failed / Ended）と Coordinator 操作（start / playNow / retry / advance）の入力列で 11 遷移を観測（分母 11。各遷移 1 ケース） |
| CI-T2a | PlayerController（契約） | `Failed(reason)` は `Paused` / `Idle` と区別できる状態値。`reason` の分類表: `ERROR_CODE_IO_*` → Network、`ERROR_CODE_DECODING_*` / `PARSING_*` → Decode、`ERROR_CODE_IO_FILE_NOT_FOUND` / `BAD_HTTP_STATUS` → Source、他 → Unknown(code)（分類の正本は実装時に Media3 1.5 の `PlaybackException` 定数表で確定。UK1） | OB-C2, CI-P03, G2 | T-T2a（JVM: `FakePlayerController` の状態注入と純関数 `classifyPlaybackError(code)` の表駆動） |
| CI-T2b | ExoPlayerController（配線） | `onPlayerError` が購読され `Failed` へ写像される | 同上 | **JVM 不可**（UV4）。androidTest 不在のため未検証として記録し、S2 の実機観測（UV3）で 1 回確認 |
| CI-T3 | ResumeRule | `resolveResumePosition` の表: (server 0, dur 600)→0、(120, 600)→120、(598, 600)→0、(599, 600)→0、(120, 0)→120、(−5, 600)→0 | OB-C3, CI-S04/S05, SG-R4 | T-T3（表駆動・`core/` の conformance と同形式） |
| CI-T4 | Coordinator | 開始後の position は resume に等しく、speed は既定速度に等しい。CACHED 経路は一覧 DTO の位置、NETWORK 経路は fresh DTO の位置を使う | OB-C4, CI-P27, SG-R2 | T-T4: `FakePlayerController` の `seekTo` / `setSpeed` 引数を観測 |
| CI-T5 | Coordinator | INV-P1。完聴→advance→NETWORK 失敗の系列で `queue.current.id == session.episodeRef.id` かつ `Errored(FetchFailed)`。`retry()` が `startEpisode(queue.current)` を再実行 | OB-C5, CI-P13, SG-R1, SG-R7 | T-T5（既存 PodcastViewModelTest の系列を拡張） |
| CI-T6 | Coordinator | `UNAVAILABLE` → network 取得なし、`Errored(SourceUnavailable)`。文言は「オフライン」 | CI-S03, OB-C6 | T-T6 |
| CI-T7 | Queue | `init` は不変条件 1〜3 違反で throw。公開操作（`setQueue` の dedupe を含む）は正規化し throw しない。Q-01〜Q-32 不変 | OB-C17, CI-Q01/Q01b/Q02, SG-R10 | 既存 conformance 32 件（不変）＋ T-T7: 不正構築 3 例（index 範囲外・空で index 0・id 重複）で throw、`copy` も同様、`setQueue([a,a,b], 0)` → `[a,b]` |
| CI-T8 | Coordinator / PodcastApi | 完聴時の順序 = `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。同一 episode の `markCompleted` は 1 セッション 1 回 | CI-N07/N08b, OB-C19, RF13 | T-T8: `FakePodcastApi` の呼出列 |
| CI-T9 | OfflineLibrary | CACHED 経路で player `Failed(Source|Decode)` → `invalidate(id)` → `isCached=false`、次回 `resolvePlaybackSource` は NETWORK | OB-C18, CI-N17, RF15 | T-T9 |
| CI-T10 | AuthViewModel | `refreshAuth`: `Unauthorized` のみ `clear` + `Unauthenticated`。`NetworkError` / `HttpError(5xx)` / `DecodingError` は `Unknown` 維持＋トークン保持＋`lastFailure` | OB-C7, CI-A03, SG-R6 | T-T10（既存 `AuthViewModelTest.kt:95` を反転） |
| CI-T11 | AuthInterceptor | 三点一致かつ `Authorization` を付与したリクエストの応答が 401 のとき `onUnauthorized` を 1 回呼ぶ。トークン無し（login / passkey）の 401・非一致 host の 401 では呼ばない。ヘッダ付与契約（CI-A13/A14）は不変 | OB-C8, CI-A12, SG-R6 | T-T11（既存 `AuthInterceptorTest` の `FakeChain` に応答を持たせる） |
| CI-T12 | AuthViewModel | 失効通知後: `Unauthenticated`、`sessionStore.load()==null`、cleanup 3 手順（音声・FCM・再生停止）が呼ばれる。logout も同じ事後条件 | OB-C9, CI-A18, SG-R5 | T-T12: Fake の呼出観測（PodcastViewModel の `stopForSubjectLeave` を含む） |
| CI-T13 | AuthViewModel | cleanup の一部が失敗しても残りは実行され、`CleanupIncomplete(parts)` が観測可能。再実行で同じ事後条件 | OB-C10, CI-A17 | T-T13 |
| CI-T14 | SessionStore / AuthViewModel | `save` 失敗は呼出元へ返り、`login` は `Authenticated` へ遷移しない | OB-C20, CI-A07/A11, RF16 | T-T14（`InMemorySessionStore` に失敗注入） |
| CI-T15 | PreferencesStore | `setDefaultPlaybackSpeed` は 8 段以外を拒否（既定へ正規化）。server 同期経路（`AuthViewModel.syncPreferences`）も通る | OB-C15, CI-X02, CI-A20, SG-R3 | T-T15（`DataStorePreferencesStoreTest` に境界値） |
| CI-T16 | ApiClient / PodcastApi | production interface に throwing default が無い（`OkHttpApiClient` が全メソッドを override、`error("` の grep 0）。`PodcastViewModel` の再生 5 操作は `PodcastApi` 経由（語彙・クイズ 3 操作は `ApiClient` のまま） | CI-N18, SG-R8, N3 | T-T16: 構造検査（grep）＋ `PodcastApi` の 5 メソッドを `OkHttpApiClientTest`（MockWebServer）で経路確認 |
| CI-T17 | PodcastViewModel 公開面 | `currentPodcast` は存在せず、UI は `nowPlaying` だけを読む | SG-R1, LF9, LF11 | T-T17: 構造検査（grep `currentPodcast` = 0）＋ `QueueSheet` の両者揃い条件が消える |
| CI-T19 | Coordinator | 位置同期（15 秒・停止直前・完聴時）は session が `Starting` / `Active` / `Completed` のときだけ送る。`Errored` / `Stopped` / `NothingPlaying` では `updatePlaybackPosition` を呼ばない | gate 指摘 2, CI-P24 | T-T19: `Errored(FetchFailed)` 後に timer を進めても `FakePodcastApi.updatePlaybackPosition` が呼ばれない |
| CI-T20 | Coordinator | `play`/`playNow` の連続呼出は直列化され、位置同期 timer は常に 1 本（旧 timer が孤児化しない）。現行 `playMutex` / `syncJob` の挙動を pin | gate 指摘 8（現行テストに pin なし） | T-T20: 特性テストとして **S2 の baseline に先に追加**（2 回連続 `playNow` 後の `updatePlaybackPosition` の id が最後の episode のみ） |
| CI-T21 | AuthViewModel | `login` が `Unauthorized` を受けたとき文言は「ユーザーIDまたはパスワードが正しくありません」、`onUnauthorized` は発火しない | gate 指摘 4 | T-T21（既存 `AuthViewModelTest` の login 401 ケースを `Unauthorized` へ置換） |
| CI-T18 | CI | `testDebugUnitTest` / `lintDebug` / `assembleDebug` が独立ステップで、JDK は toolchain 17 | SG-R11, RF11 | ci.yml / build.gradle.kts の差分レビュー（テスト不可） |

coverage（design 時点）: CI-T 22 件（T1, T2a, T2b, T3〜T21）のうち JVM で検証可能な test 仕様あり 21、JVM 不可 1（CI-T2b）。実行はすべて未。既存 CI のうち target で `met` へ変わる見込み: Q01/Q01b/Q02, P02/P03/P06/P13/P27, S03/S04/S05, A03/A07/A11/A12/A17/A18, N07/N17/N18, X02, A20（計 22 件）。**変えない**: A08/A10（Keystore、UV4）, P24（SG-R9 現行維持で met のまま）, X01/X06（週目標・難易度は保留）, N02/N09/P28（RF6 保留）。

## 5. カプセルと公開操作（Boundary / code design）

```yaml
code_design:
  capsules:
    - {id: CP1, name: PlayerController（拡張）, owns: [再生状態 union, position/duration/speed, Media3 error の分類], hides: [ExoPlayer, Handler/Looper, foreground service 起動, 500ms ポーリング], note: "isPlaying は派生値として残す"}
    - {id: CP2, name: Queue（core/PlaybackQueue）, owns: [QueueState と不変条件 1〜3（init）], hides: [配列操作], note: "共有仕様 §2 の公開操作をそのまま"}
    - {id: CP3, name: ResumeRule / PlaybackSource（core/）, owns: [resume 位置の決定, 再生元の決定], hides: [—], note: "純関数。conformance 表駆動"}
    - {id: CP4, name: PlaybackSession, owns: [セッション状態 union, 現在の Episode DTO, セッション速度, INV-P1 の session 側], hides: [player の呼出順序（prepare→setSpeed→seek→play）]}
    - {id: CP5, name: PlaybackCoordinator（PodcastViewModel 内）, owns: [UC-P1/P3/P6 の判断: source 選択・resume・失敗方針・完聴順序・主体離脱時の停止], hides: [PodcastApi 呼出, port]}
    - {id: CP6, name: OfflineLibrary（AudioCacheManager）, owns: [保存庫, invalidate], hides: [ファイル体系]}
    - {id: CP7, name: AuthViewModel, owns: [AuthState 遷移, 失効 event の受領, 主体離脱の事後条件と CleanupIncomplete], hides: [me/login/logout の呼出, SessionStore]}
    - {id: CP8, name: AuthInterceptor（拡張）, owns: [ヘッダ付与条件, 401 検出], hides: [OkHttp], note: "onUnauthorized は関数注入"}
    - {id: CP9, name: PodcastApi（狭い port）, owns: [再生 UC が使う 5 操作の契約], hides: [ApiClient の残り 39 操作]}
  public_operations:
    - {capsule: CP1, ops: [prepare, play, pause, seekTo, setSpeed, stop, release, "state: StateFlow<PlaybackState>", "isPlaying/positionSeconds/durationSeconds/playbackSpeed（派生）", onPlaybackCompleted（当面維持。state.Ended で置換可）]}
    - {capsule: CP2, ops: [current, upNext, start, setQueue, add, playNext, jump, advance, remove, moveUpNext]}
    - {capsule: CP3, ops: [resolveResumePosition, resolvePlaybackSource]}
    - {capsule: CP5, ops: [playNow, playNext, addToQueue, removeFromQueue, moveUpNext, togglePlayPause, skipBackward, skipForward, seekTo, setSpeed, retry, stopForSubjectLeave, download, removeDownload, cancelDownloadsAndClearCache, "nowPlaying: StateFlow<NowPlaying?>（episodeId / displayTitle / japaneseIntroText / segments / vocabulary / quiz / difficulty）", "session: StateFlow<PlaybackSession>", "queue: StateFlow<PlaybackQueue>"]}
    - {capsule: CP7, ops: [refreshAuth, login, logout, completePasskeyLogin, applyProfileUpdate, onUnauthorized（内部: AppContainer が interceptor へ配線）, "authState", "lastFailure", "cleanupIncomplete"]}
    - {capsule: CP9, ops: [fetchPodcast, fetchPodcasts, updatePlaybackPosition, markCompleted, downloadAudio]}
  branch_decisions:
    - {branch: "resolvePlaybackSource の 3 値", meaning: business decision table, decision: "Coordinator が全 3 値を扱う（現状維持）"}
    - {branch: "ResumeRule の完聴境界", meaning: business rule, decision: "core/ の純関数 1 箇所。spec §6.2 が正本"}
    - {branch: "refreshAuth の catch", meaning: failure classification, decision: "Unauthorized / それ以外 の 2 分岐。意味型の全面導入（RF6）は保留"}
    - {branch: "advance() が null を返す", meaning: lifecycle state, decision: "queue は末尾維持（Q-18）、session が Stopped を表す。keepCurrentPodcast flag は削除"}
  naming_decisions:
    - {from: _currentPodcast, to: nowPlaying（派生 view model）, reason: "SG-R1。正本は queue.current"}
    - {from: "isPlaying = currentPodcast?.id == podcast.id（PodcastScreen）", to: "isNowPlaying", reason: "『再生中』と『選択中』の語の再定義（LF11）を解消"}
    - {from: "ApiException.HttpError(401)", to: "ApiException.Unauthorized", reason: "失効を意味で表す"}
    - {from: "onLogoutCleanup", to: "onSubjectLeave", reason: "logout と失効の両方から呼ばれる事後条件（UC-A2）。SG-R18 satisfied・独立コミット"}
  abstraction_decisions:
    - {subject: PodcastApi port, decision: adopt, rationale: "SG-R8。既存 LearningApi 方式。1 実装"}
    - {subject: PlaybackState union, decision: adopt, rationale: "境界の出力型の貧弱さ（LF1）。テスト double で全状態を注入できる"}
    - {subject: PlaybackSession クラス, decision: adopt, rationale: "INV-P1 の session 側を型で持つ。PodcastViewModel の再生責務を切り出す最小単位（RO6 の順序: authority 統一と同時）"}
  rejected_overdesign:
    - {subject: "ApiClient の consumer 別 10 port 分割", rationale: "RO4。再生系だけで足りる（SG-R8）"}
    - {subject: "AuthState の 4 状態化（unavailable 追加）", rationale: "Unknown + lastFailure で表現でき、MainActivity / AppScaffold の分岐を増やさない"}
    - {subject: "PlaybackService の interface 化", rationale: "RO7。Media3 shared-player 方式の制約。記録のみ（RF17）"}
    - {subject: "Episode decode（PlayableEpisode 判別共用体）", rationale: "web S4 相当。android では PodcastStatusBadge が gate として機能しており、学習サイクルへ"}
    - {subject: "旧 PodcastViewModel と新 PlaybackSession の併存移行", rationale: "SG-R1（正本一意）と二重 owner が両立しない。特性テストで保護し一括切替（web SG8 と同じ）"}
    - {subject: "preferences 消去契約の追加", rationale: "SG-R5 で不採用。9 Fake へ波及"}
  dependency_direction: ["ui → ViewModel → core ← network/podcast(adapter)", "core ↛ android.*/network", "network ↛ auth（onUnauthorized は関数注入）", "PodcastViewModel ↛ ApiClient（PodcastApi のみ）"]
  change_scenarios:
    - {id: CS1, name: replace implementation（ExoPlayer → 別 player / OkHttp → 別 client）, expected: pass, evidence: "PlaybackState union と PodcastApi の contract test（T-T1/T-T16）が不変"}
    - {id: CS4, name: change one business rule（完聴境界・失敗方針・速度段）, expected: pass（1 ファイル）, evidence: "core/ResumeRule / Coordinator / PlaybackConstants に閉じる"}
    - {id: CS5, name: test one consumer in isolation, expected: pass（再生系）, evidence: "FakePodcastApi 5 メソッド + FakePlayerController の状態注入"}
    - {id: CS2/CS3, name: add/change variant, status: not_applicable, rationale: "proven variant なし"}
```

**interface が露出してはならないもの（leakage guard）**: `Player`（ExoPlayer）を `PlaybackService` 以外が読むこと、`Handler/Looper`、`HttpError.code` を再生・認証 ViewModel が比較すること（401 は `Unauthorized`）、例外 message を UI 文言にすること（再生系の 5 箇所は `Errored(reason)` → 固定文言へ）、`PodcastResponse` を UI が「再生中」の意味で読むこと（`nowPlaying` を使う）、`keepCurrentPodcast` のような整合フラグ。

## 6. 移行（Change Safety）— §8.3 の着手順に沿った slice

原則: slice ごとに **特性テスト（現行挙動の pin）→ RED（CI-T*）→ 実装 → 旧 path 削除条件の確認**。1 slice = 1 PR 目安。temporary path は owner・導入日・削除条件を持つ。検証は `JAVA_HOME=<JBR> ./gradlew testDebugUnitTest`（S4 で toolchain 固定後は `./gradlew` のみ）。

| slice | 内容 | 特性テスト（baseline） | RED（T-T*） | temporary path |
|---|---|---|---|---|
| S0 auth（順 1） | `ApiException.Unauthorized`、`refreshAuth` の分岐、`AuthInterceptor.onUnauthorized`（`AppContainer` で配線。発火条件は §2）、`onSubjectLeave`（旧 `onLogoutCleanup` の rename・独立コミット、SG-R18）を失効経路からも呼ぶ、`CleanupIncomplete`、`SessionStore.save` の失敗返却（RF16。§8.3「記録のみ」からの逸脱を SG-R16 で採用）。**再生停止（`stopForSubjectLeave`）は S2 で追加**（S0 では音声＋FCM のみ） | `tests/auth/AuthViewModelTest`（24。`:95` は反転）、`network/AuthInterceptorTest`（6）、`network/OkHttpApiClientTest`（46）、`InMemorySessionStoreTest`（4） | T-T10, T-T11, T-T12（再生停止を除く）, T-T13, T-T14, T-T21 | なし。**注意（gate 指摘 4）**: `AuthViewModel.kt:151-156` は失効ではなく **login の資格情報誤り**の文言分岐。`validateResponse` が 401 を `Unauthorized` にした後は `login` が `Unauthorized` を catch して現行文言「ユーザーIDまたはパスワードが正しくありません」を維持する（CI-T21 で pin）。他 ViewModel は 401 を比較していないため互換層は不要（`grep 'code == 401'` = 0 を確認） |
| S1 test 基盤（順 2） | test 側 `BaseFakeApiClient`（全メソッド `error`）、9 Fake を継承化、`ApiClient` の throwing default 9 箇所削除、`PodcastApi` 切り出し（`ApiClient : PodcastApi`、`OkHttpApiClient` は不変）、`FakePodcastApi`、`FakePlayerController` に `state` 注入 | 全 ViewModel テスト（191）が green のまま | T-T16 | なし（Fake の継承化は挙動不変） |
| S2 playback（順 3・**一括切替**） | `PlaybackState` union（`ExoPlayerController` に `onPlayerError`）、`PlaybackSession`、`ResumeRule`、Queue `init`、速度 8 段統一＋既定速度適用、`nowPlaying`、`_currentPodcast` / `keepCurrentPodcast` 削除、完聴順序、`invalidate`、`stopForSubjectLeave`（`onSubjectLeave` へ追加）、UI 3 ファイルの読み替え、**共有仕様の新節 §6.4（resume 規則: 完聴境界 2 秒窓・位置同期の送信条件・完聴時は duration）と §6.5（主体離脱の事後条件: Android 行、cleanup 完了待ちの明文化）を先行 PR で起票し、web/iOS 合意を前提にする（SG-R17）** | **8 ファイル**: `podcast/PodcastViewModelTest`（52）、`core/PlaybackQueueConformanceTest`（32）、`core/PlaybackSourceResolverTest`（4）、`network/AudioCacheManagerTest`（14）、`podcast/PlaybackMetadataTest`、`podcast/PodcastStatusBadgeTest`、`settings/SettingsViewModelTest`（24。速度）、`preferences/DataStorePreferencesStoreTest`（5） | T-T1〜T-T9, T-T15, T-T17, T-T19（T-T20 は baseline） | **TP2** `onPlaybackCompleted` コールバックの維持（`state.Ended` で置換可能になるまで）。owner: user、導入: S2、削除条件: `PodcastViewModel` が `state` の `Ended` だけを購読するようになった時 |
| S3 CI（順 4） | ci.yml を 3 ステップへ、`jvmToolchain(17)` | — | T-T18（差分レビュー） | なし |
| S4 保留（学習・設定サイクル） | RF6 全面（意味型）、RF8（Screen の owner）、RF9（週目標・難易度の値域）、RF10（UiState 排他）、RF2 | — | OB-C11/C12/C14/C15/C16 | — |

**S2 の scope 上の注意**: `AudioPlayerSection` / `PodcastScreen` / `QueueSheet` は `currentPodcast` の field（segments / vocabulary / quiz / japaneseIntroText）を直読みしているため、`nowPlaying` の view model にそれらを含める（S2 で定義）。`PlaybackService` は `ExoPlayerController.player` を読む現状を維持（RF17）。

**不可逆点**: 共有仕様の改訂は 3 platform に効く。gate 指摘 10 のとおり、現 §6.2 は「オフライン中の位置同期」、§6.3 は「logout 時のキャッシュ削除」が主題であり、完聴境界（resume 規則）・送信条件・失効時の主体離脱は **主題の変更**にあたる。追記ではなく新節（例: §6.4 resume 規則、§6.5 主体離脱の事後条件）として起票し、3 platform 合意を gate にする（SG-R17）。iOS は resume 規則で既に同挙動、web は「規則の追加」。§6.3 の「cleanup 失敗でも即座に未認証へ」は現行 android（`AuthViewModel.kt:167-182`、cleanup 完了待ち）と既に食い違う点も新節で明文化する。android 内部は UI 内部構造のみで、backend 契約・DataStore key・キャッシュ体系・Q-* は不変。
**rollback**: slice 単位の revert。S2 は一括切替のため、上記 8 特性テストが **すべて追加・green になってから** 切替に入る。

## 7. Requirement → UC → model → contract → test の trace

| R | UC | model / capsule | CI | test | status（design） |
|---|---|---|---|---|---|
| R1 業務ルール単一所有 | UC-P1/P2 | ResumeRule, PlaybackConstants（8 段）, refreshAuth の分類 | CI-T3, T-T15, CS4 | T-T3/15 | partial（RF6/RF8 の全面は S4） |
| R2 再生の不正状態なし | UC-P1〜P5 | PlaybackState, PlaybackSession, Queue init | CI-T1/T2/T5/T7/T9 | T-T1/2/5/7/9 | covered |
| R3 正本一意 | UC-P1/P2/P6 | queue.current + INV-P1, 既定速度→session, resume の reader | CI-T4/T5/T17 | T-T4/5/17 | covered |
| R4 失敗の意味 | UC-A1, UC-P1 | Unauthorized, Errored(reason) | CI-T6/T10 | T-T6/10 | partial（意味型全面は S4） |
| R5 失効の扱い | UC-A1 | AuthState 遷移表、AuthInterceptor 401 | CI-T10/T11/T14 | T-T10/11/14 | covered |
| R6 主体離脱でキャッシュ・再生状態なし | UC-A2 | onSubjectLeave + stopForSubjectLeave + CleanupIncomplete | CI-T12/T13 | T-T12/13 | covered（preferences は不採用を明記） |
| R7 本番経路のテスト | — | PodcastApi seam, BaseFakeApiClient, PlayerController 状態注入 | CI-T16 + 各 T-T | T-T16 + MockWebServer 経路の PodcastApi 5 件 | partial（androidTest は据置。CI-T2 の ExoPlayer 配線は未検証） |
| R8 CI ゲート | — | — | CI-T18 | 差分レビュー | covered（S3） |
| R9 共有仕様 | UC-P4/P6, UC-A2 | Queue（Q-* 不変）, ResumeRule（§6.2）, onSubjectLeave（§6.3） | CI-T3/T7/T8/T12 | 既存 32 + T-T3/7/8/12 | covered（spec §6.2/§6.3 追記が前提） |

**UC 側の coverage 分母**: UC 9 件のうち CI-T を持つのは UC-P1〜P6, UC-A1, UC-A2 の 8 件。CI 対象外 1 件: UC-S1（MediaSession。所有権は現状維持・RF17 記録のみ）。

## 8. 検証計画と decision

```yaml
verification_plan:
  per_slice: ["特性テスト green（baseline）", "T-T* RED → GREEN", "JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest（S3 以降は toolchain）", "S2 のみ: エミュレータ（AVD newslisten_e2e）で resume・既定速度・次エピソード失敗時の停止表示を手動観測（UV3）"]
  independent: ["slice ごとに code-review ロール 1 回（consolidated）", "S2 完了時に adversarial-review で CI-T1〜T9 の oracle を検算"]
  unexecuted_now: [UV1 lint, UV2 T-T* 実装, UV3 実機観測, UV4 ExoPlayer 配線・Keystore（androidTest 不在）]
  pre_implementation_gate: {role: architecture, result: "revise → 本版で必須 10 件・推奨 6 件を反映", artifact: "docs/research-reports/2026-09-16-code-design-review/spec-gate.md"}
selection_gates:
  # gate 指摘 5: 以下は §8 に無い本書固有の判断。2026-09-16 Q14〜Q19 で user が確定
  - {id: SG-R13, subject: "AuthState を 4 状態化せず Unknown + lastFailure で表す", owner: user, status: satisfied, decision: "adopt（2026-09-16 Q14）"}
  - {id: SG-R14, subject: "完聴時の位置同期の値を duration にする", owner: user, status: satisfied, decision: "adopt（2026-09-16 Q15）。順序 markCompleted → position(duration) → advance"}
  - {id: SG-R15, subject: "S2 の切替方式（一括 / 段階）", owner: user, status: satisfied, decision: "一括（2026-09-16 Q16）。8 特性テスト＋T-T20 green が前提"}
  - {id: SG-R16, subject: "RF16（SessionStore.save の失敗返却）を §8.3『記録のみ』から S0 へ繰り上げるか", owner: user, status: satisfied, decision: "S0 に含める（2026-09-16 Q17。§8.3 からの逸脱として記録。CI-T14 / T-T14 は S0）"}
  - {id: SG-R17, subject: "共有仕様の改訂を新節（resume 規則・主体離脱の事後条件）として 3 platform 合意 gate に分離するか", owner: user, status: satisfied, decision: "分離する（2026-09-16 Q18）。新節 §6.4/§6.5 を起票し、web/iOS 合意を S2 着手の前提にする"}
  - {id: SG-R18, subject: "onLogoutCleanup → onSubjectLeave の rename", owner: user, status: satisfied, decision: "rename する（2026-09-16 Q19。挙動不変の独立コミット）"}
decision:
  status: pass
  artifact_readiness: ready
  engineering_status: planned
  release_status: not_applicable
  decision_maturity: {status: approved, owner: user, approval_evidence: ["2026-09-16 user: Spec 承認（Q20）・SG-R13〜R18（Q14〜Q19）"], baseline_version: "2026-09-16", change_control: "本書を更新して再承認"}
  next_phase: {name: "共有仕様新節 §6.4/§6.5 の先行 PR（3 platform 合意）→ S0 実装（tdd-implementation ロール）", status: allowed, human_approvals_required: []}
  resolved_unknowns:
    - {id: U1(backend resume), resolution: "backend は位置を duration に clamp して保存するだけ（podcasts.py:219-242）。server-wins は client 規則。ResumeRule は client に置く"}
    - {id: "iOS 速度適用", resolution: "iOS も既定速度を player に適用していない（同型欠陥）。android が先行して SG-R2 を実装し、iOS への展開は iOS 側判断"}
  unknowns: [U1（streak と位置 PATCH の関係。SG-R9 現行維持のため blocker ではない）, U2（markCompleted の backend 冪等性。client 側 1 回抑制で対処）, UK1（Media3 PlaybackException errorCode の分類表。実装時に確定）, UK2（passkey login の URL が三点一致で Authorization 無しか。S0 で確認）, UK4（一覧再取得が queue を作り直すか。S2 の特性テストで pin）, UK5（backend が position=duration をどう解釈するか。SG-R14）]
  residual_risks: ["S2 は一括切替のため特性テスト 8 ファイルの追加を先行させないと回復手段が revert のみ", "CI-T2（onPlayerError → Failed）の ExoPlayer 配線は JVM で検証できず、実機観測（UV3）に依存", "共有仕様 §6.2/§6.3 の追記が web/iOS の合意を要し、S2 の前提になる", "PodcastViewModel の 6 責務のうち再生以外（語彙・クイズ・DL）は S2 で触らず残る（RF8 保留）"]
```
