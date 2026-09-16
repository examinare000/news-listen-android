# t5 敵対的独立評価（code design review §4 findings）

reviewed_by:
  kind: independent_evaluator
  role: adversarial-review（read-only。ソース・設定・git 未変更。サブエージェント未起動）
  subject: `scratchpad/review/report-findings.md` §4（RF1〜RF18）＋ §4.4
  quantitative_source: `scratchpad/review/verification-run.md`
  review_status: REJECT（scoped。18 件中 16 件 accepted、2 件で sub-claim が崩れた）
  method: 全 path:line を自分で `grep -Hn` / `awk`（FILENAME:NR 付き。連結出力の行番号は引用しない）で再取得して照合。
          report / verification-run 以外の他票レポートは参照していない。
  independence_note: 他票（第1票・第2票）のレポートは読んでいない。verdict は自分の grep 結果のみに基づく。

## 0. 総合判定

**REJECT（scoped）**。finding 一覧そのものは異例に精度が高く（citations_checked 64 / citations_wrong 0）、
top-8 の中核主張はすべて反証に失敗した = 崩せなかった。しかし次の 2 件は **記述された機構が Evidence で
支えられていない**ため、obligation の根拠として採用する前に訂正が必要。

- **RF9**: 「Screen 直書き 4 箇所が検証を迂回して不正値を構築できる」— 当該 4 箇所は型が全域（enum / Boolean）で
  値域違反を構築できない。不正値を構築できる経路は実質 1 本（server 同期）＋永続化による増幅。
- **RF2**: 「`ApiClient` interface 内の throwing default の message」を「UI 文言になる」経路の Evidence として
  挙げているが、`error()` は `IllegalStateException` を投げ、コード全域の `catch (e: ApiException)` に捕まらない。
  よって UI 文言化しない（クラッシュ経路）。かつ production の 9 件はすべて override 済み（実測）。

他に severity 導出の構造的欠陥 1 件（RF1 の P8 条件付き severity が (b) に適用できない）を指摘する。

## 1. finding 別 verdict

