## android リファクタ A-S4: 主体別音声キャッシュと主体離脱の事後条件（ADR-104 決定 1〜3・5〜9・14・16、SG-A6）

## 概要
音声キャッシュを主体（`user_id`）ごとのディレクトリに分け、起動時に他主体の残骸を回収し、ダウンロードジョブの主体を開始時に固定し、主体離脱を認証状態の遷移から導出して「トークン破棄 → 未認証または次の主体 → 後始末（完了を待たない）」の順に改め、logout は破棄前に捕捉したトークンで `POST /auth/logout` を送り（await しない）、client からの FCM 登録解除呼出を削除してサーバ側の連鎖削除に任せ、主体依存の端末設定 4 key を消す。共有仕様 §4.4 SL-06 / SL-07 の Android 側を green にし、SL-01 / SL-02 / SL-04 の完全な事後条件を満たす。正本は [ADR-104](../../../../docs/adr/104-subject-departure-and-subject-scoped-assets.md)（決定 1〜3・5〜10・14・16・追記 SG-A1/SG-A6。2026-09-23 user 判断 Q10/Q11/Q13 で確定した補足を含む）、共有仕様 `docs/design/shared-playback-spec.md` §4.4 SL-01〜SL-07・§6.3（性質 1〜5・Android 行）・§6.5（遷移 ①②④・事後条件表・分類表）、親 docs `docs/design/android-design.md` §7.2「主体離脱の検知」「主体離脱の後始末」「主体別の端末ローカル資産」・§7.3（A-S4 行）、Implementation Spec §3.2（主体が離れる事後条件・`CleanupIncomplete`）・§4 CI-T12・CI-T13。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T12・CI-T13 の抜粋＋SL-01〜SL-07 で足り、新しい契約 ID を作らない。

## 前提・着手条件
- 依存 slice: **B-S5**（`/auth/me`・login 応答の `user_id` 公開＝決定 15。backend の連鎖削除＝決定 10）と **A-S1**（`BaseFakeApiClient`。`logout` の署名変更を 9 Fake へ波及させないため）が main に merge 済み。A-S2b の merge は前提にしない（下記「後始末の手順集合」参照）。
- baseline: 着手時点の全 unit テストが green。
- 確定済みの判断（再提案しない）: 待機案（後始末の完了を待ってから遷移）は ADR-104 で棄却。離脱時の端末単位の全削除（主体を持たない `removeAll`）は決定 2 で不採用。平置きキャッシュの移行はしない（決定 7）。認証の猶予受理はしない（決定 17）。「8 key は消さない」は SG-A6 で撤回（Spec §5 rejected_overdesign「preferences 消去契約の追加」はこの撤回で上書き）。消す主体依存 key は **4 key**（`default_difficulty`・`default_playback_speed`・`weekly_goal_episodes`・`seen_achievement_ids`。分類表の Android 列「（未実装）」は誤りで router が訂正済み）。client からの FCM 登録解除呼出は削除し B-S5 の連鎖削除（決定 10）に任せる（Q11）。`Unknown` の間は起動時回収をしない（Q10。共有仕様 §6.3 性質 2 に追記）。
- 2026-09-23 の実測（本 slice が変える現状）: `AuthViewModel.onUnauthorized` は後始末を呼ばない（`AuthViewModel.kt:232`「order:12」）。`logout()` は `onSubjectLeave()` の完了後に `sessionStore.clear()`（`:342-350`。テスト `AuthViewModelTest.kt:704` が pin）。`CleanupIncomplete` は未実装（grep 0）。`AudioCacheManager` は `{baseDir}/audio/{id}.mp3` の平置き。`UserResponse` に `user_id` 無し。
- `docs/trial-log/`（android `auth-401-concurrent-refresh-race.md`・親）を最初に読む。

