# news-listen-android Implementation Spec — 再生セッション・認証失効・テスト基盤（design mode）

日付: 2026-09-16 ／ mode: design（read-only、実装は別フェーズ）／ owner: user ／ decision_maturity: **approved**（2026-09-16 user 承認 Q20。P9 事前実装ゲート = revise → 必須 10・推奨 6 を反映済み。SG-R13〜SG-R18 は Q14〜Q19 で確定）／ 最終更新: 2026-09-30（本文の表を現行値へ更新。同日、目標アーキテクチャの Spec に合わせて構造の行を改訂。末尾「9. 改訂履歴」）
入力: `docs/research-reports/2026-09-16-code-design-review.md`（finding RF*・§8 人間判断 SG-R1〜SG-R12・着手順）と同ディレクトリの Function package（F*/G*/IV*/OB-C*/CI*/LF*/RO*）。
位置づけ: 共有再生仕様 `docs/design/shared-playback-spec.md` §2/§6 の android 実装に対する **target 版設計**。web の Spec（`web/docs/design/2026-09-16-implementation-spec-domain-model.md`）と同じ判断順・同じ語（term ledger）を使い、platform 固有の差だけを書く。
新しい Spec との関係（2026-09-30）: module 全体の構造（層と path の対応・依存の規則・context ごとのモデルの対応・command と query・規則の置き場・検証・slice の全体）の正本は [2026-09-30-implementation-spec-target-architecture.md](2026-09-30-implementation-spec-target-architecture.md)（親 docs [ADR-110](../../../docs/adr/110-refactor-target-domain-centered-onion-cqrs.md)）。本書は、再生・認証・テストの土台の範囲の、状態遷移と契約の詳細（遷移表 11 辺・`AuthState` の遷移表・CI-T1〜T21・CP1〜CP9）の正本のまま残す。構造について両者が食い違うときは新しい Spec を正とし、本書の本文を直して改訂履歴に残す。本書が「DTO のまま」「保留」「学習サイクル」とした箇所は移行の段階の扱いで、解く slice は新しい Spec §8 にある。

> 設計原則（本書の判断順）: actor の目的 → use case → その判断に必要な概念・不変条件 → 契約 → カプセル（公開操作と隠す技術）→ 依存方向 → 移行。pattern 名・class 数は成果にしない。1 実装しかない箇所に factory / Strategy を作らない（Boundary RO1〜RO8 を踏襲）。設計書に実装コードを載せない。


> **本書の読み方（2026-09-30）**: 本文の表は 2026-09-30 に現行値へ更新した（直した行には決定 ID を括弧で併記し、旧値は末尾「9. 改訂履歴」に残す）。以下の追記節は改訂の経緯として残す。以後は本文を直し、改訂履歴に 1 行足す（追記で本文を上書きする運用はやめる）。採用済みだが未実装の決定は「target（決定 ID・未実装）」と書く。進捗の正本は `docs/plan/2026-09-16-design-review-refactor/README.md` の状態欄で、本書には書かない。
>
> **追記（2026-09-16・共有仕様 §6.7 の確定による上書き）**: 親 docs `shared-playback-spec.md` §6.7 の Selection Gate が user 判断で確定し、本書の次の記述を上書きした（当時は本文を改訂せず、この追記で上書きした。2026-09-30 に本文へ反映済み）。
> - SG-X1: 完聴時に `duration` を明示送信する（SG-R14 のとおり。3 platform 共通で確定）。
> - SG-X3: 主体離脱は **cleanup 完了を待たず**、`sessionStore.clear()` → `Unauthenticated` → `onSubjectLeave()` の順にする（§3.2 の遷移表の事後条件は同じ。順序だけ改める）。**2026-09-30 訂正**: 当初は「S0 で実施」と書いたが、この順序変更は S0 に入っていない。S0（android PR #28）で入ったのは `ApiException.Unauthorized`・`refreshAuth` の分岐・`AuthInterceptor.onUnauthorized` の配線・`onLogoutCleanup` → `onSubjectLeave` の rename・`SessionStore.save` の失敗返却・`lastFailure` まで。順序変更（破棄 → 未認証 → 後始末）・失効経路からの後始末・`CleanupIncomplete` は未実装で、A-S4 で入る（実コード: `AuthViewModel.logout()` は `onSubjectLeave()` の完了後に `sessionStore.clear()`、`onUnauthorized` は `onSubjectLeave` を呼ばない、`CleanupIncomplete` は `app/src` に 0 件）。SG-X3 は 2026-09-23 に ADR-104 で「待たない＋主体識別」へ改訂された。
> - SG-X4: **一時停止中の 15 秒 PATCH はやめる**（SG-R9「現行維持」を置換。backend は位置 PATCH 到達ごとに `listeningDays` を書くため、U1 は「依存する」で解消。CI-T19 に一時停止中の非送信を追加）。
>
> **追記（2026-09-30・wave 1 完了後の前提点検による明記）**: 再生の停止の扱いを user 判断で確定した（親 docs 監査レポート §5 の SG-C24・SG-C25、共有仕様 §6.6）。
> - 再生セッションの停止は §3.1 の遷移表の外の**リセット**で、分母 11 には数えない。Android の `PlaybackSession` は値（sealed union）なので、停止は **Coordinator が `NothingPlaying` を代入して表す。遷移関数は通らない**（表外遷移の `IllegalStateException` の対象にならない）。`stopForSubjectLeave()`（§3.1）の「→ `NothingPlaying`」はこの代入を指す。
> - `PlaybackSession` に停止の操作は足さない。A-S2a の order は変えない。代入と `playerController.stop()` の呼出は A-S2b2 が実装し、`FakePlayerController` の状態で観測する。
>
> **追記（2026-09-30・wave 3 の前提点検による上書き）**: user 判断で確定した（親 docs 監査レポート §5 の SG-C49〜C52・C54・C61・C62、共有仕様 §2.4・§2.11・§6.4・§6.6）。本書の次の記述を上書きした（当時は本文を改訂せず、この追記で上書きした。2026-09-30 に本文へ反映済み）。
> - **Queue**（SG-C50。§3.1 の `setQueue` の dedupe を具体化）: 開始位置は元の入力で clamp して id を決め、先勝ちで重複を除いた後のその id の位置を現在にする（共有仕様 Q-33）。
> - **`stopPlayback()` の終状態**（SG-C51）: 「位置同期 1 回 → `playerController.stop()` → `NothingPlaying` を代入。キューは保持」。`Stopped` は `Completed` からキューが尽きた場合だけに使う（遷移表どおり）。
> - **取得前・開始前の失敗**（SG-C52。§3.1 の `startEpisode` の `Errored(…)` を具体化）: Coordinator が `Errored(ref, reason)` の値を代入して表す。停止と同じく遷移関数を通らず、分母 11 には数えない。
> - **手動で選んだエピソードが開始前に再生できないと分かる場合**（SG-C62。`playabilityError`・`UNAVAILABLE`）: キューもセッションも変えず、`errorMessage` に理由を出すだけにする（再生中のものは続く）。再生可能かは状態を変える前に判定する。`Errored` にするのは、キューが既にそのエピソードを現在にしている場合（完聴後の advance・`retry()`）だけ。§3.1 の `startEpisode` の「`playabilityError` → `Errored(NotPlayable)`」はこの場合分けで読む。
> - **総時間の正本**（SG-C54）: player の `durationSeconds`（0 より大きい）→ DTO の `durationSeconds`（0 より大きい）→ 不明。完聴時（PS-06）は優先順で得た値を送り、不明なら完聴時点の現在位置、それも 0 なら位置は送らない。位置の丸めは不明な間は上限なし。
> - **完聴時の順序**（SG-C61。§3.1 の `onEnded`）: 完聴の記録と位置の書込は、この順で送り始める。次のエピソードの開始は応答を待たない。
> - **順序**（SG-C49）: A-S4 は A-S2b1 の PR が main に入った後に投入する。
> - **次へ送り**（SG-C63）: Android は現時点で入口を持たない。足すときは共有仕様 §2.12 に従う。
> - **導出**（親 docs 監査レポート §5.0 の A-1〜A-4。A-S2b2 の order が固定）: 手動の開始は「判定 → 取得 → その後でキューと Session を変える」の順で、取得の失敗でも状態を変えない（A-1）。「何も再生していない」は `NothingPlaying`（A-2）。`Active` の速度は開始時の値のまま（A-3）。完聴の送信は別の coroutine の直列で、次の開始は待たない（A-4）。
> - 設計の正本は親 docs `adr/105-playback-session-out-of-table-operations-and-shared-rules.md` と `design/android-design.md` §7.4。

## 0. Decision frame と function_plan