| ID | verdict | 反証試行の結果（自分の grep 根拠） |
|---|---|---|
| RF1 | accepted / severity weakened | (a)(b)(c)(d) すべて再現。`auth/AuthViewModel.kt:111-114` は `catch (e: ApiException)` → `sessionStore.clear()` → `Unauthenticated`（型で NetworkError を除外していない）。`_authState.value` の代入点は 102/107/113/149/181/197/211 の 7 箇所のみで、**セッション中の 401 を起点とする代入は 0**（(b) 成立）。`onLogoutCleanup` の呼出は `:174`（logout 内）だけ、実体は `di/AppContainer.kt:266-281`（cache + FCM）で失効経路に結線なし（(c) 成立）。`network/KeystoreSessionStore.kt:88-91` `clearBrokenState()` は `prefs.edit { remove(...) }` のみで通知なし（(d) 成立）。CI-A03 の contradictory も実在: `AuthViewModelTest.kt:95` のテスト名が `トークンありでmeがNetworkErrorでもトークン破棄してUnauthenticatedになる`。**ただし §2 の severity 批判あり** |
| RF2 | **weakened（sub-claim 崩れ）** | 本体は成立（`ApiException.kt:13` = `"HTTP Error $code"`、`:19` = `"Network error: ${cause.message}"`、`e.message` → UI が 2 ファイル 8 箇所で完全一致）。崩れたのは `ApiClient.kt:66-67`（default 9）を同一 finding の Evidence に並べた点: `error(...)` は `IllegalStateException` であり `ApiException` の sealed 階層外。`_errorMessage` 代入は全て `catch (e: ApiException)` 配下なので UI 文言化経路が存在しない。さらに 9 default は `network/OkHttpApiClient.kt` が**全件 override 済み**（9/9 を個別 grep で確認）なので production 到達もしない。加えて §4.1「Constraint（QL4 confidentiality）違反」への配置は過大: 実害の主は UX/i18n（日本語 UI に英語 transport 文字列）で、host 名は BuildConfig・TLS SNI に既出のため機密ではない |
| RF3 | accepted | 2 authority 実在（`podcast/PodcastViewModel.kt:83-86` の `_currentPodcast` と `:115-121` の `_queue`）。経路(a)を自分で構成: `play()` は `playMutex.withLock` 内でまず `stopInternal()`（既定 `keepCurrentPodcast=false`）を呼び `:509` で `_currentPodcast.value = null`、その後 NETWORK 分岐の `fetchPodcast` が失敗すると `:309` で errorMessage のみ設定 → `queue.current` は `:361-362` の `advance` 適用済みで next のまま。乖離成立。経路(b)（ゲート拒否時）も `:363-368` で `next != null && gateError != null` → `keepCurrentPodcast = (next == null) = false` により同様。自認コメントは `:341-346`（「（review指摘）」）に実在 |
| RF4 | accepted | `playbackPositionSeconds` の main 側参照は `model/PodcastResponse.kt:31` の宣言 1 箇所のみ（reader 0）。`resolveResumePosition` は main に 0 hit。`beginPlayback`（`:324-331`）は `prepare` → `play` → `startPositionSync` で seek 呼出なし。`ExoPlayerController.kt:117-135` の `prepare` も位置引数を持たない（`setMediaItem`/`prepare` のみ）。一時停止中も PATCH 継続は `:521-532` の `while (isActive) { delay; syncPosition }` に isPlaying ガードがないことで成立し、`:517-519` に spec 改訂候補の自認コメントあり |
| RF5 | accepted（+ N1 追加機構） | `podcast/PlaybackConstants.kt:12` = `listOf(0.5f,0.75f,1.0f,1.25f,1.5f,1.75f,2.0f,2.5f)`（8 段 Float）vs `settings/SettingsScreen.kt:1361` = `listOf(0.75,1.0,1.25,1.5,2.0)`（5 段 Double）。二重定義・不一致とも確認。`setSpeed` の呼出元は `AudioPlayerSection.kt:285` → `PodcastViewModel.kt:465-467` → `ExoPlayerController.kt:169` の 1 系統のみで、`preferencesStore.defaultPlaybackSpeed`（`PreferencesStore.kt:22`）を player へ渡す箇所は存在しない（`beginPlayback` にも無い）。「既定速度が適用されない」を反証できなかった |
| RF6 | accepted | 件数主張を独立に再計測して一致（§3 の表参照）。8 ファイル 8 箇所すべて自分の grep で同一行に到達。`ApiException` は 4 種のみ（`ApiException.kt:8-20` 全文確認）で unauthorized/forbidden/not_found/conflict/server の型なし。404 の 3 意味も実在（`OkHttpApiClient.kt:303` 冪等成功 / `SettingsViewModel.kt:160`・`ListeningStreakStore.kt:56` 機能未提供 = `loadFailed = e.code != 404` / `QuizSheet.kt:195` Composable 内判定）。429 は `validateResponse`（`:456-463`）で唯一の生成点＝ consumer 側で再発明されていないことも確認 |
| RF7 | accepted / latent-risk に限定 | 44 メソッド = abstract 35 + default 9 を `fun` 宣言の全列挙で独立に数えて一致。Fake 9 ファイルの行数を `wc -l` で再計測し **1,393 完全一致**（account151/auth146/feed156/notification146/observability142/onboarding142/passkey158/podcast194/settings158）。§3g の per-file 内訳と 1 行も違わない。`MockWebServer` を使う test ファイルは 2 件のみ（`OkHttpApiClientTest` / `OkHttpApiClientLearningTest`）で 46+7=53 と整合、`app/src/` は `debug/main/test` のみ（androidTest 不在）、`jacoco`/`detekt`/`ktlint` は `build.gradle.kts` に 0 hit。**弱める点**: 「override 漏れを compile で捕まえない」は現時点では潜在リスク。9 default は `OkHttpApiClient` が全件 override 済みで現に漏れている実装は無い |
| RF8 | accepted / RC6 露出あり | 業務ルール散在は実在: `AppScaffold.kt:115` と `di/AppContainer.kt:409` が同一式 `?.user?.role == "admin"`、`settings/SettingsViewModel.kt:128` が `isAdminProvider()` の二重ガード、`SettingsScreen.kt:511` に `generationQuota!!.limit == 0`、`QuizSheet.kt:50` に `correctRate >= 0.5`、`SettingsScreen.kt:1361` に選択肢。**弱める点**: 「13 StateFlow・6 責務・1362 行・13 セクション」は規模メトリクスで、単独では RC6（class 数・行数を根拠にする）に触れる。finding を支えているのは重複した business rule の所在であって規模ではないので、規模句は補助に格下げすべき。また required_action の「`PodcastViewModel` は 1 関心事 1 単位へ」は RO6（authority 統一前の分割は棄却）を参照しているが、**順序前提（RF3 の authority 統一後）が required_action 本文に書かれていない** |
| RF9 | **weakened（機構が崩れた）** | 検証点が週目標 1 箇所（`settings/SettingsViewModel.kt:193-197` の `if (value !in WEEKLY_GOAL_OPTIONS)`）のみ、`syncDefaultPlaybackSpeed`（`:187-191`）に検証なし、store setter（`DataStorePreferencesStore.kt:81-87,105-107`）に検証なし — ここまでは成立。崩れたのは「3 経路」: 引用された Screen 直書き 4 箇所は `:245` `setArticleOpenMode(ArticleOpenMode.entries[index])` / `:259` `setTimeFormat(TimeFormat.entries[index])` / `:271` `setSfxEnabled(enabled)` / `:278` `setHapticsEnabled(enabled)` で、**すべて型が全域（enum 実体・Boolean）であり `weeklyGoalEpisodes ∉ {3,5,7,10}` も速度 0.0 も構築できない**。実際に不正値を構築できるのは `auth/AuthViewModel.kt:124-126`（server 値を無検証で setter へ）1 本だけで、DataStore 復元はその値の再生（増幅）であって独立経路ではない。訂正案: 主張を「不正値の構築経路は server 同期 1 本＋永続化による恒久化」に限定し、Screen 直書き 4 箇所は RF8（owner 分散）側の Evidence へ移す |
| RF10 | accepted | `learning/LearningViewModel.kt:16` `isLoading` / `:22` `loadFailed` / `dashboard` が独立フィールド（直積）。`LearningScreen.kt:72` `isLoading && dashboard == null` と `:86` `loadFailed && dashboard == null` で排他を復元。`dashboard!!` は 118/143/171/172/177/181 の **6 箇所で完全一致** |
| RF11 | accepted（1 点未検証） | `.github/workflows/ci.yml` は `android-test` ジョブが `./gradlew build --stacktrace` 1 ステップ（`:35`）＋ `secret-scan` の gitleaks（`:46`）で、test/lint/build の識別不能を確認。temurin 17 も `:25-26` で一致。detekt/ktlint/JaCoCo 0 hit。**未検証**: Dependabot 未導入（`.github/dependabot.yml` の有無を確認していない） |
| RF12 | accepted | `ExoPlayerController.kt:73-114` の `Player.Listener` の override は `onPlaybackStateChanged` と `onIsPlayingChanged` の **2 種のみ**で `onPlayerError` は不在（全文走査）。`:87-98` で STATE_ENDED と STATE_IDLE がともに `_isPlaying.value = false` → error/ended/idle/paused が同値に潰れることを確認。clamp 非対称も成立: `PodcastViewModel.kt:445-448` skipBackward は `coerceAtLeast(0.0)`、`:452-456` skipForward は `coerceAtMost(duration)`、`:460-461` `seekTo` は素通し、`ExoPlayerController.kt:161-163` も clamp なし。`setSpeed(speed: Float)` は値域制約なし |
| RF13 | accepted（inferred 表記も妥当） | `handlePlaybackEnded`（`:348-370`）で `markCompleted` は `catch (_: ApiException)` で黙殺。最終位置保存は `play(next)` → `stopInternal()` → `syncPosition(podcastId)` の副作用で、その値は `playerController.positionSeconds.value`。`ExoPlayerController` の STATE_IDLE 分岐が `_positionSeconds.value = 0.0` にリセットするため ENDED/IDLE のタイミング依存も成立。二重同期: `next == null` → `stopInternal(keepCurrentPodcast = true)` で `_currentPodcast` が残り、次の `play()` 冒頭の `stopInternal()` が同じ id で再度 `syncPosition` する（`:502` の `?: return` ガードを通過する）。inferred ラベルは適切 |
| RF14 | accepted | `core/PlaybackQueue.kt:25` `val currentIndex: Int? = null` は data class の public constructor 引数で init 検査なし → `PlaybackQueue(items = 2件, currentIndex = 5)` / `copy` が構築可能。`:29` `current` は `items.getOrNull(it)` で null に潰れる。finding 自身が「現 production 経路では in-range」と自己限定しており過大主張になっていない |
| RF15 | **unverifiable** | `network/AudioCacheManager.kt:54-69` と `core/PlaybackSourceResolver.kt:16-21` を読めていない（turn 予算）。RF12 の error 未観測（accepted）と組み合わせた「自己復旧しない」という含意は論理として整合するが、独立照合していない |
| RF16 | accepted | `network/SessionStore.kt:13` `fun save(token: String)` は戻り値なし（`:12-19` に load/clear を含めて 3 メソッド）。`auth/AuthViewModel.kt:147-149` は `sessionStore.save(...)` の直後に無条件で `_authState.value = AuthState.Authenticated(...)`。保存失敗を検出する術がないことを確認。`KeystoreSessionStore.kt:40-53` は未読（`:82` の `Log.w` のみは確認済み） |
| RF17 | partially unverifiable / low は妥当 | `ExoPlayerController.kt:143,202-203` と `playbackservice/PlaybackService.kt:37-39` を読めていない。ただし §2 の理由で low（記録のみ）の disposition は支持できる |
| RF18 | accepted（citation 完備） | ハードコード文言は `account/AccountViewModel.kt:139-140` に実在（`"パスワードは12文字以上で、英大文字・英小文字・数字・記号のうち3種類以上を…"`）。`:106-110` は KDoc（根拠の記録）で、文言本体ではないが `:139-140` が併記されているので Evidence 集合としては完備。`:121-126` の `when (e.code) { 422 -> PASSWORD_STRENGTH_ERROR_MESSAGE }` によりクライアント事前検証なし・422 依存も成立 |