## 対象（android サブモジュールのみ。ファイル単位）
**変更（main）**
1. `model/UserResponse.kt`: `@SerialName("user_id") val userId: String? = null`（決定 16: 任意。欠落でも decode 成功）。
2. `network/AudioCacheManager.kt`: 保存先を `{baseDir}/audio/{user_id}/{podcast_id}.mp3` へ。公開操作はすべて主体を受ける: `cache(subject, id, bytes)` / `isCached(subject, id)` / `cachedFileUri(subject, id)` / `remove(subject, id)` / `cachedIds(subject)` / `removeSubject(subject)` / `reclaim(currentSubject: String?)`（`audio/` 直下の通常ファイル＝旧平置きを全削除し、`currentSubject` 以外のディレクトリを削除。`null` は全ディレクトリ削除）/ `cacheSize(subject)`。`subject` の形式検証は `validateId` と同じ `[A-Za-z0-9_-]`（決定 6）で、不正なら `InvalidId` と同型の例外。A-S2b が merge 済みなら `invalidate(subject, id)` も同じ形へ揃える。`removeAll()` は削除する。
3. `network/FileSystem.kt`・`JavaFileSystem.kt`: `listDirectories(dirPath)` と `deleteRecursively(path)` を追加（`FakeFileSystem` にも同じ実装）。
4. `podcast/PodcastViewModel.kt`: コンストラクタに `currentSubject: () -> String?` を注入（`tokenProvider` と同じ関数注入）。`download` は**開始時に `currentSubject()` を捕捉**して `performDownload` に渡し、書込は捕捉した主体のディレクトリへ（決定 9）。主体が `null`（未認証・`user_id` 欠落）なら `download` は何もしない（`downloadingIds` に入らない）、`isCached` / `cachedFileUri` は false / null（決定 16: キャッシュ無効で動作継続）。`cancelDownloadsAndClearCache()` を `cancelDownloadsAndRemoveSubject(subject)` へ改名: 進行中 Job を `cancel()`（**`join` しない**）し `cacheManager.removeSubject(subject)` を呼ぶ。離脱後に完了した書込は離脱主体のディレクトリに入る（SL-06）。
5. `auth/AuthViewModel.kt`: (a) `_authState` への書込を 1 箇所（`sessionLock` 内の遷移関数）に集約し、遷移 ①`Authenticated(A)`→`Unauthenticated`（logout）・②同（`onUnauthorized`）・④`Authenticated(A)`→`Authenticated(B)`（`login` / `completePasskeyLogin`。`A.userId != B.userId`、`userId` が片方でも null なら `username` 比較）で離脱主体 A を導出し、lock の外で後始末を `internalScope` に起動する（**完了を待たない**）。⑤`Authenticated`→`Unknown` は離脱でない（現行どおり `Unknown` へ落とさない）。`applyProfileUpdate` は同一主体の更新で離脱でない。(b) `logout()` の順序を「破棄前に `sessionStore.load()` でトークンを捕捉」→ `sessionStore.clear()` → `Unauthenticated` → 後始末（① で起動）→ `apiClient.logout(token)`（捕捉したトークンで既存の `Authorization: Bearer` を付けて `POST /auth/logout`。新ヘッダは作らない。`internalScope` で起動し **await しない**。best-effort・独立 try/catch）に改める。(c) `onSubjectLeave: suspend () -> Unit` を手順列 `List<CleanupStep(name: String, run: suspend (LeavingSubject) -> Unit)>` に変え、各手順を独立 try/catch で実行し、失敗した手順名を `cleanupIncomplete: StateFlow<CleanupIncomplete?>`（`parts: Set<String>`）に残す（CI-T13）。再実行は冪等。(d) `logoutsInProgress` ガードは残してよい（順序変更後は `sessionStore.load() != attachedToken` が同じ抑止を担う）。
6. `network/ApiClient.kt`・`OkHttpApiClient.kt`: `logout(token: String)` にし、渡されたトークンで既存の `Authorization: Bearer <token>` ヘッダを要求に付与する（決定 14。新しいヘッダ名は作らない。`AuthInterceptor` は `tokenProvider` が null のとき既存ヘッダに触れないため、破棄後でも届く。この要求の 401 は `attachedToken == null` なので `onUnauthorized` を発火しない）。
7. `preferences/PreferencesStore.kt`・`DataStorePreferencesStore.kt`・`InMemoryPreferencesStore.kt`: `clearSubjectScoped()` を追加し `default_difficulty`・`default_playback_speed`・`weekly_goal_episodes`・`seen_achievement_ids` の 4 key を削除する（共有仕様 §6.5 分類表・SG-A6）。`article_open_mode`・`time_format`・`sfx_enabled`・`haptics_enabled` は残す。
8. `di/AppContainer.kt`: `onSubjectLeave` を手順列で組む。**後始末の手順集合 = 着手時点の `onSubjectLeave` に含まれる手順をそのまま引き継ぐ**（2026-09-23 時点: `cancelDownloadsAndRemoveSubject`（旧 `cancelDownloadsAndClearCache`）・`fcmTokenRegistrar.onLogout()`。A-S2b が先に merge されていれば `stopForSubjectLeave()` も含まれている）＋ 本 slice で足す `preferencesStore.clearSubjectScoped()`。`PodcastViewModel` に `currentSubject = { (authViewModel.authState.value as? Authenticated)?.user?.userId?.takeIf(validateId) }` を注入。起動時の回収 `audioCacheManager.reclaim(subject)` を `AuthViewModel` の起動時解決が `Authenticated`（`user_id` あり・形式正）/ `Unauthenticated` / `Authenticated` だが `user_id` 欠落・形式不正（`null` 扱い＝全削除）に確定した直後に呼ぶ（`onAuthenticated` と同型の関数注入。遷移 ④ で B が確立したときも同じ回収を呼ぶ）。`Unknown`（SL-03: トークン保持・判定保留）の間は回収せず、`Authenticated` / `Unauthenticated` に確定した時点で同じ回収を 1 回走らせる（Q10 確定・共有仕様 §6.3 性質 2）。
9. `notification/FcmTokenRegistrar.kt`: `onLogout()` から `apiClient.unregisterDeviceToken(token)` の呼出と `fetchTokenOrNull()` を削除し、`isAuthenticated = false` のリセットだけ残す（Q11 = A。サーバ側の登録解除は B-S5 の連鎖削除＝決定 10）。手順集合には残す。