```yaml
decision_frame:
  mode: design
  requested_outcome: Implementation Spec（use case catalog・context 分割・model・契約・capsule・移行・検証計画）
  decision_owner: user
  mutation_authorized: false
  in_scope: [podcast/ auth/ network/ core/ preferences/（値域のみ） di/ の責務再配置, 契約と test obligation, 移行手順, CI 分割]
  out_of_scope: [backend 契約変更, iOS/web の実装, 意匠, 学習機能・設定画面・admin の model（§8 保留: RF6 全面 / RF8 / RF9 / RF10 / RF2）, 共有仕様の改訂文面そのもの（本書は改訂内容を「要求」として書く）]   # 2026-09-30: 学習機能・設定画面の model は、新しい Spec（2026-09-30）の範囲に入った（admin は Android に画面が無い）
  reversibility: reversible（slice 単位・特性テストで保護）
  public_contract_change_allowed: false   # backend API・共有仕様 §2 の QueueState 表現・Q-01〜Q-32 は不変（2026-09-30: backend API は位置の書込の 1 点を除く = ADR-109。target・未実装。Q-33 を追加 = SG-C50）。共有仕様 §6 系の改訂は SG-R17（新節＋3 platform 合意 gate）
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
| UC-P3 連続再生 | Listener | ended → 完聴記録 → 次へ or 停止 | 完聴記録の順序（completed → 位置 → advance。送信を始める順で、次の開始は応答を待たない: SG-C61）、次の取得失敗は**停止＋手動再試行（SG-R7）** | Playback |
| UC-P4 キューを編成する | Listener | 追加・次に再生・削除・並べ替え | 共有仕様 §2（Q-01〜Q-32）＋不変条件の構築時保証（SG-R10） | Playback |
| UC-P5 オフライン保存 | Listener | 保存・削除・全削除 | 成功応答のみ保存、破損キャッシュは再生失敗時に無効化（RF15） | Playback（OfflineLibrary） |
| UC-P6 再生位置の同期 | Listener | 再生中の 15 秒ごと＋状態変化時に server へ | 一時停止中は周期送信しない。pause への遷移時に 1 回送る（SG-X4。SG-R9「現行維持」を置換）、完聴時の順序。位置の記録時刻・端末保存・再開時の確認は target（ADR-109・未実装。§3.1） | Playback |
| UC-A1 認証状態の解決 | Account owner | 起動時 refresh / login / passkey / logout / **失効** | 失効（unauthorized）のみトークン破棄、到達不能は保持＋再試行（SG-R6） | Account |
| UC-A2 主体が離れる | Account owner / System | logout・失効・主体の直接交代（遷移①②④。ADR-104 決定 3）の事後条件 | 離脱主体の音声キャッシュ・**再生状態**・主体依存の端末設定 4 key を消す（SG-A6。SG-R5「preferences は消さない」を撤回）。FCM トークンの解除は client から呼ばず、サーバのセッション削除連鎖に任せる（SG-B4・ADR-104 決定 10）。位置同期は送らない（SG-C16）。後始末の完了を待たない（SG-X3・ADR-104 決定 1） | Account → Playback / Notifications |
| UC-S1 ロック画面・通知 | System | MediaSession が Player を共有 | 所有権は現状維持（RF17 記録のみ） | Platform |

### 1.3 term ledger（web Spec §1.3 と同じ語。android 固有の別義だけ追記）

| term | 意味 | 区別する別義（android） | 正本 |
|---|---|---|---|
| Episode | 聴く対象。Catalog の domain の型 `Episode`（`Playable` / `Generating` / `Failed`。target・A-T2a・未実装）。現状は `PodcastResponse` DTO をそのまま使う | DTO `PodcastResponse`（通信のデータモデル） | Catalog（`catalog/domain/Episode.kt`。新しい Spec §5.2。旧: 本書では DTO のまま・decode 分離は保留 slice） |
| 再生可能（Playable） | `Episode.Playable`（共有仕様 §6.6 の fail-closed 判定。target・A-T2b・未実装）。現状は `PodcastStatusBadge.from(podcast)` が `None` | 生成中（`Generating`。現状 Processing）・失敗（`Failed`） | `catalog/domain/Episode.kt`（現状 `podcast/PodcastStatusBadge.kt`。A-T2b で削除） |
| 現在再生中 | `Queue.current`（共有仕様 §2.1 不変条件 4） | `_currentPodcast`（削除）、行 UI の「選択中」、ExoPlayer の MediaItem | Playback Queue |
| 完聴（completed listen） | `STATE_ENDED` 到達の事象（`markCompleted`） | 生成完了（`status == "completed"`） | Playback |
| 既定速度 | 新しい再生の初期速度（設定・永続・server 同期） | セッション速度（今回の再生・非永続） | Preferences ／ Playback |
| resume 位置 | server 保存位置から `resolveResumePosition` で導いた開始位置 | 15 秒同期で書く位置 | Playback（`core/`） |
| 認証済み | `AuthState.Authenticated(user)` | 保存トークンの存在 | Account |
| 失効（unauthorized） | 保存トークンで API が 401 を返した事象 | 到達不能（NetworkError）・server 障害（5xx） | Account / Platform |
| 主体が離れる | 認証状態の遷移 ①明示 logout・②保存トークン付き API の 401（どちらも `Authenticated(A) → Unauthenticated`）・④`Authenticated(A) → Authenticated(B)`（主体の直接交代）。この 3 つが全数（ADR-104 決定 3） | `Authenticated → Unknown`（通信断・5xx・decode 失敗。離脱でない）。起動時の失効（③。起動時の回収で拾う） | Account |

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
| **Playback** | 聴き続ける | `PlaybackSession`（再生セッション状態 union・現在の `Episode`（A-T2b まで DTO）・セッション速度・resume 適用）, `Queue`（順序と現在位置）, `PlaybackSource`, `ResumeRule`, `OfflineLibrary`（`AudioCacheManager` ＋ `invalidate`・`download` / `removeDownload` / `cancelDownloadsAndClearCache`。Playback context の capsule。A-S4 で `PodcastViewModel` から移す: SG-C17） | `core/`（純粋）、`podcast/PlaybackSession.kt`（新）、`podcast/PodcastViewModel.kt`（orchestration のみ） |
| **Account** | 誰であるか | `AuthState`（既存 sealed。`Unknown` を「判定保留」として再利用）, 失効 event, 主体離脱の事後条件 | `auth/` |
| **Preferences** | 自分の使い方 | 既定速度・既定難易度・週目標（値域は本書では速度のみ確定: 8 段） | `preferences/` |
| **Notifications** | 通知 | FCM トークンの登録（認証時）。解除は client から呼ばず、サーバのセッション削除連鎖に任せる（SG-B4・ADR-104 決定 10。client の解除呼出の削除は A-S4・未実装） | 現状維持 |
| **Platform** | 技術境界 | `ApiClient`（＋失敗型 `ApiException`）, `PodcastApi`（狭い port・新）, `PlayerController`（状態 union へ拡張）, `SessionStore`, `AuthInterceptor`（401 検出を追加）, `AudioCacheManager` | `network/`, `podcast/ExoPlayerController.kt` |

**依存方向の禁止事項（prohibited_structures）**
- `core/` は `android.*`・`network`・`kotlinx.coroutines` を import しない（現状維持。`ResumeRule` も純関数）。
- `podcast/PodcastViewModel` の**再生 use case**（`startEpisode` / 完聴 / 位置同期 / DL）は `PodcastApi`（5 メソッド）だけを知る（SG-R8）。語彙登録（`fetchVocabulary` / `saveVocabulary`）とクイズ中継（`submitQuizAnswers`）の 3 操作は RF8 保留のため S2 では分離せず、既存の `ApiClient` 経由のまま残す（gate 指摘 3。完全分離は A-T7b で、Learning の application へ移す。新しい Spec §5.5。旧: RF8 の slice で）。
- `network/AuthInterceptor` は `auth/` を import しない。401 の通知は関数注入 `onUnauthorized: () -> Unit`（`tokenProvider` と同型）。**発火条件は「三点一致かつ `Authorization` ヘッダを付与した（tokenProvider が非 null だった）リクエストの 401」のみ**。トークン無しの 401（login / passkey login の資格情報誤り、`AuthViewModel.kt:137,151-156`）では呼ばない（gate 指摘 1: さもないとパスワード誤入力で主体離脱 cleanup が走る）。
- `PlayerController` の interface に Media3 の型を出さない（`player: Player` の公開は `ExoPlayerController` 具象に留め、`PlaybackService` だけが使う。RF17 現状維持）。

**port を置く根拠（abstraction gate）**: 追加するのは 1 つだけ。

| port | 根拠 | 既存の萌芽 |
|---|---|---|
| `PodcastApi`（fetchPodcast / fetchPodcasts / updatePlaybackPosition / markCompleted / downloadAudio） | SG-R8。再生 RED テストの Fake が 5 メソッドで書ける（QL2）。`OkHttpApiClient` が実装し、`ApiClient` が継承する | `network/LearningApi.kt`・`VocabularyTestApi.kt` と同方式 |

`PlayerController` / `SessionStore` / `PreferencesStore` / `FileSystem` は既存 port をそのまま使う。consumer 別の全 port 分割（RO4）・Strategy・Clock port は作らない。`ApiClient` を割らない決定は保つ。目標では、context ごとの port を `ApiClient` の前に置き、port の adapter が DTO を domain の型へ写す（新しい Spec §5.8・導出 A-24。target・A-T2a 以降）。

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
| `Failed(reason)` | `reason: Source \| Decode \| Network \| Unknown(code)` | `onPlayerError`（**新規購読**。`PlaybackException.errorCode` から分類。分類は A-S2a で確定・実装済みで、正本は `podcast/PlaybackState.kt` の `classifyPlaybackError` と `PlaybackStateTest`） |

**PlaybackSession（use case 側の再生セッション。`PodcastViewModel` から分離する非 Android クラス）**

| 状態 | 保持する値 | 遷移（コマンド／イベント） |
|---|---|---|
| `NothingPlaying` | — | `start(episode, resume, speed)` → `Starting` |
| `Starting` | episode（取り直した `Episode.Playable`。A-T2b まで DTO）, resumePosition, speed | player `Loading→Playing/Paused` → `Active`／player `Failed` → `Errored` |
| `Active` | episode, speed | player 状態は `PlayerController` に委譲（派生）／`ended` → `Completed`／player `Failed` → `Errored` |
| `Completed` | episode | Coordinator が完聴記録 → `advance` → `Starting(next)` か `Stopped` |
| `Stopped` | episode（最後に聴いたもの。キューが尽きた） | `start` |
| `Errored` | `ref`（取得済みなら `Episode`（A-T2b まで DTO）、取得前なら id だけ。型は `SessionEpisodeRef`。A-S2a で確定・実装済み。正本はコードとテスト）, `reason: SourceUnavailable(offline) \| FetchFailed(ApiException) \| NotPlayable(gate) \| Player(Failed.reason)` | `retry()`（手動）→ `Starting`／`start` |

**不変条件 INV-P1（SG-R1）**: `session` が `NothingPlaying` でないとき `session.episode.id == queue.current?.id`。`Stopped` のとき `queue.currentIndex` は末尾のまま（Q-18 不変）で、`session` が「停止」を表す。UI が「何が再生中か」を問う唯一の入口は `PodcastViewModel.nowPlaying: StateFlow<NowPlaying?>`。field は実読み手（`AudioPlayerSection.kt:75,101,115,125-126,142`、`PodcastScreen.kt:160,204`、`QueueSheet.kt:58,83`）から逆算した **episodeId / displayTitle / japaneseIntroText / segments / vocabulary / quiz / difficulty** の 7 つ（`durationSeconds` は player が正本なので含めない。gate 指摘 7）。`segments`・`vocabulary`・`quiz` の型は、A-T2b 以後 `Episode` の内容の型（`model.*` ではない）。キューのリードモデル `QueueView` は新しい Spec §5.1（A-T3a）。`session` が `NothingPlaying` または `Errored(episodeRef のみ)` のとき `null`（空のプレイヤーを出さない。`Errored` の文言は `session` を読む error 表示経路が担う）。`_currentPodcast`（`PodcastViewModel.kt:83-86`）は削除し、`AudioPlayerSection.kt:65` / `PodcastScreen.kt:61,160,204` / `QueueSheet.kt:58,83` の読み出しを `nowPlaying` へ置換。行 UI の「再生中」ハイライトは `nowPlaying?.episodeId == podcast.id`（意味は「現在再生中」。一時停止でもハイライトは維持＝現状の観測挙動を保存）。

遷移表の分母（T-T1 用に固定）: NothingPlaying→Starting, Starting→Active, Starting→Errored, Active→Completed, Active→Errored, Active→Starting(playNow), Completed→Starting(advance), Completed→Stopped, Stopped→Starting, Errored→Starting(retry), Errored→Starting(start) の **11 遷移**。表外（例: Errored→Active、Stopped→Completed）は禁止。A-S2a で確定（実装済み。遷移関数 8 つで 11 辺。正本は `podcast/PlaybackSession.kt` と `PlaybackSessionTest`）。

**遷移表の外の操作（SG-C24・C25・C52）**: 再生セッションの停止と、取得前・開始前の失敗は、遷移表の外の操作で、分母 11 に数えない。`PlaybackSession` は値（sealed union）なので、Coordinator が `NothingPlaying`（停止）または `Errored(ref, reason)`（失敗にする）の値を代入して表す。遷移関数は通らない（表外遷移の `IllegalStateException` の対象にならない）。`Stopped` は `Completed` からキューが尽きた場合だけに使う（SG-C51）。

**Queue** — 共有仕様 §2 の `PlaybackQueue<T>` をそのまま採用。**`init` で不変条件 1〜3 を検査し違反は `require` で throw**（SG-R10、programmer error）。公開操作は §2 どおり正規化し throw しない。ただし現行 `setQueue`（`core/PlaybackQueue.kt:39-44`）は入力の id 重複を除去しないため `init` の一意性検査と衝突する（gate 指摘 9）→ `setQueue` は入力を **先勝ちで dedupe** してから構築する（不変条件 1 は「すべての操作の前後で保たれる」と規定されており §2.4 と矛盾しない。Q-04〜Q-07 は重複なし入力なので不変）。開始位置は元の入力で clamp して「開始する id」を決め、重複を除いた後のその id の位置を現在にする（SG-C50。共有仕様 §2.4・Q-33）。Q-01〜Q-32 不変（Q-33 を追加: SG-C50）。`copy` も `init` を通るため、`PodcastViewModel` 内の `_queue.value = ...` はすべて検査済みの値になる。

**ResumeRule（`core/`、純関数、SG-R4）** — `resolveResumePosition(serverSeconds: Double, durationSeconds: Int): Double`。規則: `durationSeconds > 0 && serverSeconds >= durationSeconds − 2` なら 0（完聴済み＝先頭から）、`serverSeconds > 0` ならその値、それ以外 0。iOS `PodcastViewModel.swift:304-307` と同一。規則の正本は共有仕様 §6.4（「完聴境界 = 末尾 2 秒」。新節として起票済み: SG-R17。3 platform 共通規則で、web も追随する: SG-X2。旧: §6.2 へ追記・web は web 側判断）。CACHED 経路は `fetchPodcast` を通らないため、resume は一覧の `Episode`（A-T2b まで DTO）が持つ位置（一覧取得時点の値）を使う。NETWORK 経路は取り直した `Episode` の値を使う。

**速度（SG-R2 / SG-R3）** — 既定速度は `PreferencesStore.defaultPlaybackSpeed`（Double、値域 = `PlaybackConstants.speeds` 8 段）。`PlaybackSession.start` は `speed = preferences.defaultPlaybackSpeed` で初期化し `playerController.setSpeed` を呼ぶ。以後 `setSpeed` はセッション内で保持。設定画面の `PLAYBACK_SPEEDS`（5 段・`SettingsScreen.kt:1361`）は削除し `PlaybackConstants.speeds` を参照。値域の正本は **Double 1 本**（`PlaybackConstants.speeds: List<Double>`。`PlayerController.setSpeed(Float)` へは境界で変換。gate 推奨）。`DataStorePreferencesStore.setDefaultPlaybackSpeed` は値域外を**拒否して直前の妥当値を維持**（server が 0.0 を返しても無音再生にならない。既定 1.0 への置換は保存済みの妥当値を失うため採らない）。

**PlaybackSource / OfflineLibrary** — `resolvePlaybackSource` は現状維持（§6.1 一致）。`AudioCacheManager` に `invalidate(id)`（削除と同義だが意味を分ける）を足し、CACHED 経路で player が `Failed(Source|Decode)` になったら Coordinator が `invalidate` して `Errored(Player)` へ（次回は NETWORK へ退避。RF15 / OB-C18）。

**PlaybackCoordinator（`PodcastViewModel` 内の非 Android 関数群。`PodcastApi` と port だけに依存）** — 以下は target（A-S2b2・未実装）。`PodcastViewModel` の中に置くのは移行の段階で、目標では class `PlaybackCoordinator` として取り出す（A-T3a。新しい Spec §5.1）。A-T2b 以後、以下の `podcast`・`dto`・「DTO」は `Episode`、`playabilityError` は `Episode` の種類による判定と読む。取り直した `Episode` が再生可能でないときは、開始前に再生できないと分かった場合と同じに扱う（導出 A-17）。開始を確定するときの辺の選び方と、player の事象の受け方は、新しい Spec §8.3 の表（導出 A-14・A-15）。
- `startEpisode(podcast)`: 再生可能性の判定（`playabilityError`）→ `source = resolvePlaybackSource(...)` → `CACHED` なら一覧 DTO＋cachedUri／`NETWORK` なら `podcastApi.fetchPodcast` の順に行う。**手動の開始**は、この判定と取得が通ったときだけ、その後でキューと Session を変える。判定に落ちる・`UNAVAILABLE`・取得の失敗のどれでも、キューも Session も変えず、`errorMessage` に理由を出すだけにする（再生中のものは続く。SG-C62・SG-C68・導出 A-1）。`Errored` にするのは、キューが既にそのエピソードを現在にしている場合（完聴後の advance・`retry()`）だけで、Coordinator が `Errored(ref, reason)`（`NotPlayable` / `SourceUnavailable` / `FetchFailed`）の値を代入する（SG-C52。queue は進めたまま、INV-P1 は `ref` の id で維持）。旧: 手動の開始でも `Errored(NotPlayable)` / `Errored(FetchFailed)` / `Errored(SourceUnavailable)` へ遷移。通った後は `resume = resolveResumePosition(dto.playbackPositionSeconds, dto.durationSeconds)`；`session.start(dto, resume, speed = prefs.defaultPlaybackSpeed)` → `playerController.prepare → setSpeed → seekTo(resume)（resume > 0 のとき）→ play`。
- `onEnded`: `markCompleted(id)`（best-effort、1 セッション 1 回。backend first-write-wins 依存をコメントとテストに明記）→ 位置同期を 1 回（**値は総時間**。player の `durationSeconds`（0 より大きい）→ `Episode`（A-T2b まで DTO）の `durationSeconds`（0 より大きい）の優先順で得た値。不明なら完聴時点の現在位置、それも 0 なら位置は送らない: SG-X1・SG-C54。完聴を「末尾」で記録し、次回 resume は ResumeRule が 0 に写す）。この 2 つは順に送り始め、次の開始はその応答を待たない（SG-C61・導出 A-4。旧: 応答を待ってから次へ）→ `queue.advance` → `next` があれば `startEpisode(next)`、無ければ `Stopped`。失敗は `Errored`、`retry()` で `startEpisode(queue.current)`。
- `stopForSubjectLeave()`（UC-A2）: `playerController.stop()` → `queue = PlaybackQueue()` → `NothingPlaying` を代入（SG-C24・C25）。**位置同期は送らない**（SG-C16。旧: 先に位置同期 1 回）。主体離脱の後始末（`AppContainer` の `onSubjectLeave`。旧名 `onLogoutCleanup`）から呼ぶ（音声キャッシュの後始末と同列）。
- 位置同期（UC-P6）: 再生中の 15 秒周期と、pause への遷移時の 1 回・停止直前・完聴時に送る。**一時停止中は周期送信しない**（SG-X4。SG-R9「現行維持」を置換。共有仕様 PS-05b）。主体離脱は送信の契機に含めない（SG-C16）。**事前条件: session が prepare 済み（`Starting` / `Active` / `Completed`）のときだけ送る**。`Errored` / `Stopped` / `NothingPlaying` では送らない（gate 指摘 2: 現行は `_currentPodcast ?: return`（`PodcastViewModel.kt:503`）が守っており、削除後に `Errored(FetchFailed)` から `updatePlaybackPosition(id, 0.0)` を送ると server の resume 位置を破壊する）。CACHED 経路の resume は一覧取得時点の値なので、別端末で進めた位置より古い可能性がある（stale resume。許容し明記）。
- **target（ADR-109・SG-C67・C74・C76・C77。未実装・slice 未起票）**: 位置の書込に、位置を記録した端末の時刻を付け、記録時刻の新しい方を正とする（SG-C74。位置の大小では比べず、巻き戻した位置も保存する: SG-C67）。端末はエピソードごとに最後に記録した位置と時刻を永続保存し、送れなかった記録を接続が戻ったとき・起動時・次に位置を送るときに送る。再開位置は端末の記録とサーバーの値のうち時刻が新しい方。主体が離れるときは未送信のものも含めて送らずに消す（SG-C76）。サーバーの記録の方が新しいときの再開時の確認は SG-C77。Android のクライアント側の slice は、A-S2c と backend B-S7 の完了後に、その時点の実物に対して起票する（SG-C79。未起票・ID 未定）。それまでは現行どおりで、届いた順に上書きされる。規則の正本は共有仕様 §6.2・§6.4・§6.5 と ADR-109。
- **保留（SG-C73・Android）**: 「利用者が起こした開始と、自動で次へ進む開始が重なったら、利用者の開始を採る」（共有仕様 §2.11）は Android では保留。現状 = 1 本ずつ順に処理し、後から来たものが効く現行のまま。解除条件 = Android の再生 Coordinator に同じ規則を入れる slice の完了。

### 3.2 Account

**AuthState** — 既存 sealed（`Unknown | Unauthenticated | Authenticated(user)`。`user` は現状 DTO `UserResponse`、目標は domain の `AccountUser` = A-T6。新しい Spec §5.3）を維持し、**`Unknown` の意味を「判定保留（起動直後 or 到達不能で未確定）」に拡張**。新状態は足さない（web の `unavailable` 相当は `Unknown` + `lastFailure: ApiException?` の別 StateFlow で表す。理由: 4 状態化は MainActivity / AppScaffold の分岐を増やし、`Unknown` 表示（ローディング）に「再試行」導線を足すだけで済む）。

| 遷移 | 条件 | 事後条件 |
|---|---|---|
| `Unknown → Authenticated(user)` | `me()` 成功 | preferences 同期、FCM 登録 |
| `Unknown → Unauthenticated` | トークン無し、または `me()` が **`ApiException.Unauthorized`** | `sessionStore.clear()` |
| `Unknown → Unknown` | `me()` が `NetworkError` / `HttpError(5xx)` / `DecodingError` | トークン保持、`lastFailure` 設定、UI は再試行導線 |
| `Authenticated → Unauthenticated`（失効） | 任意 API が 401（`AuthInterceptor` が検出 → `onUnauthorized`） | `sessionStore.clear()` → `Unauthenticated` → **主体離脱の後始末**（下記。完了を待たない: SG-X3・ADR-104 決定 1） |
| `Authenticated → Unauthenticated`（logout） | `logout()` | 同上。server への logout は、破棄の前に捕捉したトークンで既存の `Authorization: Bearer` を付けて送り、await しない（best-effort。ADR-104 決定 14。旧: `apiClient.logout()` を先に呼ぶ） |
| `Authenticated(A) → Authenticated(B)`（直接交代④） | 主体の直接交代（未認証を経由しない） | B の確立は A の後始末を待たない。離脱主体 A について**主体離脱の後始末**（ADR-104 決定 1・3。共有仕様 SL-07） |

**現状と target**: `Unauthorized`・`refreshAuth` の分岐・`onUnauthorized` の受領・`lastFailure`・`save` の失敗返却は S0（android PR #28）で実装済み。失効経路からの後始末・順序（破棄 → 未認証 → 後始末）・直接交代④・捕捉トークンでの logout・`CleanupIncomplete` は target（A-S4・未実装）。現行の `logout()` は server への logout → `onSubjectLeave()` → `sessionStore.clear()` の順で完了を待ち、`onUnauthorized` は後始末を呼ばない。

**主体が離れる事後条件（UC-A2。ADR-104・SG-A6・SG-B4・SG-C16。SG-R5 を改訂）**: 離脱主体の音声キャッシュの削除と進行中 DL の cancel（完了を待たない。ADR-104 決定 2・9。旧: 端末単位の全削除）、**再生状態の停止と空化**（`stopForSubjectLeave`、新。位置同期は送らない: SG-C16）、主体依存の端末設定 4 key（`default_difficulty`・`default_playback_speed`・`weekly_goal_episodes`・`seen_achievement_ids`）の削除（SG-A6。`article_open_mode`・`time_format`・`sfx_enabled`・`haptics_enabled` は残す。旧: preferences は消さない）。FCM トークンの解除は client から呼ばず、サーバのセッション削除連鎖に任せる（SG-B4・ADR-104 決定 10。旧: client が解除を呼ぶ）。順序はトークン破棄 → 未認証（または次の主体の確立）→ 後始末で、後始末の完了を待たない（SG-X3・ADR-104 決定 1）。各手順は独立に試み（1 つが失敗しても残りを実行する）、再実行は冪等で同じ事後条件に収束する（共有仕様 §6.5・SL-04）。失敗は `CleanupIncomplete(parts)` として `auth/SubjectCleanup` の StateFlow に残し観測可能にする（OB-C10。導出 A-8。旧: `AuthViewModel` の StateFlow）。

**`CleanupIncomplete` の置き場・手順の名前・再実行の入口（導出 A-7・A-8。新しい Spec §10.1。target・A-S4b・未実装）**: 後始末の実行体と `CleanupIncomplete(parts)` の StateFlow は `auth/SubjectCleanup` が持つ（`AuthViewModel` への注入は `onSubjectLeave: (Subject) -> Unit`）。手順の名前は 5 値で、この順に実行する: `playback_stop`・`download_cancel`・`audio_cache`・`preferences`・`fcm_local_reset`。`parts` はその部分集合。再実行の入口は 2 つ（同じ主体で再ログインした後の明示 logout／次回起動の回収）で、専用の操作は作らない。旧: 置き場は `AuthViewModel` の StateFlow、手順の名前と数は保留（order の小決定が台帳に無かったため）。

**主体別の端末ローカル資産（ADR-104 決定 3・5〜9・16、SG-B3・SG-B6。target・A-S4・未実装）**: 音声キャッシュを主体ごとのディレクトリ `{cacheDir}/audio/{user_id}/{podcast_id}.mp3` に分ける（キーは backend の `user_id`）。既存の平置きキャッシュは初回起動時に全削除し、移行しない。起動時に、現在の主体以外のディレクトリを回収する（未認証・`user_id` の欠落・形式不正は全ディレクトリが対象）。起動時の失効（③）はこの回収で拾う。`Unknown` の間は回収せず、主体が確定した時点で 1 回行う（SG-B3）。ダウンロードジョブは開始時に主体を固定し、離脱時は cancel を試みるが完了を待たない。`user_id` は任意として扱い、欠けていればキャッシュを無効にして動作を続ける（決定 16・SG-B6）。現状: 平置き（`{cacheDir}/audio/{id}.mp3`）で、logout 時に端末単位で全削除する。規則の正本は共有仕様 §6.3・§6.5。

**ApiException（Platform）** — `Unauthorized` を追加（S0 で実装済み。`validateResponse` で `code == 401` を写像。既存の `HttpError(401)` 比較 `AuthViewModel.kt:152` は `Unauthorized` catch へ置換）。他の意味型（not_found / conflict / forbidden / server）は保留 slice（RF6）。

**SessionStore** — `save` の失敗を返す（`Boolean` か `Result`）。`login` は保存失敗時 `Authenticated` へ遷移せずエラー表示（OB-C20 / RF16）。`KeystoreSessionStore.clearBrokenState` は `onUnauthorized` と同じ通知経路を使わず（復号失敗は失効ではない）、`load()` が null を返した時点で `refreshAuth` が `Unauthenticated` へ落とす現状のままとする。

### 3.3 保留（境界と obligation のみ）

- Catalog（`Episode` decode・404/409 の意味・quota 期間）: 保留を解く slice は新しい Spec §8 の A-T2a・A-T2b（`Episode` と判別）・A-T4（失敗の意味）・A-T8b（quota）。OB-C11/C12/C14 はそれらの slice の契約として保持（旧: RF6 全面導入は学習サイクル）。
- Preferences registry（RF9）: 本書は速度の値域だけ確定。週目標・難易度は A-T5 で型にする（新しい Spec §5.4。OB-C15）。
- UiState の排他化（RF10）: OB-C16 / OB-B9 を保持。画面が読む値は、各 context の slice でリードモデルにする（新しい Spec §5 の TA-Q）。
- Screen 層の owner 分散（RF8）: `PodcastViewModel` の責務分割は INV-P1 導入後（RO6）= A-T3a。語彙登録・クイズ中継の分離は A-T7b（旧: 保留）。

## 4. 契約（Contract target）

既存 Contract Package の CI*（`docs/research-reports/2026-09-16-code-design-review/contract-package.md`）を再利用し、target 固有の契約を CI-T* として追加する。テスト仕様 T-T* は Given-When-Then と oracle（公開 API 経由）。

| CI | 対象 capsule | statement（要約） | 由来 | test |
|---|---|---|---|---|
| CI-T1 | PlaybackSession | 状態は §3.1 の union のみ。11 遷移以外は起きない。`Errored` は `Paused` / `Stopped` と区別できる | OB-C1, CI-P02, SG-R7 | T-T1: 純粋な遷移関数で 11 辺を観測（分母 11。各辺 1 ケース。A-S2a で確定・実装済み = `PlaybackSessionTest`）。Coordinator 経由の観測（`FakePlayerController` の状態注入と start / playNow / retry / advance の入力列）は A-S2b2。停止と「失敗にする」の代入は分母の外（SG-C24・C25・C52）。旧: Coordinator 操作の入力列だけで 11 遷移を観測 |
| CI-T2a | PlayerController（契約） | `Failed(reason)` は `Paused` / `Idle` と区別できる状態値。`reason` の分類表: `ERROR_CODE_IO_*` → Network、`ERROR_CODE_DECODING_*` / `PARSING_*` → Decode、`ERROR_CODE_IO_FILE_NOT_FOUND` / `BAD_HTTP_STATUS` → Source、他 → Unknown(code)（UK1 は A-S2a で確定・実装済み。分類の正本は `podcast/PlaybackState.kt` の `classifyPlaybackError` と `PlaybackStateTest` で、左の表は設計時の要約。食い違う場合はコードとテストが正） | OB-C2, CI-P03, G2 | T-T2a（JVM: `FakePlayerController` の状態注入と純関数 `classifyPlaybackError(code)` の表駆動） |
| CI-T2b | ExoPlayerController（配線） | `onPlayerError` が購読され `Failed` へ写像される | 同上 | **JVM 不可**（UV4）。androidTest 不在のため未検証として記録し、S2 の実機観測（UV3）で 1 回確認 |
| CI-T3 | ResumeRule | `resolveResumePosition` の表: (server 0, dur 600)→0、(120, 600)→120、(598, 600)→0、(599, 600)→0、(120, 0)→120、(−5, 600)→0 | OB-C3, CI-S04/S05, SG-R4 | T-T3（表駆動・`core/` の conformance と同形式） |
| CI-T4 | Coordinator | 開始後の position は resume に等しく、speed は既定速度に等しい。CACHED 経路は一覧の `Episode` の位置、NETWORK 経路は取り直した `Episode` の位置を使う（A-T2b まで DTO） | OB-C4, CI-P27, SG-R2 | T-T4: `FakePlayerController` の `seekTo` / `setSpeed` 引数を観測 |
| CI-T5 | Coordinator | INV-P1。完聴→advance→NETWORK 失敗の系列で `queue.current.id == session.episodeRef.id` かつ `Errored(FetchFailed)`。`retry()` が `startEpisode(queue.current)` を再実行 | OB-C5, CI-P13, SG-R1, SG-R7 | T-T5（既存 PodcastViewModelTest の系列を拡張） |
| CI-T6 | Coordinator | `UNAVAILABLE` → network 取得なし。手動の開始では状態を変えず、`errorMessage` に「オフライン」と分かる文言を出す（SG-C62）。キューが既にそのエピソードを現在にしている場合（advance 後・`retry()`）は `Errored(SourceUnavailable)`（SG-C52）。旧: 常に `Errored(SourceUnavailable)` | CI-S03, OB-C6 | T-T6（手動の開始の場合分けを含む） |
| CI-T7 | Queue | `init` は不変条件 1〜3 違反で throw。公開操作（`setQueue` の dedupe を含む）は正規化し throw しない。Q-01〜Q-32 不変。Q-33（重複 id があるときの開始位置）を追加（SG-C50） | OB-C17, CI-Q01/Q01b/Q02, SG-R10 | 既存 conformance 32 件（不変）＋ Q-33（SG-C50）＋ T-T7: 不正構築 3 例（index 範囲外・空で index 0・id 重複）で throw、`copy` も同様、`setQueue([a,a,b], 0)` → `[a,b]` |
| CI-T8 | Coordinator / PodcastApi | 完聴時の順序 = `markCompleted` → `updatePlaybackPosition(総時間)` の順に送り始める。`advance` と次の開始はその応答を待たない（SG-C61・導出 A-4）。送る値は player → `Episode`（A-T2b まで DTO）の優先順で得た総時間で、不明なら完聴時点の現在位置、それも 0 なら位置は送らない（SG-X1・SG-C54）。旧: `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。同一 episode の `markCompleted` は 1 セッション 1 回 | CI-N07/N08b, OB-C19, RF13 | T-T8: `FakePodcastApi` の呼出列 |
| CI-T9 | OfflineLibrary | CACHED 経路で player `Failed(Source|Decode)` → `invalidate(id)` → `isCached=false`、次回 `resolvePlaybackSource` は NETWORK | OB-C18, CI-N17, RF15 | T-T9 |
| CI-T10 | AuthViewModel | `refreshAuth`: `Unauthorized` のみ `clear` + `Unauthenticated`。`NetworkError` / `HttpError(5xx)` / `DecodingError` は `Unknown` 維持＋トークン保持＋`lastFailure` | OB-C7, CI-A03, SG-R6 | T-T10（既存 `AuthViewModelTest.kt:95` を反転） |
| CI-T11 | AuthInterceptor | 三点一致かつ `Authorization` を付与したリクエストの応答が 401 のとき `onUnauthorized` を 1 回呼ぶ。トークン無し（login / passkey）の 401・非一致 host の 401 では呼ばない。ヘッダ付与契約（CI-A13/A14）は不変 | OB-C8, CI-A12, SG-R6 | T-T11（既存 `AuthInterceptorTest` の `FakeChain` に応答を持たせる） |
| CI-T12 | AuthViewModel | 失効通知後: `Unauthenticated`、`sessionStore.load()==null`、§3.2 の事後条件の各手順（離脱主体の音声キャッシュ削除と DL cancel・再生停止・主体依存 4 key の削除）が呼ばれ、client は FCM の解除 API を呼ばない（ADR-104・SG-A6・SG-B4。旧: cleanup 3 手順 = 音声・FCM・再生停止）。未認証への遷移は後始末の完了を待たない（SG-X3）。logout・直接交代④も同じ事後条件（ADR-104 決定 3）。手順の名前は 5 値で、実行体は `auth/SubjectCleanup`（§3.2。導出 A-8。旧: 手順の名前と数は A-S4 の order が持つ） | OB-C9, CI-A18, SG-R5 | T-T12: Fake の呼出観測（PodcastViewModel の `stopForSubjectLeave` を含む） |
| CI-T13 | AuthViewModel | cleanup の一部が失敗しても残りは実行され、`CleanupIncomplete(parts)` が観測可能（`auth/SubjectCleanup` の StateFlow。`parts` は手順名 5 値の部分集合）。再実行（入口は §3.2 の 2 つ）で同じ事後条件 | OB-C10, CI-A17 | T-T13 |
| CI-T14 | SessionStore / AuthViewModel | `save` 失敗は呼出元へ返り、`login` は `Authenticated` へ遷移しない | OB-C20, CI-A07/A11, RF16 | T-T14（`InMemorySessionStore` に失敗注入） |
| CI-T15 | PreferencesStore | `setDefaultPlaybackSpeed` は 8 段以外を拒否し、直前の妥当値を維持する（§3.1 と同じ。SG-R3・親 docs `android-design.md` §7.2。旧「既定へ正規化」は §3.1 と矛盾していた）。server 同期経路（`AuthViewModel.syncPreferences`）も通る | OB-C15, CI-X02, CI-A20, SG-R3 | T-T15（`DataStorePreferencesStoreTest` に境界値） |
| CI-T16 | ApiClient / PodcastApi | production interface に throwing default が無い（`OkHttpApiClient` が全メソッドを override、`error("` の grep 0）。`PodcastViewModel` の再生 5 操作は `PodcastApi` 経由（語彙・クイズ 3 操作は A-T7b まで `ApiClient` のまま。A-T2b 以後、`PodcastApi` の戻り値は `Episode` で、`ApiClient` は `PodcastApi` を継承しない。実装は `network/PodcastApiAdapter`） | CI-N18, SG-R8, N3 | T-T16: 構造検査（grep）＋ `PodcastApi` の 5 メソッドを `OkHttpApiClientTest`（MockWebServer）で経路確認 |
| CI-T17 | PodcastViewModel 公開面 | `currentPodcast` は存在せず、UI は `nowPlaying` だけを読む | SG-R1, LF9, LF11 | T-T17: 構造検査（grep `currentPodcast` = 0）＋ `QueueSheet` の両者揃い条件が消える |
| CI-T19 | Coordinator | 位置同期（再生中の 15 秒周期・pause への遷移時の 1 回・停止直前・完聴時）は session が `Starting` / `Active` / `Completed` のときだけ送る。一時停止中は周期送信しない（SG-X4。共有仕様 PS-05b）。主体離脱では送らない（SG-C16）。`Errored` / `Stopped` / `NothingPlaying` では `updatePlaybackPosition` を呼ばない | gate 指摘 2, CI-P24 | T-T19: `Errored(FetchFailed)` 後に timer を進めても `FakePodcastApi.updatePlaybackPosition` が呼ばれない |
| CI-T20 | Coordinator | `play`/`playNow` の連続呼出は直列化され、位置同期 timer は常に 1 本（旧 timer が孤児化しない）。現行 `playMutex` / `syncJob` の挙動を pin | gate 指摘 8（現行テストに pin なし） | T-T20: 特性テストとして **S2 の baseline に先に追加**（2 回連続 `playNow` 後の `updatePlaybackPosition` の id が最後の episode のみ） |
| CI-T21 | AuthViewModel | `login` が `Unauthorized` を受けたとき文言は「ユーザーIDまたはパスワードが正しくありません」、`onUnauthorized` は発火しない | gate 指摘 4 | T-T21（既存 `AuthViewModelTest` の login 401 ケースを `Unauthorized` へ置換） |
| CI-T18 | CI | `testDebugUnitTest` / `lintDebug` / `assembleDebug` が独立ステップで、JDK は toolchain 17 | SG-R11, RF11 | ci.yml / build.gradle.kts の差分レビュー（テスト不可） |