集計: accepted 13 / accepted-with-weakening 3（RF1・RF7・RF8）/ **weakened=sub-claim 崩れ 2（RF2・RF9）** / refuted 0 / unverifiable 1（RF15）＋partially 1（RF17）。

## 2. router の severity / routing 判定の評価

### RF1「P8（端末共有なし）成立なら major、偽なら blocker」への降格
**部分的に不当（構造的欠陥）**。降格そのものは妥当: (c)(d) の残留物（音声キャッシュ・FCM トークン・preferences 8 key）
と (a) の強制再ログインは、いずれも「同一端末に別主体がいる」前提がなければ機密の露出に至らない。
しかし **(b)（セッション中 401 が authState に反映されず、失効後も `Authenticated` のまま全 API が失敗）は
機密性の問題ではなく可用性の問題であり、P8 の真偽に一切依存しない**。P8 が真と判断された瞬間に (b) の severity が
confidentiality 由来の条件付き major に吸収され、「P8 が真だから major に落とせる」という推論が (b) に誤って波及する。
訂正案: RF1 を (a)(c)(d)＝P8 条件付き / (b)＝無条件 major（可用性）に分割する。分割しない場合、§8 の人間判断で
P8=true が選ばれたときに (b) の修正優先度が根拠なく下がる。