**変更（test）**: `auth/AuthViewModelTest.kt`（順序 pin `:704`、失効経路の非呼出 pin `:106, :562`、logout 中の失効通知 `:648, :674, :757` を仕様変更として反転。SL-01・SL-02・SL-04・SL-07 を追加。既存の SL-03（`CI-T10` の一時障害 3 件）・SL-05（`CI-T21`）のテスト名に行 ID を付す）、`network/AudioCacheManagerTest.kt`（主体別パス・`reclaim`・平置き削除）、`podcast/PodcastViewModelTest.kt`（`:1009, :1038` の `cancelDownloadsAndClearCache` 2 件を主体固定・不 join へ反転。SL-06）、`network/OkHttpApiClientTest.kt`（`logout(token)` のヘッダ）、`preferences/*PreferencesStoreTest.kt`（`clearSubjectScoped` 4 key）、`notification/FcmTokenRegistrarTest.kt`（`onLogout` が API を呼ばないことへ反転）、`model/AuthDecodingTest.kt`（`user_id` 有無）、`network/FakeFileSystem.kt`。

## 契約（CI → T / SL の対応）
| 契約 | 内容 | 検証 |
|---|---|---|
| SL-01 | 明示 logout: トークン破棄 → `Unauthenticated`（後始末の完了を待たない）→ 離脱主体のディレクトリが空・4 key 無し（再生停止は A-S2b の手順が集合にあればその呼出も観測） | `AuthViewModelTest`（後始末を `delay` させる double で `Unauthenticated` が先に観測される） |
| SL-02 | 保存トークン付き API の 401 → SL-01 と同じ事後条件 | 同上（`onUnauthorized(attachedToken)`） |
| SL-04 / CI-T13 | 手順の 1 つが失敗しても残りは実行され `CleanupIncomplete(parts)` が観測可能。再実行で同じ事後条件 | `AuthViewModelTest` |
| SL-06 / 決定 9 | 離脱時に cancel が間に合わず書込が完了する double → 書込は A のディレクトリ → 次回起動の `reclaim(B or null)` で A のディレクトリが消える | `PodcastViewModelTest`＋`AudioCacheManagerTest` |
| SL-07 / 決定 3 ④ | `Authenticated(A)` → `login(B)` で A の後始末が走り、B の確立は待たない | `AuthViewModelTest` |
| CI-T12 | 失効通知後・logout 後の事後条件（手順集合の全呼出） | T-T12（既存を拡張） |
| 決定 7・8 | `reclaim`: `audio/*.mp3` の平置きを全削除、`currentSubject` 以外のディレクトリ削除、`null` で全削除 | `AudioCacheManagerTest`（表駆動） |
| 決定 14 | `logout(token)` が `Authorization: Bearer <token>` を送り、破棄後に送っても届く。`AuthViewModel.logout` は送信完了を待たずに `Unauthenticated` へ遷移する | `OkHttpApiClientTest`（MockWebServer）＋`AuthViewModelTest`（`logout` を遅延させる Fake で遷移が先） |
| 決定 10 / Q11 | `FcmTokenRegistrar.onLogout()` は API を呼ばず `isAuthenticated` だけ落とす（以後 `onNewToken` で登録しない） | `FcmTokenRegistrarTest` |
| 決定 16 | `user_id` 欠落で decode 成功・`download` 無効・`isCached` false・起動回収は全削除 | `AuthDecodingTest`＋`PodcastViewModelTest` |

## 特性テスト（baseline。着手前に green）
`auth/AuthViewModelTest`（45）、`network/AudioCacheManagerTest`（14）、`podcast/PodcastViewModelTest`（52＋A-S2b 分）、`network/OkHttpApiClientTest`（46）、`notification/FcmTokenRegistrarTest`、`preferences/DataStorePreferencesStoreTest`（5）・`InMemoryPreferencesStoreTest`、`model/AuthDecodingTest`。