coverage（design 時点）: CI-T 22 件（T1, T2a, T2b, T3〜T21）のうち JVM で検証可能な test 仕様あり 21、JVM 不可 1（CI-T2b）。実行状況は本書に書かない（進捗の正本は README の状態欄）。既存 CI のうち target で `met` へ変わる見込み: Q01/Q01b/Q02, P02/P03/P06/P13/P27, S03/S04/S05, A03/A07/A11/A12/A17/A18, N07/N17/N18, X02, A20（計 22 件）。**変えない**: A08/A10（Keystore、UV4）, X01/X06（週目標・難易度は保留）, N02/N09/P28（RF6 保留）。P24（位置同期の送信条件）は SG-X4 で「変わる側」へ移した（旧: SG-R9 現行維持で met のまま）。

## 5. カプセルと公開操作（Boundary / code design）

```yaml
code_design:
  capsules:
    - {id: CP1, name: PlayerController（拡張）, owns: [再生状態 union, position/duration/speed, Media3 error の分類], hides: [ExoPlayer, Handler/Looper, foreground service 起動, 500ms ポーリング], note: "isPlaying は派生値として残す"}
    - {id: CP2, name: Queue（core/PlaybackQueue）, owns: [QueueState と不変条件 1〜3（init）], hides: [配列操作], note: "共有仕様 §2 の公開操作をそのまま"}
    - {id: CP3, name: ResumeRule / PlaybackSource（core/）, owns: [resume 位置の決定, 再生元の決定], hides: [—], note: "純関数。conformance 表駆動"}
    - {id: CP4, name: PlaybackSession, owns: [セッション状態 union, "現在の Episode（A-T2b まで DTO）", セッション速度, INV-P1 の session 側], hides: [player の呼出順序（prepare→setSpeed→seek→play）]}
    - {id: CP5, name: "PlaybackCoordinator（PodcastViewModel 内。A-T3a で class として取り出す）", owns: [UC-P1/P3/P6 の判断: source 選択・resume・失敗方針・完聴順序・主体離脱時の停止], hides: [PodcastApi 呼出, port]}
    - {id: CP6, name: OfflineLibrary（AudioCacheManager）, owns: [保存庫, invalidate, "ダウンロード 3 操作（download / removeDownload / cancelDownloadsAndClearCache。A-S4 で CP5 から移す: SG-C17）"], hides: [ファイル体系]}
    - {id: CP7, name: AuthViewModel, owns: [AuthState 遷移, 失効 event の受領, 主体離脱の導出], hides: [me/login/logout の呼出, SessionStore], note: "後始末の実行と CleanupIncomplete は auth/SubjectCleanup（導出 A-8。旧: CP7 が持つ）"}
    - {id: CP8, name: AuthInterceptor（拡張）, owns: [ヘッダ付与条件, 401 検出], hides: [OkHttp], note: "onUnauthorized は関数注入"}
    - {id: CP9, name: PodcastApi（狭い port）, owns: [再生 UC が使う 5 操作の契約], hides: [ApiClient の残り 39 操作]}
  public_operations:
    - {capsule: CP1, ops: [prepare, play, pause, seekTo, setSpeed, stop, release, "state: StateFlow<PlaybackState>", "isPlaying/positionSeconds/durationSeconds/playbackSpeed（派生）", onPlaybackCompleted（当面維持。state.Ended で置換可）]}
    - {capsule: CP2, ops: [current, upNext, start, setQueue, add, playNext, jump, advance, remove, moveUpNext]}
    - {capsule: CP3, ops: [resolveResumePosition, resolvePlaybackSource]}
    - {capsule: CP5, ops: [playNow, playNext, addToQueue, removeFromQueue, moveUpNext, togglePlayPause, skipBackward, skipForward, seekTo, setSpeed, retry, stopForSubjectLeave, download, removeDownload, cancelDownloadsAndClearCache, "nowPlaying: StateFlow<NowPlaying?>（episodeId / displayTitle / japaneseIntroText / segments / vocabulary / quiz / difficulty）", "session: StateFlow<PlaybackSession>", "queue: StateFlow<PlaybackQueue>"], note: "download / removeDownload / cancelDownloadsAndClearCache は A-S4 で CP6（OfflineLibrary）へ移し、CP5 は中継のみ（SG-C17）"}
    - {capsule: CP7, ops: [refreshAuth, login, logout, completePasskeyLogin, applyProfileUpdate, onUnauthorized（内部: AppContainer が interceptor へ配線）, "authState", "lastFailure"], note: "cleanupIncomplete は auth/SubjectCleanup の StateFlow（導出 A-8。旧: CP7 の ops）。applyProfileUpdate は A-T6 で表示名だけを受ける形に改める"}
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
    - {subject: "ApiClient の consumer 別 10 port 分割", rationale: "RO4。再生系だけで足りる（SG-R8）。2026-09-30: ApiClient を割らない決定は保ち、context ごとの port を前に置く（新しい Spec §5.8・導出 A-24）"}
    - {subject: "AuthState の 4 状態化（unavailable 追加）", rationale: "Unknown + lastFailure で表現でき、MainActivity / AppScaffold の分岐を増やさない"}
    - {subject: "PlaybackService の interface 化", rationale: "RO7。Media3 shared-player 方式の制約。記録のみ（RF17）"}
    - {subject: "Episode decode（PlayableEpisode 判別共用体）", status: "撤回（2026-09-30。ADR-110 決定 8。A-T2a・A-T2b で Episode を導入する。新しい Spec §5.2・§8.2）", rationale: "旧: web S4 相当。android では PodcastStatusBadge が gate として機能しており、学習サイクルへ"}
    - {subject: "旧 PodcastViewModel と新 PlaybackSession の併存移行", rationale: "SG-R1（正本一意）と二重 owner が両立しない。特性テストで保護し、入口の差し替えは 1 slice（A-S2b2）で行う。2026-09-23 に「一括切替」を domain 新設 → 入口の差し替え → 旧実装の削除の 3 段へ再スライス（親 plan。§6）"}
    - {subject: "preferences 消去契約の追加", status: "撤回（2026-09-23 SG-A6。主体依存 4 key を消す契約を足す。A-S4）", rationale: "旧: SG-R5 で不採用。9 Fake へ波及"}
  dependency_direction: ["ui → ViewModel → core ← network/podcast(adapter)", "core ↛ android.*/network", "network ↛ auth（onUnauthorized は関数注入）", "PodcastViewModel ↛ ApiClient（PodcastApi のみ）", "目標の依存の規則は新しい Spec §4（TA-D1〜TA-D9）"]
  change_scenarios:
    - {id: CS1, name: replace implementation（ExoPlayer → 別 player / OkHttp → 別 client）, expected: pass, evidence: "PlaybackState union と PodcastApi の contract test（T-T1/T-T16）が不変"}
    - {id: CS4, name: change one business rule（完聴境界・失敗方針・速度段）, expected: pass（1 ファイル）, evidence: "core/ResumeRule / Coordinator / PlaybackConstants に閉じる"}
    - {id: CS5, name: test one consumer in isolation, expected: pass（再生系）, evidence: "FakePodcastApi 5 メソッド + FakePlayerController の状態注入"}
    - {id: CS2/CS3, name: add/change variant, status: not_applicable, rationale: "proven variant なし"}
```