### RF17 を low（記録のみ）とした判定
**妥当**。`podcast` ⇄ `playbackservice` の相互参照は `MediaSessionService` が同一 `Player` インスタンスを
必要とする Android プラットフォーム制約に由来し、RO7 で変更が棄却されている。所有権が doc で規律化されているなら
変更コストが便益を上回るという判断は反証できない。ただし引用 3 行を自分で読めていないため、
「生 `Player` の公開」が実際に `service locator 経由の具象依存`である点は未照合（下記 unknown へ）。

### RF2 を minor とした判定
**severity は妥当、配置は不当**。transport 文字列の露出は機密ではなく UX 劣化が主なので minor で正しい。
一方で §4.1「Constraint（QL4 confidentiality）違反」節に置くと、constraint 違反 = 2 件という節の重みが
1.5 件相当に薄まる。RF2 は §4.2（boundary）へ移すのが正確。

### RF18 を cross-module SG に送った判定
**妥当**。統一すべき正本値は backend（`shared/password_policy.py`）にあり、backend 契約は §4.4 で
out_of_scope 宣言済み。android 単独で文言値を決めれば 3 者不整合を固定化するため、
「backend で確定してから各クライアント」という順序は正しい。android 側の required_action を
「文言値の変更」ではなく「整合確認の obligation」に留めている点も RC7 と矛盾しない。