## 手順
1. baseline green を記録。
2. `UserResponse.userId` → `AuthDecodingTest` → GREEN。
3. `FileSystem` 拡張 → `AudioCacheManager` 主体別化（決定 7・8・16 の表駆動）→ RED → GREEN。
4. `PodcastViewModel` の主体固定・不 join（SL-06）→ RED → GREEN。
5. `PreferencesStore.clearSubjectScoped`（4 key）→ RED → GREEN。
5b. `FcmTokenRegistrar.onLogout` の API 呼出削除 → `FcmTokenRegistrarTest` 反転 → GREEN。
6. `ApiClient.logout(token)`（決定 14）→ RED → GREEN。
7. `AuthViewModel` の遷移導出・順序・`CleanupIncomplete`（SL-01・02・04・07、CI-T12・T13）→ 反転対象の既存テストを先に反転 → RED → GREEN。
8. `AppContainer` の配線（手順列・`currentSubject`・起動時回収）。
9. 1 slice = 1 PR。temporary path なし。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。
- SL-01・SL-02・SL-03・SL-04・SL-05・SL-06・SL-07 がテスト名に含まれ、T-T12・T-T13 が `verifies: CI-T12` / `CI-T13` を持つ。
- **参照 0 件**（対象集合 = `app/src/main` の全 Kotlin。除外 = `docs/`・`build/`・`app/src/test`）: `grep -rn 'removeAll()\|cancelDownloadsAndClearCache' app/src/main` = 0。`grep -rn '"audio"' app/src/main` の一致は `network/AudioCacheManager.kt` のみ。`grep -rn '_authState.value =' app/src/main/java/com/rioikeda/newslisten/auth/AuthViewModel.kt` = 1（遷移関数）。
- `grep -rn 'fun logout' app/src/main/java/com/rioikeda/newslisten/network` の署名がすべて `logout(token: String)`。`grep -rn 'unregisterDeviceToken' app/src/main` の一致は `network/ApiClient.kt`・`OkHttpApiClient.kt` の宣言／実装のみ（呼出 0）。
- `clearSubjectScoped` が消す key が `KEY_DEFAULT_DIFFICULTY`・`KEY_DEFAULT_PLAYBACK_SPEED`・`KEY_WEEKLY_GOAL_EPISODES`・`KEY_SEEN_ACHIEVEMENT_IDS` の 4 つと一致（`DataStorePreferencesStoreTest` で全 8 key を書いてから呼び、残る 4 key を検査）。
- 反転した既存テスト（`AuthViewModelTest.kt:106, 562, 704, 648, 674, 757`・`PodcastViewModelTest.kt:1009, 1038`）が PR 本文に「テスト名 → 決定番号 / 行 ID」で列挙されている。
- 後始末の手順集合が PR 本文に列挙されている（着手時点の集合＋`clearSubjectScoped`。FCM 手順はローカルリセットのみ）。
- grep oracle 回帰なし: `error("` = 0、`code == 401` = 0、`currentPodcast` の件数は本 slice で変えない。
- レビュー観点: `AuthInterceptor` が `auth/` を import しない。`PodcastViewModel` が `auth/` を import しない（主体は関数注入）。`core/` を触っていない。

## 禁止事項 / scope 外
- 後始末の完了を待ってから遷移する実装にしない（ADR-104 で棄却）。`cancelAndJoin` に戻さない。
- 主体を持たない全削除（`removeAll`）を残さない。平置きキャッシュを主体ディレクトリへ移行しない。
- client から `unregisterDeviceToken` を呼ぶ経路を残さない（server 側の登録解除は B-S5 決定 10 の連鎖）。`registerDeviceToken`（認証時の再登録）は変えない。
- 消す端末設定は 4 key に限る。`article_open_mode`・`time_format`・`sfx_enabled`・`haptics_enabled` は消さない。
- `user_id` を必須にしない（欠落でログイン不能にしない。決定 16 は Q13 で 3 platform 共通）。`Unknown` の間に回収を走らせない。logout 用の新しいヘッダ名を作らない。admin 一覧に `user_id` を求めない（決定 15）。
- `PlaybackSession` / `nowPlaying`（A-S2a〜c）・CI（A-S3）を触らない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 上記 grep 4 本の結果を PR 本文に貼る。

## 記録
- 反転したテスト・手順集合・`user_id` 欠落時の観測を PR 本文と `android/docs/trial-log/` に残す。
- 親 docs への返却事項: 共有仕様 §4.4 SL-06 / SL-07 と SL-01 / SL-02 / SL-04 の Android 側の保留を解除できる旨（§5 の解除条件）。`design/android-design.md` §7.2「主体離脱の検知」「主体離脱の後始末」「主体別の端末ローカル資産」を現状記述へ。