**interface が露出してはならないもの（leakage guard）**: `Player`（ExoPlayer）を `PlaybackService` 以外が読むこと、`Handler/Looper`、`HttpError.code` を再生・認証 ViewModel が比較すること（401 は `Unauthorized`）、例外 message を UI 文言にすること（再生系の 5 箇所は `Errored(reason)` → 固定文言へ。A-T3b で `PlaybackNotice` に替える。文面は判断待ち: 新しい Spec §10.3。それまでは現行の文言を保つ）、`PodcastResponse` を UI が「再生中」の意味で読むこと（`nowPlaying` を使う）、`keepCurrentPodcast` のような整合フラグ。

## 6. 移行（Change Safety）— §8.3 の着手順に沿った slice

原則: slice ごとに **特性テスト（現行挙動の pin）→ RED（CI-T*）→ 実装 → 旧 path 削除条件の確認**。1 slice = 1 PR 目安。temporary path は owner・導入日・削除条件を持つ。検証は `JAVA_HOME=<JBR> ./gradlew testDebugUnitTest`（A-S3 で toolchain 固定後は `./gradlew` のみ）。

slice ID の正本は、親 docs の実行計画 `docs/plan/2026-09-16-design-review-refactor.md` と本リポの `docs/plan/2026-09-16-design-review-refactor/README.md` の接頭辞付き ID（A-*）。2026-09-23 に旧 S2 の「一括切替」（SG-R15）を 3 段（① domain の新設 = A-S2a／② 契約と入口の差し替え = A-S2b1・A-S2b2／③ 旧実装の削除 = A-S2c）へ再スライスし、主体別資産の A-S4 を足した。本書の他の節に残る旧 ID（S0〜S3）は下表の「旧 ID」で読み替える。**旧 S4（保留）は A-S4 とは別のもの**。進捗の正本は README の状態欄で、本書には書かない。