## 3. 件数主張の独立再計測

| 主張 | 報告値 | 自分の再計測 | 一致 |
|---|---|---|---|
| status 数値比較（consumer） | 8 ファイル 8 箇所 | `AuthViewModel.kt:152`(401) / `AccountViewModel.kt:122`(when 400,422) / `SettingsViewModel.kt:160`(404) / `ListeningStreakStore.kt:56`(404) / `OkHttpApiClient.kt:303`(404) / `QuizSheet.kt:195`(404) / `OnboardingViewModel.kt:86`(409) / `PasskeyRegistrationViewModel.kt:54`(409) = 8 ファイル 8 箇所 | ✓ |
| 生成側の除外 | `OkHttpApiClient.kt:458` の `== 429` を除外 | `validateResponse` 内の唯一の生成点で除外が正しい | ✓ |
| `e.message` → UI | 2 ファイル 8 箇所 | PodcastViewModel 135/212/214/251/309 + FeedViewModel 89/106/204 = 8 | ✓ |
| `ApiClient` メソッド数 | 44（abstract 35 + default 9） | `fun` 宣言 44 件を全列挙、うち body 付き `error(...)` が 9 件（66/71/129/133/137/141/145/149/153） | ✓ |
| Fake | 9 ファイル 1,393 行 | `wc -l` 合計 1,393、per-file も §3g と完全一致。他の Fake 3 件（FakeFileSystem 35 / FakePasskeyProvider 30 / FakePlayerController 133）は ApiClient 実装でないため除外が正しい | ✓ |
| production HTTP 経路 | 53 / 528 | `MockWebServer` 使用は 2 ファイルのみ（46+7=53）。528 は V3 の XML 集計で追試していない（下記 unknown） | 分子✓/分母未追試 |
| preferences key | 8 key | `DataStorePreferencesStore.kt:120-127` に KEY_* が 8 個（cited range 117-128 の内側） | ✓ |
| `dashboard!!` | 6 箇所 | LearningScreen 118/143/171/172/177/181 | ✓ |
| 速度選択肢 | 8 段 vs 5 段 | PlaybackConstants 8（Float）/ SettingsScreen 5（Double） | ✓ |

追加観測: 速度の**型も** Float / Double で不一致（RF5 は段数の不一致のみ指摘）。

## 4. rejection criteria 合否