| slice（現行 ID） | 旧 ID | 内容 | 特性テスト（baseline） | RED（T-T*） | temporary path |
|---|---|---|---|---|---|
| A-S0 auth | S0（順 1） | `ApiException.Unauthorized`、`refreshAuth` の分岐、`AuthInterceptor.onUnauthorized`（`AppContainer` で配線。発火条件は §2）、`onSubjectLeave`（旧 `onLogoutCleanup` の rename・独立コミット、SG-R18）、`SessionStore.save` の失敗返却（RF16。§8.3「記録のみ」からの逸脱を SG-R16 で採用）。当初 S0 に含めていた「失効経路からも後始末を呼ぶ」「`CleanupIncomplete`」と、主体離脱の順序変更は S0 に入っておらず、A-S4 が持つ | `tests/auth/AuthViewModelTest`、`network/AuthInterceptorTest`、`network/OkHttpApiClientTest`、`InMemorySessionStoreTest` | T-T10, T-T11, T-T14, T-T21（T-T12・T-T13 は A-S4） | なし。**注意（gate 指摘 4）**: `AuthViewModel` の login の 401 分岐は失効ではなく **login の資格情報誤り**の文言分岐。`validateResponse` が 401 を `Unauthorized` にした後は `login` が `Unauthorized` を catch して現行文言「ユーザーIDまたはパスワードが正しくありません」を維持する（CI-T21 で pin）。他 ViewModel は 401 を比較していないため互換層は不要。`grep 'code == 401'` の到達点は S0 の帰結で 2 箇所（`AuthInterceptor` の発火条件と `OkHttpApiClient` の写像。旧: 0 を確認）で、以後は「2 箇所から増えない」で判定する |
| A-S1 test 基盤 | S1（順 2） | test 側 `BaseFakeApiClient`（全メソッド `error`）、9 Fake を継承化、`ApiClient` の throwing default 9 箇所削除、`PodcastApi` 切り出し（`ApiClient : PodcastApi`、`OkHttpApiClient` は不変）、`FakePodcastApi`、`FakePlayerController` に `state` 注入 | 全 ViewModel テストが green のまま | T-T16 | なし（Fake の継承化は挙動不変） |
| A-S2a playback domain | S2 の ①（domain の新設） | `PlaybackState` union（`ExoPlayerController` に `onPlayerError`）、`PlaybackSession`（11 遷移）、`ResumeRule`。新規コードのみで、既存の入口からは呼ばない | —（既存挙動を変えない） | T-T1（遷移関数）, T-T2a, T-T3 | なし |
| A-S2b1 queue / speed contracts | S2 の ②-1（Coordinator に触らない契約） | Queue `init` の不変条件検査と `setQueue` の dedupe（Q-33: SG-C50）、速度 8 段統一（`PlaybackConstants.speeds` を Double 1 本に）、`setDefaultPlaybackSpeed` の値域外拒否（直前の妥当値を維持）、`invalidate` 操作の追加。`PodcastViewModel` の再生 orchestration には触らない | 下の「旧 S2 の baseline」のうち該当分 | T-T7, T-T15, T-T9（操作部） | なし |
| A-S2b2 playback entry | S2 の ②-2（入口の差し替え） | `PodcastViewModel` の再生 use case を `PlaybackSession` 経由へ差し替える: `nowPlaying`、既定速度の適用、完聴順序、位置同期の送信条件、`invalidate` の接続、`stopForSubjectLeave`（`onSubjectLeave` へ追加。位置同期なし: SG-C16）。共有仕様 §2・Q-* の挙動は不変で、変わる挙動は ADR-103・SG-X1 / X4・ADR-105（SG-C24・C25・C51・C52・C54・C61・C62・C68）で確定した行に限る | 下の「旧 S2 の baseline」のうち該当分 | T-T4, T-T5, T-T6, T-T8, T-T9, T-T19（T-T20 は baseline） | **TP2** `onPlaybackCompleted` コールバックの維持（`state.Ended` で置換可能になるまで）。owner: user、導入: A-S2b2、削除条件: `PodcastViewModel` が `state` の `Ended` だけを購読するようになった時（A-S2c） |
| A-S2c playback cleanup | S2 の ③（旧実装の削除） | `_currentPodcast` / `keepCurrentPodcast` の削除、設定画面の速度 5 段の削除、UI 3 ファイルの読み替え完了、TP2 の削除 | — | T-T17 | なし |
| A-S3 CI | S3（順 4） | ci.yml を 3 ステップへ、`jvmToolchain(17)` | — | T-T18（差分レビュー） | なし |
| A-S4 subject cache | —（2026-09-23 新設。ADR-104） | 主体別の音声キャッシュと起動時の回収、ダウンロードジョブの主体固定、主体離脱の導出（遷移①②④）と順序（破棄 → 未認証または次の主体 → 後始末。待たない）、失効経路からの後始末、`CleanupIncomplete`、捕捉トークンでの logout、client の FCM 解除呼出の削除（SG-B4）、主体依存 4 key の削除（SG-A6）、ダウンロード 3 操作の `OfflineLibrary` への移設（SG-C17）。A-S2b1 が main に入った後に投入する（SG-C49）。着手条件は PR ごと: A-S4a（資産の主体化）は backend B-S5a、A-S4b（離脱の導出と順序・FCM の解除呼出の削除）は B-S5b の後（導出 A-19。旧: 分けていない） | 着手時点の全 unit テスト | T-T12, T-T13。共有仕様 SL-01・SL-02・SL-04・SL-06・SL-07・SL-08〜SL-10 | — |
| 位置同期（クライアント） | —（未起票・ID 未定） | target（ADR-109・SG-C67・C74・C76・C77）。§3.1 の位置同期の注記。A-S2c と backend B-S7 の完了後に起票する（SG-C79） | — | — | — |
| 目標アーキテクチャの補完 slice（A-T1〜A-T9） | S4（保留）を解く | 正本は新しい Spec §8。RF6 全面（意味型）= A-T4、RF8（Screen の owner）= A-T3a・A-T7b、RF9（週目標・難易度の値域）= A-T5、RF10（UiState 排他）= 各 context のリードモデル、`Episode` = A-T2a・A-T2b。旧: 学習・設定サイクルまで保留 | 各 slice の着手時点の全 unit テスト | OB-C11/C12/C14/C15/C16 と、新しい Spec の TA-V1〜V10 | 新しい Spec §8.2 の表 |

**旧 S2 の baseline（2026-09-16 時点の一覧。件数は当時の値）**: `podcast/PodcastViewModelTest`（52）、`core/PlaybackQueueConformanceTest`（32）、`core/PlaybackSourceResolverTest`（4）、`network/AudioCacheManagerTest`（14）、`podcast/PlaybackMetadataTest`、`podcast/PodcastStatusBadgeTest`、`settings/SettingsViewModelTest`（24。速度）、`preferences/DataStorePreferencesStoreTest`（5）の 8 ファイル。A-S2b1・A-S2b2 は、このうち自 slice が触る範囲を baseline にする。

**共有仕様の先行起票（SG-R17）**: 新節 §6.4（resume 規則: 完聴境界 2 秒窓・位置同期の送信条件・完聴時は総時間）と §6.5（主体離脱の事後条件）は起票済みで、§6.7 の Selection Gate も確定している。§6.5 の順序は「後始末の完了を待たない」（SG-X3・ADR-104。旧: cleanup 完了待ちの明文化）。

**A-S2b2 / A-S2c の scope 上の注意**: `AudioPlayerSection` / `PodcastScreen` / `QueueSheet` は `currentPodcast` の field（segments / vocabulary / quiz / japaneseIntroText）を直読みしているため、`nowPlaying` の view model にそれらを含める（A-S2b2 で定義）。`PlaybackService` は `ExoPlayerController.player` を読む現状を維持（RF17）。