| RC | 判定 | 根拠 |
|---|---|---|
| RC1（path:line なし / 未確認転記） | **pass（軽微な瑕疵 1）** | 18 件すべてに path:line あり。例外は RF11 の `.github/workflows/ci.yml`（行番号なし・ファイル単位）と、RF4/RF14 の `spec §6.2`/`§2.1`（doc アンカー）。未確認転記は検出できず: verification-run 由来の数値は自分の再計測と一致し、`:309`/`:1361`/`:12`/`:56`/`:195` のような転記しやすい値も全て正しい |
| RC2（棄却済み案の再提案） | **pass** | trial-log 2 件を読了。`featured-categories.md` の棄却「`getCategoryLabel` の @StringRes unit test（Context 必要のため削除、UI テストで担保）」は §4 で再提案されていない（@StringRes・getCategoryLabel の言及 0）。factory/Strategy 階層・汎用 Storage port・Clock port・QueueState branded type も 0。RO4（44 分割）・RO6（authority 統一前の PodcastViewModel 分割）・RO7 は **棄却済みとして参照**しており再提案ではない。ただし RF8 の required_action は RO6 の順序前提を本文に書いていない（§1 参照）ので conditional |
| RC3（subject_verdict と artifact_readiness の混同） | **pass** | §4 見出しが「severity は proposed、§7.1 の独立評価と §8 の人間判断で確定」と明示し、§4 自体は verdict を出していない。RF17 の「変更しない。ADR に記録」は finding の disposition であって readiness 宣言ではない |
| RC6（pattern 名・class 数を根拠にする） | **conditional** | RF7 の件数（9 ファイル / 1,393 行 / 44 メソッド）は「1 メソッド追加で 9 ファイルが同時に壊れる」という機構に接続されており、単なる計数ではない。RF8 の「13 StateFlow・6 責務・1362 行・13 セクション」は機構への接続が弱く、規模メトリクス単独に見える箇所がある（§1 RF8） |
| RC7（スコープ外: backend 契約・iOS/web・意匠・署名） | **pass** | §4.4 が backend 契約・iOS/web 実装・UI 意匠を out_of_scope と明示、release `signingConfig = debug` も out_of_scope として除外。iOS/web への言及は「正本の所在」「他 platform の選択肢」の参照に留まり、iOS/web の実装を finding 化していない |
| RC8（web の人間判断を android で選択済み扱い） | **pass** | RF18 は web SG7（8〜20 文字）を「web レビューは user 判断で採用済み」と出所付きで記述し、android の required_action は「統一値を backend 正本で確定してから」＝未決として扱っている。android で選択済みに見せる記述はない |

## 5. 新規 finding 候補（自分の grep で構成したもののみ）

- **N1（minor / RF5 の未記述機構）**: `settings/SettingsScreen.kt:182-183` は `PLAYBACK_SPEEDS.indexOfFirst { it == defaultPlaybackSpeed }`
  という **Double の完全一致比較**で、不一致なら `?: PLAYBACK_SPEEDS.indexOfFirst { it == 1.0 }` に落ちる。
  player UI 側で選べる 0.5 / 1.75 / 2.5（`PlaybackConstants.kt:12`）や server 由来の中間値は設定画面で必ず 1.0x と表示され、
  `:654-659` の再同期でも同じ経路を通る。結果「保存値と表示値が恒久的に食い違う UI」が成立する。
  RF5 は段数の二重定義までしか述べておらず、この silent value-loss（および Float/Double の型不一致）は未記述。
- **N2（minor / RF9 の観測可能な帰結）**: `settings/SettingsScreen.kt:186` は
  `WEEKLY_GOAL_OPTIONS.indexOf(weeklyGoalEpisodes).coerceAtLeast(0)` で、範囲外値を **無言で option[0] として表示**する。
  さらに `:305` は `weeklyGoalEpisodes / 7.0` を無ガードで計算する。RF9 が示す invariant 破れが
  「気付けない UI」として現れる点は RF9 に含まれていない。
- **N3（low / RF7 の failure mode 記述の補強）**: `ApiClient` の 9 default は `error(...)` = `IllegalStateException` を投げる。
  コード全域のハンドラは `catch (e: ApiException)` なのでこれを捕まえず、**override 漏れの失敗モードは
  「処理されたエラー」ではなく「クラッシュ」**になる。現状 `OkHttpApiClient` が 9/9 を override 済みで潜在に留まるが、
  RF7 の「compile で捕まえない」に加えて「runtime でも handled error にならない」を書くと obligation の形が変わる
  （＝throwing default 除去の優先度が上がる）。

## 6. unknown / unverifiable（到達できなかった主張）

- RF15 の Evidence（`network/AudioCacheManager.kt:54-69`、`core/PlaybackSourceResolver.kt:16-21`）— 未読。
- RF17 の Evidence（`podcast/ExoPlayerController.kt:143,202-203`、`playbackservice/PlaybackService.kt:37-39`）— 未読。
- `network/ApiClient.kt:37`、`network/LearningApi.kt:9-11`、`network/VocabularyTestApi.kt:8-9`、
  `podcast/PlayerController.kt:26-35`、`podcast/PodcastScreen.kt:160`、`podcast/QueueSheet.kt:83`、
  `feed/FeedViewModel.kt:44-71,290-303`、`auth/LoginScreen.kt:64`、`network/KeystoreSessionStore.kt:40-53`、
  `core/PlaybackQueue.kt:84-91`、`app/build.gradle.kts:320,324-330` — 行の存在（ファイル長）のみ確認、内容未照合。
  なお `ApiClient` が `override suspend fun` を 4 件持つ（`:129,141,149,153`）ことから、狭い port 2 つを継承している
  という RF7 の前提は間接的に裏付けられる。
- tests=528 / suites=67 / failures=0（verification-run V3）— `./gradlew` を再実行していないため未追試（read-only 方針と turn 予算）。
  ただし分子側（MockWebServer 2 ファイル = 46+7）と上位 suite 名は静的に整合。
- UV1〜UV4（lint / assembleRelease / 実機再生 / `@Test` 534 vs 528 の差 6）— report 側で unexecuted 宣言済み。
  **RF4・RF5・RF12 の「ユーザーに観測される帰結」は UV3（実機観測）が未実行のままなので、
  コード上の欠落は確定だが体験上の症状は未確認**という区別は保持されるべき。

## 7. 自分の引用の範囲検査（`wc -l` 上限内か）

| file | wc -l | 自分が引用した最大行 | 判定 |
|---|---|---|---|
| auth/AuthViewModel.kt | 218 | 211 | ok |
| network/ApiException.kt | 20 | 20 | ok |
| network/AuthInterceptor.kt | 40 | 39 | ok |
| network/KeystoreSessionStore.kt | 119 | 91 | ok |
| network/ApiClient.kt | 224 | 153 | ok |
| network/OkHttpApiClient.kt | 466 | 463 | ok |
| network/SessionStore.kt | 20 | 19 | ok |
| podcast/PodcastViewModel.kt | 560 | 542 | ok |
| podcast/ExoPlayerController.kt | 237 | 169 | ok |
| podcast/PlaybackConstants.kt | 19 | 12 | ok |
| podcast/AudioPlayerSection.kt | 536 | 485 | ok |
| podcast/QuizSheet.kt | 224 | 195 | ok |
| preferences/DataStorePreferencesStore.kt | 129 | 128 | ok |
| preferences/PreferencesStore.kt | 61 | 37 | ok |
| settings/SettingsScreen.kt | 1362 | 1361 | ok |
| settings/SettingsViewModel.kt | 239 | 200 | ok |
| learning/LearningScreen.kt | 505 | 181 | ok |
| learning/LearningViewModel.kt | 72 | 38 | ok |
| core/PlaybackQueue.kt | 137 | 81 | ok |
| account/AccountViewModel.kt | 143 | 141 | ok |
| AppScaffold.kt | 245 | 115 | ok |
| engagement/ListeningStreakStore.kt | 62 | 56 | ok |
| di/AppContainer.kt | 537 | 527 | ok |
| model/PodcastResponse.kt | 37 | 31 | ok |
| test/auth/AuthViewModelTest.kt | 386 | 100 | ok |
| .github/workflows/ci.yml | 48 | 46 | ok |

範囲外引用 0。連結 sed 出力から採った行番号は、すべて `grep -Hn` / `awk FILENAME:NR` で個別に再取得して照合した
（`PodcastViewModel.kt:448,456,461,466`、`ExoPlayerController.kt:161,169`、`SettingsScreen.kt:245,259,271,278`、
`SettingsViewModel.kt:193-197`、`DataStorePreferencesStore.kt:81-87,105-107,117-128` は path 付き出力で確認済み）。
report 側の citation で ±1〜2 行のドリフトを確認したのは 2 件（`PodcastViewModel.kt:502-512` 実体は 501-511、
`OkHttpApiClient.kt:456-465` 実体は 456-463）。いずれも実質を取り違えていないので citations_wrong には数えない。

citations_checked: 64 / citations_wrong: 0 / citations_drifted(±2 行): 2 / citations_unverified: 13

## 8. 秘密値

出力に `local.properties`・`google-services.json`・BuildConfig 実値・トークンは一切含めていない。
`BuildConfig.API_KEY` は参照箇所（`di/AppContainer.kt:66`）の存在のみ言及し、値は読んでいない。