**不可逆点**: 共有仕様の改訂は 3 platform に効く。gate 指摘 10 のとおり、現 §6.2 は「オフライン中の位置同期」、§6.3 は「logout 時のキャッシュ削除」が主題であり、完聴境界（resume 規則）・送信条件・失効時の主体離脱は **主題の変更**にあたる。追記ではなく新節（例: §6.4 resume 規則、§6.5 主体離脱の事後条件）として起票し、3 platform 合意を gate にする（SG-R17）。iOS は resume 規則で既に同挙動、web は「規則の追加」。§6.3 の「cleanup 失敗でも即座に未認証へ」は現行 android（`AuthViewModel.kt:167-182`、cleanup 完了待ち）と既に食い違う点も新節で明文化する。android 内部は UI 内部構造のみで、DataStore key・Q-01〜Q-32 は不変（Q-33 を追加: SG-C50）。**キャッシュ体系は A-S4 で平置きから主体別へ変わり**、既存の平置きキャッシュは初回起動時に全削除して移行しない（不可逆。ADR-104 決定 5・7。旧: キャッシュ体系は不変）。**backend 契約は、位置の書込の 1 点（記録時刻。ADR-109。target・未実装）を除いて不変**（旧: backend 契約は不変）。認証応答の `user_id`（ADR-104 決定 15）は、Android では任意として読む（決定 16）。
**rollback**: slice 単位の revert。入口の差し替え（A-S2b2）は 1 slice で行うため、baseline の特性テストが **すべて追加・green になってから** 切替に入る。A-S2a・A-S2b1 は既存の入口を変えないので個別に revert できる（旧: S2 は一括切替。2026-09-23 に 3 段へ再スライス）。

## 7. Requirement → UC → model → contract → test の trace

| R | UC | model / capsule | CI | test | status（design） |
|---|---|---|---|---|---|
| R1 業務ルール単一所有 | UC-P1/P2 | ResumeRule, PlaybackConstants（8 段）, refreshAuth の分類 | CI-T3, T-T15, CS4 | T-T3/15 | partial（RF6/RF8 の全面は新しい Spec の A-T3a・A-T4・A-T7b。旧: 保留 slice = 旧 S4） |
| R2 再生の不正状態なし | UC-P1〜P5 | PlaybackState, PlaybackSession, Queue init | CI-T1/T2/T5/T7/T9 | T-T1/2/5/7/9 | covered |
| R3 正本一意 | UC-P1/P2/P6 | queue.current + INV-P1, 既定速度→session, resume の reader | CI-T4/T5/T17 | T-T4/5/17 | covered |
| R4 失敗の意味 | UC-A1, UC-P1 | Unauthorized, Errored(reason) | CI-T6/T10 | T-T6/10 | partial（意味型全面は新しい Spec の A-T4。旧: 保留 slice = 旧 S4） |
| R5 失効の扱い | UC-A1 | AuthState 遷移表、AuthInterceptor 401 | CI-T10/T11/T14 | T-T10/11/14 | covered |
| R6 主体離脱でキャッシュ・再生状態なし | UC-A2 | onSubjectLeave + stopForSubjectLeave + CleanupIncomplete | CI-T12/T13 | T-T12/13 | covered（主体依存 4 key を消す: SG-A6。主体別キャッシュと直接交代④: ADR-104。旧: preferences は不採用を明記） |
| R7 本番経路のテスト | — | PodcastApi seam, BaseFakeApiClient, PlayerController 状態注入 | CI-T16 + 各 T-T | T-T16 + MockWebServer 経路の PodcastApi 5 件 | partial（androidTest は据置。CI-T2 の ExoPlayer 配線は未検証） |
| R8 CI ゲート | — | — | CI-T18 | 差分レビュー | covered（A-S3） |
| R9 共有仕様 | UC-P4/P6, UC-A2 | Queue（Q-01〜Q-32 不変・Q-33 追加）, ResumeRule（§6.4）, onSubjectLeave（§6.5・§6.3） | CI-T3/T7/T8/T12 | 既存 32 + T-T3/7/8/12 | covered（共有仕様の新節 §6.4 / §6.5 として起票済み: SG-R17。旧: §6.2/§6.3 追記が前提） |

**UC 側の coverage 分母**: UC 9 件のうち CI-T を持つのは UC-P1〜P6, UC-A1, UC-A2 の 8 件。CI 対象外 1 件: UC-S1（MediaSession。所有権は現状維持・RF17 記録のみ）。

## 8. 検証計画と decision

```yaml
verification_plan:
  per_slice: ["特性テスト green（baseline）", "T-T* RED → GREEN", "JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest（S3 以降は toolchain）", "S2 のみ: エミュレータ（AVD newslisten_e2e）で resume・既定速度・次エピソード失敗時の停止表示を手動観測（UV3）"]
  independent: ["slice ごとに code-review ロール 1 回（consolidated）", "S2 完了時に adversarial-review で CI-T1〜T9 の oracle を検算"]
  unexecuted_at_approval: [UV1 lint, UV2 T-T* 実装, UV3 実機観測, UV4 ExoPlayer 配線・Keystore（androidTest 不在）]   # 2026-09-16 の承認時点の値（旧 key: unexecuted_now）。進捗の正本は README の状態欄
  pre_implementation_gate: {role: architecture, result: "revise → 本版で必須 10 件・推奨 6 件を反映", artifact: "docs/research-reports/2026-09-16-code-design-review/spec-gate.md"}
selection_gates:
  # gate 指摘 5: 以下は §8 に無い本書固有の判断。2026-09-16 Q14〜Q19 で user が確定
  - {id: SG-R13, subject: "AuthState を 4 状態化せず Unknown + lastFailure で表す", owner: user, status: satisfied, decision: "adopt（2026-09-16 Q14）"}
  - {id: SG-R14, subject: "完聴時の位置同期の値を duration にする", owner: user, status: satisfied, decision: "adopt（2026-09-16 Q15）。順序 markCompleted → position(duration) → advance。2026-09-30: 順序は送信を始める順で次の開始は待たない（SG-C61）、値は総時間の優先順（SG-C54）"}
  - {id: SG-R15, subject: "S2 の切替方式（一括 / 段階）", owner: user, status: satisfied, decision: "一括（2026-09-16 Q16）。8 特性テスト＋T-T20 green が前提。2026-09-23 改訂: 3 段（A-S2a / A-S2b1・A-S2b2 / A-S2c）へ再スライス（親 plan。§6）"}
  - {id: SG-R16, subject: "RF16（SessionStore.save の失敗返却）を §8.3『記録のみ』から S0 へ繰り上げるか", owner: user, status: satisfied, decision: "S0 に含める（2026-09-16 Q17。§8.3 からの逸脱として記録。CI-T14 / T-T14 は S0）"}
  - {id: SG-R17, subject: "共有仕様の改訂を新節（resume 規則・主体離脱の事後条件）として 3 platform 合意 gate に分離するか", owner: user, status: satisfied, decision: "分離する（2026-09-16 Q18）。新節 §6.4/§6.5 を起票し、web/iOS 合意を S2 着手の前提にする"}
  - {id: SG-R18, subject: "onLogoutCleanup → onSubjectLeave の rename", owner: user, status: satisfied, decision: "rename する（2026-09-16 Q19。挙動不変の独立コミット）"}
decision:
  status: pass
  artifact_readiness: ready
  engineering_status: planned
  release_status: not_applicable
  decision_maturity: {status: approved, owner: user, approval_evidence: ["2026-09-16 user: Spec 承認（Q20）・SG-R13〜R18（Q14〜Q19）"], baseline_version: "2026-09-16", change_control: "本書を更新して再承認。2026-09-30 の更新は承認済みの決定（SG-* / A-* / ADR）の反映だけで、新しい決定を含まない（§9）"}
  next_phase: {name: "README の投入順に従う（承認時の次工程 = 共有仕様新節 §6.4/§6.5 の先行 PR → S0 実装 は完了）", status: allowed, human_approvals_required: []}
  resolved_unknowns:
    - {id: U1(backend resume), resolution: "backend は位置を duration に clamp して保存するだけ（podcasts.py:219-242）。server-wins は client 規則。ResumeRule は client に置く"}
    - {id: "iOS 速度適用", resolution: "iOS も既定速度を player に適用していない（同型欠陥）。android が先行して SG-R2 を実装し、iOS への展開は iOS 側判断"}
    - {id: "U1（streak と位置 PATCH の関係）", resolution: "依存する（backend は位置 PATCH の到達ごとに listeningDays を書く）。一時停止中の周期送信をやめた（SG-X4）"}
    - {id: UK1, resolution: "確定（A-S2a で実装済み）。Media3 errorCode の分類の正本は podcast/PlaybackState.kt の classifyPlaybackError と PlaybackStateTest"}
    - {id: UK5, resolution: "backend は duration に clamp して保存するだけ（上の U1(backend resume)）。完聴時は総時間を送る（SG-X1・SG-C54。共有仕様 §6.4）"}
  unknowns: [U2（markCompleted の backend 冪等性。client 側 1 回抑制で対処）, UK2（passkey login の URL が三点一致で Authorization 無しか。S0 で確認）, UK4（一覧再取得が queue を作り直すか。旧 S2 の特性テストで pin）]   # U1・UK1・UK5 は resolved_unknowns へ移した（2026-09-30）
  residual_risks: ["入口の差し替え（A-S2b2）は特性テストの追加を先行させないと回復手段が revert のみ（旧: S2 は一括切替）", "CI-T2（onPlayerError → Failed）の ExoPlayer 配線は JVM で検証できず、実機観測（UV3）に依存", "（解消）共有仕様の改訂は新節 §6.4 / §6.5 として起票済みで、Selection Gate も確定（SG-R17。旧: §6.2/§6.3 の追記が web/iOS の合意を要し、S2 の前提になる）", "PodcastViewModel の 6 責務のうち再生以外（語彙・クイズ・DL）は S2 で触らず残る（DL は A-S4a、語彙・クイズは A-T7b、一覧は A-T3a で出す。旧: RF8 保留）"]
```

## 9. 改訂履歴

本文を直したら、ここに 1 行足す（旧値を残す）。決定 ID の正本は親 docs `research-reports/2026-09-23-design-docs-mino-audit.md` §5（SG-*）・§5.0（A-*）と `adr/`。

### 2026-09-30（本文の表を現行値へ更新。承認済みの決定の反映だけで、新しい決定は含まない）

| 節 / 行 ID | 旧値 | 現行値 | 決定 ID |
|---|---|---|---|
| 冒頭の追記（SG-X3） | 主体離脱の順序変更は「S0 で実施」 | S0 では未実施。A-S4 で入る。S0 で入ったものを列挙 | 実コード（`auth/AuthViewModel.kt` の `logout()`・`onUnauthorized`。`CleanupIncomplete` は `app/src` に 0 件）、ADR-104 |
| §0 `public_contract_change_allowed`・§6 不可逆点 | backend API・キャッシュ体系・Q-* は不変 | backend API は位置の書込の 1 点を除いて不変（target・未実装）。キャッシュ体系は A-S4 で主体別へ変わる。Q-33 を追加 | ADR-109、ADR-104 決定 5・7・15・16、SG-C50 |
| §1.2 UC-P3・§3.1 `onEnded`・§4 CI-T8・§8 SG-R14 | `markCompleted` → `updatePlaybackPosition(duration)` → `advance`（応答を待つ）。値は duration | 送信を始める順で、次の開始は応答を待たない。値は player → DTO の優先順の総時間、不明なら現在位置、0 なら送らない | SG-C61・SG-C54・SG-X1・A-4 |
| §1.2 UC-P6・§3.1 位置同期・§4 CI-T19・coverage の P24 | 15 秒 timer は現状維持（一時停止中も送る。SG-R9） | 一時停止中は周期送信しない。pause への遷移時に 1 回。主体離脱では送らない | SG-X4・SG-C16 |
| §1.2 UC-A2・§3.2 事後条件・§4 CI-T12・§5 rejected_overdesign・§7 R6 | 音声キャッシュ全削除・FCM トークン解除・preferences は消さない（SG-R5）。cleanup 3 手順 | 離脱主体の音声キャッシュ・主体依存 4 key を消す。FCM 解除は client から呼ばずサーバの連鎖に任せる。完了を待たない | ADR-104 決定 1・2・9・10、SG-A6、SG-B4、SG-X3 |
| §1.3「主体が離れる」・§3.2 遷移表 | logout または失効の `Authenticated → Unauthenticated`。`apiClient.logout()` は best-effort | 遷移①②④（直接交代④の行を追加）。logout は破棄前に捕捉したトークンで送り await しない | ADR-104 決定 3・14 |
| §2 Notifications | FCM トークンの登録／解除 | 登録のみ。解除はサーバのセッション削除連鎖 | SG-B4、ADR-104 決定 10 |
| §2 Playback・§5 CP5 / CP6 | ダウンロード 3 操作は CP5（`PodcastViewModel`） | A-S4 で `OfflineLibrary`（CP6）へ移し、CP5 は中継のみ | SG-C17 |
| §3.1 `PlaybackState` の `Failed`・§4 CI-T2a・§8 UK1 | errorCode の分類は「実装時に確定」（unknowns） | 確定（A-S2a で実装済み。正本は `podcast/PlaybackState.kt` と `PlaybackStateTest`） | 実コード（android PR #36） |
| §3.1 `Errored` の保持値 | episode（取得前なら `episodeRef: {id}`） | `ref`（`SessionEpisodeRef`。A-S2a で実装済み） | 実コード（`podcast/PlaybackSession.kt`） |
| §3.1 遷移表の分母・§4 CI-T1 | Coordinator 操作の入力列で 11 遷移を観測 | 純粋な遷移関数 8 つで 11 辺（A-S2a で実装済み）。Coordinator 経由は A-S2b2。停止と「失敗にする」は分母の外の代入 | 実コード、SG-C24・C25・C52・C51 |
| §3.1 Queue・§4 CI-T7 | `setQueue` は先勝ちで dedupe。Q-01〜Q-32 不変 | 開始位置は元の入力で clamp して id を決める。Q-33 を追加 | SG-C50 |
| §3.1 ResumeRule・§7 R9・§8 residual_risks | 共有仕様 §6.2 / §6.3 へ追記する（web は web 側判断） | 新節 §6.4 / §6.5 として起票済み。web も追随 | SG-R17、SG-X2 |
| §3.1 `startEpisode`・§4 CI-T6 | 手動の開始でも `Errored(NotPlayable / FetchFailed / SourceUnavailable)` へ遷移 | 手動の開始は判定と取得の後でだけ状態を変える。失敗では状態を変えず `errorMessage` に理由を出す。`Errored` は advance 後と `retry()` だけで、Coordinator の代入 | SG-C62・SG-C68・SG-C52・A-1 |
| §3.1 `stopForSubjectLeave` | 位置同期 1 回 → stop → queue 空。`onLogoutCleanup` から呼ぶ | 位置同期は送らない。`onSubjectLeave` から呼ぶ | SG-C16、SG-R18 |
| §3.1 位置同期（追加） | 記載なし | target: 記録時刻・端末保存・再開時の確認（未実装・slice 未起票）。保留: 利用者の開始優先（Android） | ADR-109（SG-C67・C74・C76・C77・C79）、SG-C73 |
| §3.2（追加） | 記載なし | 主体別の端末ローカル資産（target・A-S4）。`CleanupIncomplete` の置き場などは保留として明記 | ADR-104 決定 3・5〜9・16、SG-B3・SG-B6 |
| §4 CI-T15 | 8 段以外を拒否（既定へ正規化） | 拒否して直前の妥当値を維持（§3.1 と同じ） | SG-R3（親 docs `android-design.md` §7.2） |
| §6 slice 表・rollback・§5 rejected_overdesign・§8 SG-R15 | S0〜S4、S2 は一括切替（8 特性テストが前提） | A-S0・A-S1・A-S2a・A-S2b1・A-S2b2・A-S2c・A-S3・A-S4（旧 ID との対応つき）。3 段分割 | 親 plan（2026-09-23 再スライス）、ADR-104、SG-C49 |
| §6 S0 行 | `grep 'code == 401'` = 0 を確認。T-T12・T-T13 を含む | 到達点は 2 箇所。T-T12・T-T13 は A-S4 | 実コード（`network/AuthInterceptor.kt`・`network/OkHttpApiClient.kt`） |
| §4 coverage・§8 `unexecuted_now`・`next_phase`・unknowns の U1 / UK5 | 「実行はすべて未」「unexecuted_now」「→ S0 実装」。U1・UK5 は unknowns | 進捗は README の状態欄が正本。U1・UK5 は解消済みへ移動 | SG-X4、SG-X1 |

### 2026-09-30（目標アーキテクチャの Spec に合わせた改訂。根拠は親 docs ADR-110 決定 8 と、新しい Spec の該当節）

| 節 / 行 ID | 旧値 | 現行値 | 根拠 |
|---|---|---|---|
| 冒頭 | 記載なし | 新しい Spec（`2026-09-30-implementation-spec-target-architecture.md`）との関係と優先を 1 段落で追加 | 新しい Spec §1.2 |
| §0 `out_of_scope` | 学習機能・設定画面の model は範囲外 | 新しい Spec の範囲に入った旨を注記 | ADR-110 決定 9、新しい Spec §5.4〜§5.6 |
| §1.3「Episode」・§2 Playback・§3.1 の `Starting` / `Errored`・§5 CP4・§5 rejected_overdesign「Episode decode」 | Episode は DTO のまま。decode の分離は保留 slice（学習サイクル） | Catalog の domain の型 `Episode` を A-T2a・A-T2b で導入する。セッションとキューは `Episode` を持つ | ADR-110 決定 2・8、新しい Spec §5.2・§8.2 |
| §1.3「再生可能」 | `PodcastStatusBadge.from(podcast)` が `None`（正本は `podcast/PodcastStatusBadge.kt`） | `Episode.Playable`（fail-closed。正本は `catalog/domain/Episode.kt`）。`PodcastStatusBadge` は A-T2b で削除 | 共有仕様 §6.6・PS-07・PS-07b、新しい Spec §5.2 TA-R-CT1 |
| §2 禁止事項の 2 行目・§3.3 の 4 行目・§4 CI-T16・§8 residual_risks | 語彙登録・クイズ中継の 3 操作の分離は RF8 の slice（保留） | A-T7b で Learning の application へ移す | 新しい Spec §5.5 |
| §2 の port の段落・§5 rejected_overdesign「10 port 分割」 | port は `PodcastApi` だけ。consumer 別の分割は作らない | `ApiClient` を割らない決定は保ち、context ごとの port を前に置く | 新しい Spec §5.8、導出 A-24 |
| §3.1 INV-P1 | `NowPlaying` の field の型の記載なし | `segments`・`vocabulary`・`quiz` は `Episode` の内容の型。`QueueView` を参照 | 新しい Spec §5.1 TA-M-PB |
| §3.1 ResumeRule・`onEnded`・§4 CI-T4・CI-T8 | 一覧 DTO / fresh DTO / DTO の `durationSeconds` | 一覧の `Episode` / 取り直した `Episode` / `Episode` の `durationSeconds`（A-T2b まで DTO） | 新しい Spec §8.3 の A-S2b2 の補正 9 |
| §3.1 PlaybackCoordinator の見出し・§5 CP5 | `PodcastViewModel` 内の関数群 | 移行の段階。A-T3a で class `PlaybackCoordinator` として取り出す。辺の選び方と player の事象の受け方は新しい Spec §8.3 の表 | 新しい Spec §5.1、導出 A-14・A-15・A-17 |
| §3.2 `AuthState` | `Authenticated(user)`（`user` の型の記載なし） | 現状 DTO `UserResponse`、目標 `AccountUser`（A-T6） | 新しい Spec §5.3 TA-M-AC |
| §3.2 主体が離れる事後条件・「保留（`CleanupIncomplete` の置き場…）」・§4 CI-T12・CI-T13・§5 CP7 | 置き場は `AuthViewModel` の StateFlow。手順の名前と数・再実行の入口は保留（契約にしない） | 置き場は `auth/SubjectCleanup`。手順の名前 5 値と順序、再実行の入口 2 つを契約にする | 導出 A-7・A-8（新しい Spec §10.1。A-S4 の小決定 (i)〜(v) を登録） |
| §3.3 保留の 4 行・§6 の「保留（学習・設定サイクル）」の行・§7 R1 / R4 | 学習機能・設定のサイクルまで保留 | 解く slice を新しい Spec の ID で書いた（A-T2a〜A-T9） | ADR-110 決定 8・9、新しい Spec §8 |
| §5 `dependency_direction` | 4 項目 | 目標の依存の規則（新しい Spec §4）への参照を追加 | 新しい Spec §4 |
| §5 leakage guard | 再生系の 5 箇所は固定文言へ | A-T3b で `PlaybackNotice` に替える。文面は判断待ち | 新しい Spec §10.3 の 1 |
| §6 A-S4 の行 | 着手条件を PR で分けていない。SL は 01・02・04・06・07 | A-S4a は B-S5a、A-S4b は B-S5b の後。SL-08〜SL-10 を追加 | 導出 A-19、新しい Spec §8.3 の A-S4 の補正 1・2 |
