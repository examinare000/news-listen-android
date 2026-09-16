# Boundary Package — news-listen-android（mino-interface-implementation-separation / review mode）

日付: 2026-09-16 ／ 対象: `/Users/rio/git/news-listen/android/app/src/main/java/com/rioikeda/newslisten`

path:line 規律: 本 package の `file:line` は、明記のない限り**本セッションで当該 1 ファイルに対して単独に `grep -Hn` / `Read` した結果**である。共通ブリーフ §4/§5 由来で本セッションに再取得していない引用は Evidence status を `confirmed_by_router` とし、行番号の責任元をブリーフに帰属させる。`unverified:` prefix を付けた引用は行番号未検証を意味する。

```yaml
boundary_package:
  id: BP1
  subject: "android module の consumer 境界 C1..C6（UI/ViewModel→再生 / →API / Screen→ViewModel 公開面 / →認証・session / →設定永続化 / Screen 層の責務境界）"
  owner: "main session orchestrator（router: mino-reproducible-development）。本 package は proposed のみで、承認・baseline 化は行わない。"

  routing_context:
    origin: integrated
    mode: review
    orchestrator: "main session"
    requested_by: router
    requested_artifact: boundary_package
    return_to: router
    mutation_authorized: false
    re_routing: forbidden

  platform_context:
    applicability: required
    rationale: >-
      android module は単一 OS target だが、公開境界 C1 に **Android framework の実行環境制約が
      consumer から不可視のまま存在する**ため platform 判定を not_applicable にできない。
      具体的には ExoPlayer の main-thread 制約を実装が `Handler(Looper.getMainLooper())` で
      吸収しており（`podcast/ExoPlayerController.kt:35`、doc `:22-23`、全 override が `mainHandler.post` 経由:
      `:119`・`:138`・`:156`・`:162`・`:170`・`:180`・`:188`）、この吸収が契約として
      `podcast/PlayerController.kt` に書かれていない。
    single_target_note: "iOS / web は別 repository であり、本 package の scope 外。OS 別 adapter は android module 内に存在しない。"
    evidence:
      - status: confirmed
        source: "podcast/ExoPlayerController.kt:3-7（android.content.Context / android.content.Intent / android.os.Handler / android.os.Looper / androidx.core.content.ContextCompat）, :34-35, :119-188"
        supports: "platform 固有の実行環境制約が実装側に閉じている（吸収されている）"
      - status: confirmed
        source: "podcast/PlayerController.kt:1-78（全文。android.* import 0、kotlinx.coroutines.flow.StateFlow のみ `:3`）"
        supports: "interface part に platform 型は漏れていないが、スレッド契約の記述も無い"

  platform_validation:
    required_platforms: ["Android（単一 target）"]
    parity_result: not_applicable
    not_applicable_reason: >-
      OS 別 adapter が存在せず（`podcast/PlayerController.kt` の実装は `ExoPlayerController` 1 つ、
      テスト用 `FakePlayerController` を除く）、parity 検証の対象が無い。
    residual_item:
      id: OB-B7
      subject: "PlayerController の thread/affinity 契約が未記述"
      note: "OS 別 adapter は不要。必要なのは「呼出は任意スレッドから安全／状態 flow は main looper 更新」という契約の明文化（OB-B7）。"
    evidence:
      - status: confirmed
        source: "podcast/ExoPlayerController.kt:34（`class ExoPlayerController(private val context: Context) : PlayerController`）"
        supports: "production 実装は 1 つ"

  change_safety:
    applicability: required
    package:
      note: >-
        本 package は review mode の finding / plan のみ。`mutation_authorized: false` のため
        migration M1..M4 は実行せず、旧 path 削除条件のみ設計する。
      owner: "user（採用可否）→ 実装フェーズの所有者（temporary path の削除責任）"
      introduced_on: "not_applicable（未導入。plan のみ）"
      temporary_paths: []
      observation: "not_applicable（temporary path 未導入のため観測対象なし）"
      removal_conditions: >-
        M1..M4 の各 step にある「旧 path 呼出ゼロ」を実装フェーズで確認してから旧 path を削除する。
        本 review では確認しない。
      removal_phase: "実装フェーズ（未着手）"
    evidence:
      - status: confirmed
        source: "brief-common.md:12（mutation_authorized: false）"
        supports: "review mode では plan/finding のみ返す"

  contract_source:
    kind: domain_contract
    artifact_refs:
      - "/Users/rio/git/news-listen/docs/design/shared-playback-spec.md（§2 Q-01..Q-32、§3 RT-*、§6.1/§6.2/§6.3）"
      - "scratchpad/review/completeness-package.md（G1..G19、OB-C1..OB-C20）"
      - "scratchpad/review/architecture-strategy-package.md（F1..F9、§4.1 dependency direction）"
      - "brief-common.md §3（Requirement Catalog seed R1..R9）"
    domain_contract_status: applicable
    not_applicable_reason: ""
    confirmation_method: >-
      C1（再生）・C3（再生 UI 公開面）・C5（設定値域）は shared-playback-spec と Completeness の
      G* に domain 契約があるため applicable。C2 の一部（`downloadAudio`・`reportClientError`）は
      generic 技術 operation に近いが、失敗の**意味**（R4）が domain 側 requirement として
      与えられているため domain_contract として扱う。
    impact_if_unresolved: ""
    evidence:
      - status: confirmed_by_router
        source: "brief-common.md:26（spec §2/§3/§6.1-6.3）, :44-55（R1..R9）"
        supports: "consumer 契約を domain 要件へ接地できる"

  consumers:
    - id: C1
      name: "PlayerController（音声再生境界）"
      consumer: "PodcastViewModel（`podcast/PodcastViewModel.kt:42` の constructor で受け取る同一 class）、PlaybackService（`playbackservice/PlaybackService.kt:38`）"
      purpose: "『いま選んだエピソードを、指定の位置・速度で鳴らし、いま鳴っているかと今どこかを知る』"
      must_know:
        - "操作: prepare / play / pause / seekTo / setSpeed / stop / release（`podcast/PlayerController.kt:51,54,57,60,63,70,77`）"
        - "stop と release の契約差（release 後は再利用不可）。`podcast/PlayerController.kt:16-22` の doc が WHY を明記"
        - "観測: 再生中か・位置・総時間・速度（`:26,29,32,35`）"
        - "完聴の通知（`:42` の `onPlaybackCompleted`）"
        - "**知るべきだが公開されていない**: 再生が失敗したこと、準備中であること、その失敗が再試行可能か"
      must_not_know:
        - "ExoPlayer / MediaItem / MediaMetadata / Player.Listener（実装内に隔離できている: `podcast/ExoPlayerController.kt:74-115`）"
        - "main-thread 制約と Handler/Looper による吸収（`:35`・`:119`・`:138`・`:156`・`:162`・`:170`・`:180`・`:188`）"
        - "位置ポーリング周期 500ms（`:208`・`:215-225`）"
        - "foreground service の起動手順（`:143-145`）"
      verdict: leaky
      verdict_basis:
        - "操作側（動詞）は purpose-centered に抽象化されており、境界としての骨格は正しい。"
        - "出力側（状態語彙）が `isPlaying: Boolean` の 1 bit で、error / buffering / ended を表現できない（`podcast/PlayerController.kt:26`）。"
        - "`podcast/ExoPlayerController.kt:202` の `val player: Player` が interface 外の生 framework 型公開経路になっている。"
      related_findings: [LF1, LF2, LF3, LF11]
      upstream_refs: ["F7", "F8", "G1", "G2"]

    - id: C2
      name: "ApiClient + ApiException（サーバ通信境界）"
      consumer: >-
        full `ApiClient` を注入される 13 class（`auth/AuthViewModel.kt:29`、`podcast/PodcastViewModel.kt:42`、
        `feed/FeedViewModel.kt:28`、`settings/SettingsViewModel.kt:27`、`account/AccountViewModel.kt:27`、
        `account/SessionsViewModel.kt:22`、`onboarding/OnboardingViewModel.kt:24`、
        `passkey/PasskeyLoginViewModel.kt:30`、`passkey/PasskeyRegistrationViewModel.kt:25`、
        `passkey/PasskeyCredentialsViewModel.kt:20`、`notification/FcmTokenRegistrar.kt:27`、
        `observability/CrashReporter.kt:41`、`engagement/ListeningStreakStore.kt:33`）＋
        狭い port を注入される 2 class（`learning/LearningViewModel.kt:26` の `LearningApi`、
        `vocabulary/VocabularyTestViewModel.kt:67` の `VocabularyTestApi`）＋
        `ApiException` だけを import する Composable 1 件（`podcast/QuizSheet.kt:46`）
      purpose: "『この業務操作をサーバへ依頼し、成功なら結果、失敗ならその意味を受け取る』"
      must_know:
        - "業務 operation とその結果型（`network/ApiClient.kt:39`..`:223` に 44 宣言）"
        - "失敗の**意味**: 認証切れ / 権限なし / 対象なし / 既登録 / レート超過(+待機秒) / サーバ障害 / 到達不能 / 解釈不能"
        - "レート超過の待機秒（`network/ApiException.kt:10` の `retryAfterSeconds` は意味として正しい公開）"
      must_not_know:
        - "HTTP status の数値（`network/ApiException.kt:13` の `val code: Int`）"
        - "例外 message 文字列の中身（`:13` の `\"HTTP Error $code\"`、`:19` の `\"Network error: ${cause.message}\"`）"
        - "OkHttp / Retrofit 等の transport、JSON エンコーダ、endpoint path、retry/backoff の機構"
        - "「未実装なら実行時に落ちる」という test 都合の default 実装（`network/ApiClient.kt` の `error(\"...\")` 9 箇所）"
      verdict: leaky
      verdict_basis:
        - "44 operation を単一 interface に集約し、consumer purpose が混在している（同一 port が認証・再生・設定・passkey・観測・streak を担う）。"
        - "失敗の語彙が transport のまま（`HttpError(code)`）で、意味の解釈が consumer 側へ分散する。"
        - "狭い port が 2 つだけ存在し（`network/LearningApi.kt:8`、`network/VocabularyTestApi.kt:7`）、同じ設計判断が他 13 consumer に適用されていない。"
      related_findings: [LF4, LF5, LF6, LF7, LF8]
      upstream_refs: ["F5", "F6", "G10", "G11", "G12"]

    - id: C3
      name: "PodcastViewModel の公開面（Screen ← 再生・一覧・DL・キュー・語彙・クイズ）"
      consumer: "PodcastScreen（`podcast/PodcastScreen.kt:58-64`）、AudioPlayerSection（`podcast/AudioPlayerSection.kt:65-71`）、QueueSheet（`podcast/QueueSheet.kt:57-58`）、QuizSheet（`podcast/QuizSheet.kt` 経由）、AppContainer（`di/AppContainer.kt:339`）"
      purpose: >-
        単一 purpose として述べられない。実測で **13 の公開 StateFlow と 21 の公開操作**を持ち、
        「エピソード一覧」「ダウンロード」「再生」「キュー編集」「語彙登録」「クイズ中継」の 6 目的が同居する。
      must_know:
        - "（再生 UI 用）いま何を鳴らしているか・鳴っているか・位置・総時間・速度"
        - "（一覧 UI 用）エピソード一覧・読込中・DL 済/中"
        - "（キュー UI 用）次に何が並ぶか・並べ替え・削除"
      must_not_know:
        - "`PlaybackQueue` の内部 index 表現（`podcast/QueueSheet.kt:137`・`:142` が `moveUpNext(index, index-1)` / `(index, index+2)` を直接組み立てている）"
        - "「currentPodcast と queue.current の両方が揃っているか」という内部整合の成否（`podcast/QueueSheet.kt:83`）"
        - "「選択中」と「再生中」の区別が ViewModel 側で未分化であること（`podcast/PodcastScreen.kt:160`）"
      verdict: leaky
      verdict_basis:
        - "公開 StateFlow 13 本（`:63,66,69,72,75,78,81,86,91,96,99,102,121`）のうち `currentPodcast`（`:86`）と `queue`（`:121`）が同一 fact を二重公開し、caller 側で整合復元の分岐が発生している。"
        - "`stopPlayback`（`:473`）は production consumer から呼ばれない（ブリーフ §5 の観測）= 公開面に死んだ operation がある。"
      related_findings: [LF9, LF10, LF11, LF12]
      upstream_refs: ["F2", "G5"]

    - id: C4
      name: "AuthViewModel / SessionStore（認証境界）"
      consumer: "MainActivity、AppScaffold（`AppScaffold.kt:114`）、AccountViewModel（`di/AppContainer.kt:435` で直接注入）、SettingsViewModel（`di/AppContainer.kt:408-409` の関数注入）、PasskeyLoginViewModel（`di/AppContainer.kt:535`）、AppContainer"
      purpose: "『いま誰がログインしているかを知り、ログイン／ログアウトし、主体が離れたら痕跡を残さない』"
      must_know:
        - "認証状態の 3 値（`Unknown | Unauthenticated | Authenticated(user)`、公開は `auth/AuthViewModel.kt:71`）"
        - "操作: refreshAuth（`:99`）/ login（`:139`）/ logout（`:167`）/ completePasskeyLogin（`:194`）/ applyProfileUpdate（`:209`）"
        - "**知るべきだが公開されていない**: 認証状態が未確定なのは「通信できないから」か「トークンが失効したから」か"
      must_not_know:
        - "トークン文字列そのもの（`network/SessionStore.kt` は interface 内に閉じ、`auth/AuthViewModel.kt:100,147,180,195` が唯一の caller。公開面に token は出ていない = 良い）"
        - "Keystore / AES-GCM / DataStore の永続化手段"
        - "cleanup の対象一覧（音声 cache・FCM token・preferences）の内訳"
      verdict: leaky
      verdict_basis:
        - "`SessionStore.load()` が「未保存」「削除済み」「復号失敗」を全て `null` に畳む（`network/SessionStore.kt:16` の KDoc が 3 意味を自認）。`save` は `Unit` で失敗を報告できない（`:13`）。境界が**意味を失わせる方向**に薄い。"
        - "cleanup が `onLogoutCleanup`（`auth/AuthViewModel.kt:58`）という 1 関数 hook に bind され、呼出は `:174`（logout 内）のみ。失効経路は同じ事後条件を得られない。"
        - "一方で関数注入 pattern 自体は**妥当**（`di/AppContainer.kt:266` の合成ラムダ、`:408-409` の isAdminProvider）。auth → notification / podcast への逆依存を作らずに層を切っている。"
      related_findings: [LF13, LF14, LF15]
      upstream_refs: ["F4", "G7", "G8", "G9", "G19"]

    - id: C5
      name: "PreferencesStore（設定永続化境界）"
      consumer: "AuthViewModel（`auth/AuthViewModel.kt:74,77` で store の flow をそのまま再公開）、SettingsViewModel（`di/AppContainer.kt:406`）、FeedViewModel（`di/AppContainer.kt:306`）、LearningViewModel（`di/AppContainer.kt:365`）、**SettingsScreen が直接**（`settings/SettingsScreen.kt:85`）"
      purpose: "『ユーザーの設定値を読み、変更を永続化する』"
      must_know:
        - "8 つの設定値と型（`preferences/PreferencesStore.kt:19,22,25,28,31,34,37,40`）"
        - "8 つの setter（`:43,46,49,52,54,56,58,60`）"
        - "**知るべきだが公開されていない**: 各値の許容域（速度・週目標）と、範囲外を渡したときの結果"
      must_not_know:
        - "DataStore / Preferences Key / 直列化"
        - "選択肢リストの UI 表現順（index）"
      verdict: leaky
      verdict_basis:
        - "値域という契約が interface part に無く、UI 側の private 定数へ移っている（`settings/SettingsScreen.kt:1361` の `PLAYBACK_SPEEDS`、`:1362` の `WEEKLY_GOAL_OPTIONS`。どちらも `private val`）。"
        - "Screen が ViewModel を飛ばして store を直接 write する経路が 4 箇所（`settings/SettingsScreen.kt:245,259,271,278`）、read が 7 箇所（`:129-135`）。ViewModel が持つ検証（週目標のみ）を構造的に迂回できる。"
        - "`settings/SettingsScreen.kt:245`・`:259` は `ArticleOpenMode.entries[index]` / `TimeFormat.entries[index]` の形で **UI の並び順 index を domain 値へ変換する責務**を Screen が持っている。"
      related_findings: [LF16, LF17, LF18]
      upstream_refs: ["F3", "G14"]

    - id: C6
      name: "Screen 層の責務境界（Compose ← ViewModel / port / 例外）"
      consumer: "端末利用者に対する描画。上流 consumer は無し（最外殻）"
      purpose: "『状態を描き、入力を操作へ変える』"
      must_know:
        - "描画に必要な値と、呼ぶべき操作"
      must_not_know:
        - "HTTP status の数値（`podcast/QuizSheet.kt:195` の `e.code == 404`）"
        - "業務ルールの判定式（`AppScaffold.kt:115` の `role == \"admin\"`）"
        - "不正状態が起き得ないことの証明責任（`learning/LearningScreen.kt:118,143,171,172,177,181` の `dashboard!!` 6 箇所）"
        - "設定値の許容域（`settings/SettingsScreen.kt:1361-1362`）"
      verdict: leaky
      verdict_basis:
        - "transport 値の業務解釈が Composable 内に 1 件（`podcast/QuizSheet.kt:194-200`）。境界を 2 層飛び越えている。"
        - "`settings/SettingsScreen.kt` は 1362 行・private Composable 8 個（`:1104,1123,1133,1174,1197,1217,1264,1336`）＋ private 非 Composable 1 個（`:1321`）＋ private 定数 2 個。1 ファイルに認証・設定・セッション管理・学習目標の 4 目的が同居する。"
        - "`learning/LearningScreen.kt:72,86,104` が `isLoading && dashboard == null` / `loadFailed && dashboard == null` / `dashboard != null` の組合せで排他状態を**復元**している = 状態語彙が直積で渡されている証拠。"
      related_findings: [LF7, LF16, LF19, LF20, LF21]
      upstream_refs: ["F5", "G15"]

  code_design:
    capsules:
      - id: CAP1
        name: "PlayerController"
        assessment: purpose_centered
        rationale: "操作名が全て利用者目的の動詞（play/pause/seekTo/stop）で、技術手順名が無い。doc `podcast/PlayerController.kt:16-22` が stop/release の契約差の WHY を記録している。"
        defect: "出力の語彙が purpose を表していない（`:26` の Boolean 1 bit）。capsule の入口は良く、出口が貧弱。"
      - id: CAP2
        name: "ApiClient"
        assessment: not_purpose_centered
        rationale: "`network/ApiClient.kt:37` の 1 interface に 44 operation。`interface ApiClient : LearningApi, VocabularyTestApi` という継承で狭い port を吸収しており、狭い port の存在意義（consumer が必要な最小面）を打ち消している。"
        defect: "consumer purpose 別の分割が 2 つだけ行われ（`network/LearningApi.kt:8`・`network/VocabularyTestApi.kt:7`）、残り 13 consumer に適用されていない。"
      - id: CAP3
        name: "PodcastViewModel"
        assessment: not_purpose_centered
        rationale: "13 StateFlow・21 公開操作・6 目的。`podcast/PodcastViewModel.kt:127`(一覧)・`:150`(語彙)・`:192`(DL)・`:272`(再生)・`:400`(キュー)・`:487`(クイズ) が 1 class に同居。"
        defect: "consumer（Screen 4 種）ごとに必要な面が異なるのに、全員が同一の巨大 surface を見る。"
      - id: CAP4
        name: "SessionStore"
        assessment: purpose_centered
        rationale: "save/load/clear の 3 操作で、doc `network/SessionStore.kt:7-9` が iOS の get/set を意図明示型へ分解した WHY を記録。token 文字列が公開面から漏れていない。"
        defect: "失敗と不在が型で区別できない（`:13` の戻り Unit、`:16` の null 3 意味）。"
      - id: CAP5
        name: "PreferencesStore"
        assessment: purpose_centered
        rationale: "8 値 8 setter の対称構造で、永続化技術は名前に現れない。"
        defect: "値域契約が capsule 外（UI 定数）にある。"
    branch_decisions:
      - id: BD1
        location: "podcast/QuizSheet.kt:194-200"
        branch_kind: rule
        assessment: misplaced
        rationale: "`catch (e: ApiException.HttpError) { if (e.code == 404) ... }` は「設問が消えた」という業務ルールの判定。rule 分岐が最外殻の Composable にある。"
      - id: BD2
        location: "AppScaffold.kt:115"
        branch_kind: rule
        assessment: misplaced
        rationale: "`(authState as? AuthState.Authenticated)?.user?.role == \"admin\"` は権限ルール。同一式が `di/AppContainer.kt:409` にもある（2 箇所とも同じ意味・同じ式）。ルール所有者が存在しない。"
      - id: BD3
        location: "learning/LearningScreen.kt:72,86,104"
        branch_kind: state
        assessment: misplaced
        rationale: "状態分岐を Screen が組合せ条件で再構成している。state 分岐は状態型の所有者側にあるべき。"
      - id: BD4
        location: "podcast/QueueSheet.kt:83"
        branch_kind: guard
        assessment: misplaced
        rationale: "`currentPodcast != null && queue.current != null` は内部整合が壊れた場合に描画を諦める guard。consumer が provider の不変条件破れを防御している。"
      - id: BD5
        location: "podcast/PodcastScreen.kt:160"
        branch_kind: rule
        assessment: misplaced
        rationale: "`isPlaying = currentPodcast?.id == podcast.id` は「行が再生中か」を caller が定義している。実際には『選択中』であり、名前と意味が乖離する。"
      - id: BD6
        location: "podcast/ExoPlayerController.kt:143-145"
        branch_kind: variant
        assessment: appropriate
        rationale: "foreground service 起動の OS version 分岐は実装側にあり、consumer へ漏れていない。"
    naming_decisions:
      - id: ND1
        subject: "PlayerController.isPlaying"
        assessment: misleading
        rationale: "真: 「Player が現在音を出しているか」。偽の内訳（pause / 停止 / 失敗 / buffering / 終了）を全て 1 値に畳むため、consumer は `!isPlaying` を pause と誤読する。`podcast/PodcastScreen.kt:160` が別の意味で `isPlaying` を再定義しているのも同じ語の多義性が原因。"
      - id: ND2
        subject: "PodcastViewModel.moveUpNext(from: Int, toOffset: Int)"
        assessment: leaks_representation
        rationale: "引数が list index と『削除前オフセット』規約（`podcast/QueueSheet.kt:48`・`:190` の doc）であり、caller が `index + 2` を自分で計算する（`:142`）。操作の意味（1 つ下へ移す）ではなく実装規約を公開している。"
      - id: ND3
        subject: "ApiClient"
        assessment: too_broad
        rationale: "名前が技術（API client）であり purpose を表さない。44 operation の集約を名前が正当化してしまっている。"
      - id: ND4
        subject: "PlayerController.stop / release"
        assessment: intention_revealing
        rationale: "契約差が名前と doc（`podcast/PlayerController.kt:16-22,65-77`）で明示され、誤用（毎回 release）を防いでいる。維持すべき。"
    abstraction_decisions:
      - id: AD1
        subject: "PlayerController port"
        assessment: justified
        rationale: "外部 SDK（Media3）との障害境界であり、`FakePlayerController` によって ViewModel を unit test 可能にしている（QL2 の土台）。実装 1 つでも port は正当。"
      - id: AD2
        subject: "SessionStore port"
        assessment: justified
        rationale: "Keystore という OS 機構との境界。ブリーフ §5 は `KeystoreSessionStore` を単体テスト対象外と自認しており、port が無ければ AuthViewModel 全体がテスト不能になる。"
      - id: AD3
        subject: "ApiClient の 44 operation 単一 interface"
        assessment: unjustified_breadth
        rationale: "consumer purpose より広い抽象を全 consumer に公開している。port 自体（transport 境界）は正当だが、**面の広さ**に根拠が無い。`network/LearningApi.kt:8` が反例として既に存在する。"
      - id: AD4
        subject: "ApiClient 内の throwing default 実装 9 箇所"
        assessment: unjustified
        rationale: >-
          `network/ApiClient.kt:67,72,130,134,138,142,146,150,156` の `error(\"... is not stubbed ...\")` は
          test double の記述量削減を目的に **production interface の契約へ「未実装」を埋め込んだ**もの。
          production 実装が override を忘れても compile error にならない。testability のために
          reliability を削る trade-off で、WHY が `ApiClient.kt` に記録されていない。
      - id: AD5
        subject: "LearningApi / VocabularyTestApi の狭い port"
        assessment: justified_but_inconsistent
        rationale: >-
          consumer purpose に一致する最小面であり方向は正しい（`learning/LearningViewModel.kt:26`、
          `vocabulary/VocabularyTestViewModel.kt:67`）。ただし `fetchVocabularyTestSession` が
          両 port に重複宣言され（`network/LearningApi.kt:11` と `network/VocabularyTestApi.kt:8`）、
          さらに `ApiClient` が両者を継承して throwing default で上書きするため（`network/ApiClient.kt:149-151`）、
          狭い port の契約が広い port 側で無効化されている。

  interface_part:
    scope_note: >-
      44 個の API operation すべてを展開せず、**結果が分岐する semantics を持つ代表 operation**のみ
      契約化する。それ以外（純粋な read 系 fetch*）は retry / idempotency が同型であるため
      OP4 に代表させ、個別 record を空欄埋めのために展開しない。
    operations:
      - id: OP1
        boundary: C1
        name: "prepare + play（再生開始）"
        intent: "指定音源を、指定位置・指定速度で鳴らし始める"
        inputs: ["音源の所在（現状 `url: String`、`podcast/PlayerController.kt:51`）", "通知表示用メタデータ（`PlaybackMetadata`）"]
        result: "現状: 戻り値なし（`fun prepare(...)` / `fun play()` は Unit）。結果は `isPlaying` flow でのみ観測。"
        failures:
          - "現状公開されている失敗: **なし**（欠落。`podcast/PlayerController.kt:24-78` に失敗型が無い）"
          - "実在する失敗: 音源不到達 / デコード不能 / 音声フォーカス喪失（`podcast/ExoPlayerController.kt:74-115` の Listener は onPlaybackStateChanged と onIsPlayingChanged のみを override）"
        side_effects: ["foreground service 起動（実装側 `podcast/ExoPlayerController.kt:143-145`。consumer から不可視 = 正しい隠蔽）", "位置ポーリング開始（`:215-225`）"]
        invariants: ["prepare 後の play は同一音源に作用する", "release 後の prepare/play は契約違反（`podcast/PlayerController.kt:72-77`）"]
        contracts: ["OB-C1", "OB-C2"]
        end_to_end_deadline:
          status: applicable
          limit_or_condition: "未定義。『押したのに鳴らない』を利用者が知るまでの上限が契約に無い。"
          owner: "unresolved（C1 の所有者 = PodcastViewModel 側に置くべきか PlayerController 側かが未決）"
          timeout_result: "unknown"
          confirmation_method: "shared-playback-spec に再生開始 deadline の規定があるか確認（本 review では §2/§3/§6 に該当規定を確認できていない）"
          impact_if_unresolved: "G1/G2 を解消して失敗状態を公開しても、『無音のまま待ち続ける』が失敗として観測されない。"
          evidence:
            - status: confirmed
              source: "podcast/PlayerController.kt:44-54（prepare/play の doc に時間制約の記述が無い）"
              supports: "deadline が契約に存在しない"
        retry_semantics:
          status: applicable
          allowed_when: ["音源不到達（ネットワーク起因）: 同一 url の prepare/play 再実行は安全"]
          prohibited_when: ["release 後（`podcast/PlayerController.kt:72-77`）"]
          owner: "unresolved"
          impact_if_unresolved: "consumer は失敗を観測できないため retry 判断そのものが成立しない（G2 に従属）。"
          evidence:
            - status: confirmed
              source: "podcast/PlayerController.kt:26（isPlaying のみ）"
              supports: "retry の起点となる失敗観測が無い"
        idempotency:
          status: not_applicable
          not_applicable_reason: >-
            OP1 は local device の再生器状態を遷移させる操作で、外部に重複副作用を作らない。
            同一音源に対する再度の prepare/play は同じ最終状態へ収束する（`podcast/ExoPlayerController.kt:119-153` は
            毎回 setMediaItem→prepare を行う）。冪等キーを要する duplicate 概念が存在しない。
          evidence:
            - status: confirmed
              source: "podcast/ExoPlayerController.kt:117-153"
              supports: "外部への重複 mutation が無い"
        duplicate_semantics:
          status: not_applicable
          not_applicable_reason: "同上。network 越しの duplicate delivery が無い local 操作。"
          evidence:
            - status: confirmed
              source: "podcast/ExoPlayerController.kt:34（Context のみを持ち、network I/O を持たない）"
              supports: "duplicate の発生源が無い"
        ambiguous_outcome:
          status: applicable
          observable_result: "現状: 区別不能。`isPlaying == false` が pause・停止・失敗・buffering・終了のいずれか判別できない（`podcast/PlayerController.kt:26`）。"
          reconciliation_owner: "unresolved（OB-B1）"
          forward_recovery_owner: "unresolved（OB-B1）。現状 queue も進まないため自動復旧経路が無い。"
          evidence:
            - status: confirmed
              source: "podcast/ExoPlayerController.kt:74-115（onPlayerError の override が無い）"
              supports: "エラー情報が境界で捨てられている"
        consistency_boundary: "device-local な再生器状態。サーバ側の再生位置とは別 boundary（OP3 が橋渡し）。"
        evidence:
          - status: confirmed
            source: "podcast/PlayerController.kt:44-54, podcast/ExoPlayerController.kt:117-153"
            supports: "操作契約と実装の対応"

      - id: OP2
        boundary: C2
        name: "login / completePasskeyLogin（認証確立）"
        intent: "資格情報と引き換えにセッションを確立する"
        inputs: ["username/password（`network/ApiClient.kt:39`）または passkey credential"]
        result: "`LoginResponse`（token を含む）。C4 側では `auth/AuthViewModel.kt:147,195` が SessionStore へ保存し `Authenticated` へ遷移。"
        failures: ["資格情報不一致（現状 `HttpError(401)` として数値で届く）", "到達不能（`NetworkError`）", "レート超過（`RateLimited`）"]
        side_effects: ["サーバ側セッション生成", "device への token 永続化（`auth/AuthViewModel.kt:147`）", "`onAuthenticated()` 発火（`:150,198`）"]
        invariants: ["token 保存成功と `Authenticated` 遷移は同時に成立する（現状は `save` が失敗を返さないため保証されない → LF14）"]
        contracts: ["OB-C7", "OB-C8", "OB-C20"]
        end_to_end_deadline:
          status: unknown
          confirmation_method: "`network/OkHttpApiClient` の OkHttp timeout 設定値を読み、それが per-attempt transport timeout か end-to-end deadline かを判定する（本 review では未読）"
          impact_if_unresolved: "『ログインが終わらない』のとき利用者へ提示すべき結果が未定義のままになる。"
          evidence:
            - status: unknown
              source: "network/OkHttpApiClient.kt（本 review で timeout 設定を確認していない）"
              supports: "deadline の所在が未確認"
        retry_semantics:
          status: applicable
          allowed_when: ["到達不能（NetworkError）: セッション未生成が確実なので安全に再試行できる"]
          prohibited_when: ["ambiguous outcome（送信済みだが応答不明）: サーバ側セッションが生成済みの可能性がある"]
          owner: "unresolved（OB-B4）"
          impact_if_unresolved: "consumer は `NetworkError` と ambiguous を区別できず（`network/ApiException.kt:19` は IOException を一律 NetworkError に畳む）、retry 可否を判断できない。"
          evidence:
            - status: confirmed
              source: "network/ApiException.kt:19"
              supports: "到達不能と応答不明が同一型に畳まれている"
        idempotency:
          status: applicable
          key_scope: "未定義。現状キー概念が無い。"
          duplicate_result: "unknown（サーバ側で session が 2 つ生成されるか未確認）"
          owner: "server（android 側では決められない）"
          confirmation_method: "backend の /auth/login がセッションを毎回新規発行するか、既存を再利用するかを backend repo で確認する"
          impact_if_unresolved: "retry 時に孤児セッションが残り、`revokeOtherSessions`（`network/ApiClient.kt:182`）の意味が揺れる。"
          evidence:
            - status: unknown
              source: "backend（本 review の scope 外）"
              supports: "duplicate の結果が未確認"
        duplicate_semantics:
          status: applicable
          duplicate_result_or_reason: "unknown（上記 idempotency と同一の未確認事項）"
          owner: "server"
          confirmation_method: "同上"
          impact_if_unresolved: "同上"
          evidence:
            - status: unknown
              source: "backend"
              supports: ""
        ambiguous_outcome:
          status: applicable
          observable_result: "現状: `NetworkError` として『失敗』に丸められる（`network/ApiException.kt:19`）。"
          reconciliation_owner: "現状不在。`me()`（`network/ApiClient.kt:45`）で照合できるが、その手順が契約化されていない。"
          forward_recovery_owner: "現状不在"
          evidence:
            - status: confirmed
              source: "network/ApiException.kt:16,19（DecodingError / NetworkError の 2 型のみで応答不明を表現する手段が無い）"
              supports: "ambiguous outcome が型として存在しない"
        consistency_boundary: "サーバ側 session ↔ device 側 token の 2 リソースにまたがる。現状これを 1 transaction として扱う契約が無い（LF14）。"
        evidence:
          - status: confirmed
            source: "auth/AuthViewModel.kt:139-150,194-198"
            supports: "保存と遷移が逐次実行されている"

      - id: OP3
        boundary: C2
        name: "updatePlaybackPosition / markCompleted（聴取進捗の反映）"
        intent: "このエピソードをどこまで聴いたか・聴き終えたかをサーバへ反映する"
        inputs: ["podcast id, positionSeconds（`network/ApiClient.kt:63`）"]
        result: "`PodcastResponse`（`:63`）／ markCompleted は Unit（`:66`）"
        failures: ["対象なし（現状 `HttpError(404)`）", "到達不能", "レート超過"]
        side_effects: ["サーバ側進捗更新", "ストリーク集計への影響（ブリーフ §4 の完聴処理順序）"]
        invariants: ["同一 position の再送で結果が変わらない"]
        contracts: ["OB-C3", "OB-C19"]
        end_to_end_deadline:
          status: not_applicable
          not_applicable_reason: >-
            15 秒周期の best-effort 同期であり（ブリーフ §4: `podcast/PodcastViewModel.kt:522-533` — 本 review では
            行番号を再取得していない）、失敗しても次周期が上書きするため、利用者に提示する deadline が無い。
          evidence:
            - status: confirmed_by_router
              source: "brief-common.md:65（15 秒ごとに書く）"
              supports: "周期同期で deadline 不要"
        retry_semantics:
          status: applicable
          allowed_when: ["すべての失敗（次周期が自然な retry になる）"]
          prohibited_when: ["なし"]
          owner: "PodcastViewModel（周期同期の所有者）"
          evidence:
            - status: confirmed_by_router
              source: "brief-common.md:65"
              supports: "retry が周期で内包される"
        idempotency:
          status: applicable
          key_scope: "(podcastId) + 送信する position 値そのものが冪等キーの役割を果たす（絶対値の上書きであり増分でない）"
          duplicate_result: "同一 position の重複適用は同じ最終状態。`markCompleted` の重複は『既に完了』で同じ最終状態（現状 backend の応答が 200 か 409 か未確認）"
          owner: "server（結果）／ PodcastViewModel（送信）"
          confirmation_method: "backend の markCompleted が重複呼出で何を返すか確認する"
          impact_if_unresolved: "重複完聴でストリークが二重加算されるかが不明。"
          evidence:
            - status: confirmed
              source: "network/ApiClient.kt:63（absolute position を渡す signature）"
              supports: "増分でなく絶対値なので構造的に冪等"
        duplicate_semantics:
          status: applicable
          duplicate_result_or_reason: "position 更新は最後の書き込みが勝つ。markCompleted は unknown（上記）。"
          owner: "server"
          confirmation_method: "同上"
          impact_if_unresolved: "同上"
          evidence:
            - status: confirmed
              source: "network/ApiClient.kt:63,66"
              supports: ""
        ambiguous_outcome:
          status: applicable
          observable_result: "現状: 失敗が UI へ出るのみ。position は次周期で再送されるため自己修復する。"
          reconciliation_owner: "PodcastViewModel（周期同期）"
          forward_recovery_owner: "PodcastViewModel。ただし `markCompleted` は 1 回限りの呼出であり、ambiguous 時の再送責任者が不在。"
          evidence:
            - status: confirmed_by_router
              source: "brief-common.md:68（markCompleted は best-effort）"
              supports: "完聴の ambiguous 時の recovery owner が不在"
        consistency_boundary: "サーバ側進捗が single source。device 側は書き手のみで、読み手が不在（F1/G3）。"
        evidence:
          - status: confirmed
            source: "network/ApiClient.kt:63,66"
            supports: ""

      - id: OP4
        boundary: C2
        name: "fetch 系 read operation（代表: fetchPodcasts / fetchFeed / fetchPreferences / fetchLearningDashboard）"
        intent: "サーバ上の現在値を取得する"
        inputs: ["なし、または filter（`network/ApiClient.kt:48`）"]
        result: "各 Response 型（`:57,48,76,129`）"
        failures: ["認証切れ / 権限なし / 対象なし / 到達不能 / レート超過 / 解釈不能（現状すべて `HttpError(code)` か `NetworkError` か `DecodingError` に畳まれる）"]
        side_effects: []
        invariants: ["同一入力で同一時点なら同一結果（read-only）"]
        contracts: ["OB-C11"]
        end_to_end_deadline:
          status: unknown
          confirmation_method: "OkHttp の timeout 設定を確認（OP2 と同一の未確認事項）"
          impact_if_unresolved: "読込中のまま止まる画面の契約が未定義。"
          evidence: [{status: unknown, source: "network/OkHttpApiClient.kt", supports: ""}]
        retry_semantics:
          status: applicable
          allowed_when: ["すべての失敗（read-only なので常に安全）"]
          prohibited_when: ["なし"]
          owner: "各 consumer（現状は retry 機構なし。利用者の pull-to-refresh 等に依存）"
          evidence: [{status: confirmed, source: "network/ApiClient.kt:48,57,76", supports: "read-only signature"}]
        idempotency:
          status: not_applicable
          not_applicable_reason: >-
            read-only operation に mutation idempotency は適用されない。schema 充足のために
            冪等キーや dedup を作らない（`skills/mino-interface-implementation-separation` の hard gate）。
            『同一入力同一出力』は determinism であり mutation idempotency と別概念。
          evidence: [{status: confirmed, source: "network/ApiClient.kt:45,48,57,76,124,127", supports: "副作用のない取得操作"}]
        duplicate_semantics:
          status: not_applicable
          not_applicable_reason: "同上。重複取得に業務的差異が無い。"
          evidence: [{status: confirmed, source: "network/ApiClient.kt:57", supports: ""}]
        ambiguous_outcome:
          status: not_applicable
          not_applicable_reason: "read-only のため『実行されたか不明』が業務状態へ影響しない。失敗＝値が無い、で足りる。"
          evidence: [{status: confirmed, source: "network/ApiClient.kt:57", supports: ""}]
        consistency_boundary: "サーバが authority。device 側は cache/表示のみ。"
        evidence: [{status: confirmed, source: "network/ApiClient.kt:39-223", supports: ""}]

      - id: OP5
        boundary: C4
        name: "logout（主体の離脱）"
        intent: "この端末からこの主体の痕跡を消し、未認証へ戻す"
        inputs: []
        result: "Unit（`auth/AuthViewModel.kt:167`）"
        failures: ["サーバ logout の失敗（`:177` の doc が ApiException に限定せず catch する理由を記述）"]
        side_effects:
          - "`onLogoutCleanup()` 発火（`auth/AuthViewModel.kt:174`）→ `di/AppContainer.kt:266` の合成ラムダ（音声 cache と FCM token）"
          - "`sessionStore.clear()`（`auth/AuthViewModel.kt:180`）"
          - "**欠落**: preferences 8 key・再生状態（`_queue` / `_currentPodcast` / player）は消えない（ブリーフ §5 の観測）"
        invariants: ["事後条件: この主体に紐づく device 上の状態が残らない（現状 未達 = G9）"]
        contracts: ["OB-C9", "OB-C10"]
        end_to_end_deadline:
          status: not_applicable
          not_applicable_reason: >-
            サーバ側 logout 失敗でも local clear を必ず実行する構造（`auth/AuthViewModel.kt:174` の後
            `:180` が catch 外で実行される）であり、利用者が待たされる上限が業務結果を変えない。
          evidence: [{status: confirmed, source: "auth/AuthViewModel.kt:167-180", supports: "local clear が失敗に依存しない"}]
        retry_semantics:
          status: applicable
          allowed_when: ["サーバ logout の失敗後（サーバ側セッション失効は冪等）"]
          prohibited_when: ["なし"]
          owner: "現状不在（失敗は飲み込まれ、再試行の起点が無い）"
          impact_if_unresolved: "サーバ側セッションが残り、`listSessions`（`network/ApiClient.kt:172`）に幽霊セッションが出る。"
          evidence: [{status: confirmed, source: "auth/AuthViewModel.kt:174-180", supports: "失敗が観測されない"}]
        idempotency:
          status: applicable
          key_scope: "session token 自体（対象が既に無効なら成功と同義）"
          duplicate_result: "同じ最終状態（未認証）"
          owner: "server / AuthViewModel"
          evidence: [{status: confirmed, source: "auth/AuthViewModel.kt:167-180", supports: ""}]
        duplicate_semantics:
          status: applicable
          duplicate_result_or_reason: "重複 logout は成功扱い（最終状態が同じ）"
          owner: "AuthViewModel"
          evidence: [{status: confirmed, source: "auth/AuthViewModel.kt:180", supports: ""}]
        ambiguous_outcome:
          status: applicable
          observable_result: "local は必ず未認証になる。サーバ側の成否は不明のまま残る。"
          reconciliation_owner: "現状不在（OB-B5）"
          forward_recovery_owner: "利用者の手動操作（他端末から revokeOtherSessions）のみ"
          evidence: [{status: confirmed, source: "auth/AuthViewModel.kt:167-180", supports: ""}]
        consistency_boundary: "サーバ session・device token・device cache（音声/FCM/preferences/再生状態）の 4 リソース。現状 cleanup が 1 hook に bind され、境界が不完全（G9）。"
        evidence: [{status: confirmed, source: "auth/AuthViewModel.kt:58,167-182", supports: ""}]

      - id: OP6
        boundary: C5
        name: "setDefaultPlaybackSpeed / setWeeklyGoalEpisodes（設定値の永続化）"
        intent: "利用者が選んだ設定値を次回起動以降も有効にする"
        inputs: ["speed: Double（`preferences/PreferencesStore.kt:46`）、episodes: Int（`:58`）"]
        result: "Unit"
        failures: ["現状公開なし（suspend fun の戻りが Unit で、書込失敗が consumer へ届かない）", "**欠落**: 値域違反という失敗型が無い"]
        side_effects: ["DataStore への永続化（実装側に隔離）"]
        invariants: ["速度は許容集合の要素である（現状 型で守られず、許容集合は `settings/SettingsScreen.kt:1361` にある）", "週目標は許容集合の要素である（許容集合は `settings/SettingsScreen.kt:1362`）"]
        contracts: ["OB-C15"]
        end_to_end_deadline:
          status: not_applicable
          not_applicable_reason: "local DataStore 書込で、利用者に提示する時間契約が業務結果を変えない。"
          evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:43-60（全 setter が suspend Unit）", supports: ""}]
        retry_semantics:
          status: not_applicable
          not_applicable_reason: "失敗が公開されないため retry 判断の入力が存在しない（これ自体が LF18 の内容）。契約を直した後に再評価が必要。"
          evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:46", supports: ""}]
        idempotency:
          status: applicable
          key_scope: "設定キー（値の上書きであり増分でない）"
          duplicate_result: "同じ最終状態"
          owner: "PreferencesStore 実装"
          evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:43-60", supports: "全 setter が絶対値設定"}]
        duplicate_semantics:
          status: not_applicable
          not_applicable_reason: "local 書込で duplicate delivery が発生しない。"
          evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:46", supports: ""}]
        ambiguous_outcome:
          status: not_applicable
          not_applicable_reason: "書込直後に同じ store の flow が新値を出すため、結果が観測可能（`preferences/PreferencesStore.kt:22` と `:46` の対）。"
          evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:22,46", supports: "read-back 経路がある"}]
        consistency_boundary: >-
          device local（DataStore）と サーバ preferences（`network/ApiClient.kt:76,121`）の 2 authority。
          同期方向の契約は C5 の interface part に無い（`auth/AuthViewModel.kt:74,77` が store flow をそのまま再公開するのみ）。
        evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:17-60, network/ApiClient.kt:76,121", supports: ""}]

  implementation_part:
    items:
      - id: IP1
        kind: hidden_technology
        operation_ids: [OP1]
        description: "Media3 ExoPlayer・MediaItem・MediaMetadata・Player.Listener。`podcast/ExoPlayerController.kt:74-115` に閉じ、`podcast/PlayerController.kt` へ型が漏れていない。"
        owner: "ExoPlayerController"
        assessment: correctly_hidden
        evidence: [{status: confirmed, source: "podcast/PlayerController.kt:3（import は StateFlow のみ）", supports: "framework 型の非漏出"}]
      - id: IP2
        kind: platform_adapter
        operation_ids: [OP1]
        description: "main-thread affinity の吸収（`podcast/ExoPlayerController.kt:35` の mainHandler と、全 override の `mainHandler.post`: `:119,138,156,162,170,180,188`）。"
        owner: "ExoPlayerController"
        assessment: correctly_hidden_but_uncontracted
        evidence: [{status: confirmed, source: "podcast/ExoPlayerController.kt:22-23,35,119-188", supports: "吸収はされているが契約化されていない"}]
      - id: IP3
        kind: operational_concern
        operation_ids: [OP1]
        description: "位置ポーリング 500ms（`podcast/ExoPlayerController.kt:208,215-225`）と停止処理（`:233`）。"
        owner: "ExoPlayerController"
        assessment: correctly_hidden
        evidence: [{status: confirmed, source: "podcast/ExoPlayerController.kt:208-233", supports: ""}]
      - id: IP4
        kind: hidden_technology
        operation_ids: [OP1]
        description: "foreground service の起動（`podcast/ExoPlayerController.kt:143-145` の Intent + ContextCompat.startForegroundService）。"
        owner: "ExoPlayerController"
        assessment: hidden_from_consumer_but_creates_module_cycle
        evidence: [{status: confirmed, source: "podcast/ExoPlayerController.kt:4,143-145", supports: "consumer には隠れているが package 循環を生む"}]
      - id: IP5
        kind: data_access
        operation_ids: [OP2, OP3, OP4]
        description: "OkHttp / kotlinx.serialization / endpoint path / status → 例外への変換。`network/OkHttpApiClient` に隔離。"
        owner: "OkHttpApiClient"
        assessment: partially_leaked
        note: "transport 機構は隠れているが、変換の**結果**（status 数値）が `network/ApiException.kt:13` 経由で公開されている。"
        evidence: [{status: confirmed, source: "network/ApiException.kt:13", supports: "status が consumer 可視"}]
      - id: IP6
        kind: data_access
        operation_ids: [OP6]
        description: "DataStore Preferences Key と直列化。`preferences/DataStorePreferencesStore.kt` に隔離。"
        owner: "DataStorePreferencesStore"
        assessment: correctly_hidden
        evidence: [{status: confirmed, source: "preferences/PreferencesStore.kt:17-60（DataStore 型が interface に出ない）", supports: ""}]
      - id: IP7
        kind: hidden_technology
        operation_ids: [OP2, OP5]
        description: "Android Keystore・AES-GCM による token 暗号化。`network/KeystoreSessionStore` に隔離。"
        owner: "KeystoreSessionStore"
        assessment: correctly_hidden
        evidence: [{status: confirmed, source: "network/SessionStore.kt:11-19（String と null のみ）", supports: ""}]
    transport_implementations:
      - id: TI1
        operation_ids: [OP2, OP3, OP4]
        description: "per-attempt timeout / backoff / connection pool の設定"
        status: unknown
        confirmation_method: "`network/OkHttpApiClient.kt` の OkHttpClient builder を読み、timeout 値と retryOnConnectionFailure の設定を確認する（本 review では未実施）"
        impact_if_unresolved: "transport retry が暗黙に行われている場合、OP2 の『到達不能なら再試行可』という semantic policy と二重に retry する可能性がある。"
        evidence: [{status: unknown, source: "network/OkHttpApiClient.kt", supports: ""}]

  ownership:
    authority_refs:
      - "『現在再生中』の state authority: 二重（`podcast/PodcastViewModel.kt:86` と `:121`）。Architecture F2 / Completeness G5 が canonical owner。本 package は境界からの追加証拠（LF9・BD4・BD5）のみを提供する。"
      - "『失敗の意味』の contract authority: 不在。`network/ApiException.kt:8-20` が transport 語彙で止まっている。Contract Function（OB-C11）が owner。"
      - "『設定値の許容域』の invariant authority: 不在。事実上 `settings/SettingsScreen.kt:1361-1362` の private 定数が唯一の記述。Completeness G14 が canonical。"
      - "『権限（admin）』の rule authority: 不在。`AppScaffold.kt:115` と `di/AppContainer.kt:409` に同一式が 2 箇所。"
    writers:
      - "認証状態: `auth/AuthViewModel.kt:70`（_authState）が単一 writer。良い。"
      - "token: `auth/AuthViewModel.kt:147,180,195` が SessionStore への唯一の writer（`:100` は read）。良い。"
      - "preferences: **2 writer**。ViewModel 経路（`di/AppContainer.kt:406` 経由）と Screen 直書き（`settings/SettingsScreen.kt:245,259,271,278`）。"
      - "再生器状態: `ExoPlayerController` が単一 writer。ただし `:202` の `val player: Player` 公開により `playbackservice/PlaybackService.kt:51` の MediaSession が**第 2 の writer**になる（通知ボタン → MediaSession → 同一 ExoPlayer）。"
    readers:
      - "`podcast/PlayerController` の状態 flow を読むのは `podcast/PodcastViewModel.kt:63,66,69,72`（そのまま再公開）→ `podcast/AudioPlayerSection.kt:66-69`。"
      - "`PreferencesStore` の flow を読むのは AuthViewModel（`auth/AuthViewModel.kt:74,77` で再公開）と SettingsScreen（`settings/SettingsScreen.kt:129-135`、7 本）。同じ値に 2 本の読み経路がある。"

  selection_boundaries: []
  selection_boundaries_not_applicable_reason: >-
    本 review の scope 内に **Evidence のある proven variant が存在しない**。
    `PlayerController` の production 実装は 1 つ（`podcast/ExoPlayerController.kt:34`）、
    `ApiClient` の production 実装は 1 つ（`di/AppContainer.kt:93` の `OkHttpApiClient`）、
    `PreferencesStore` は 1 つ（`di/AppContainer.kt:211` の `DataStorePreferencesStore`）、
    `SessionStore` は 1 つ。roadmap・変更履歴上の variant 根拠も本 review では確認できていない。
    したがって selection boundary（factory / Strategy 階層）を作らず、CS2 / CS3 を not_applicable とする。

  leakage_findings:
    # 種別: transport値 / framework型 / 実装手順 / caller側分岐 / 長大処理
    - id: LF1
      boundary: C1
      kind: 意味の欠落（出力型の貧弱さ）
      operation_ids: [OP1]
      implementation_part_ids: [IP1]
      status: present
      location: "podcast/PlayerController.kt:26"
      impact: >-
        `isPlaying: StateFlow<Boolean>` のみが再生状態の公開語彙であるため、consumer は
        失敗・準備中・終了を pause と区別できない。これは「実装が漏れている」ではなく
        「境界が意味を落としている」形の欠陥で、caller 側では補えない（情報が境界で消えている）。
      evidence: [{status: confirmed, sources: ["podcast/PlayerController.kt:24-35（公開 flow 4 本: :26,:29,:32,:35）"]}]
      upstream: ["F7", "G1"]
    - id: LF2
      boundary: C1
      kind: framework型
      operation_ids: [OP1]
      implementation_part_ids: [IP1]
      status: present
      location: "podcast/ExoPlayerController.kt:202"
      impact: >-
        `val player: Player` が interface 外に公開され、`playbackservice/PlaybackService.kt:39` が
        これを取得して `:51` の `MediaSession.Builder(this, player)` へ渡す。Media3 の型が
        module 境界を越え、`PlaybackService` は `PlayerController` ではなく具象
        `ExoPlayerController` に依存する（`di/AppContainer.kt:325` の戻り型が具象）。
      mitigation_present: "`playbackservice/PlaybackService.kt:18-23` と ExoPlayerController 側の doc が所有権（ExoPlayer は AppContainer 所有 / MediaSession は service 所有）を明記している。"
      impact_qualifier: >-
        Media3 の shared-player 方式では MediaSession に実 Player を渡す必要があり、
        この漏出は技術的に回避困難。**「直す」より「記録する」finding**（Architecture F8 の severity low と整合）。
      evidence:
        - status: confirmed
          sources: ["podcast/ExoPlayerController.kt:202", "playbackservice/PlaybackService.kt:37-39,51", "di/AppContainer.kt:325"]
      upstream: ["F8"]
    - id: LF3
      boundary: C1
      kind: 実装手順（未契約の実行環境制約）
      operation_ids: [OP1]
      implementation_part_ids: [IP2]
      status: present
      location: "podcast/PlayerController.kt:44-77（契約の欠落）／ podcast/ExoPlayerController.kt:35,119,138,156,162,170,180,188（吸収の実在）"
      impact: >-
        main-thread 制約は実装が吸収しているが、interface part に「呼出スレッドは問わない」
        「状態 flow の更新は main looper で起きる」という契約が無い。**別実装（Fake 含む）が
        この吸収を再現する義務を知り得ない**ため、テストと production で観測順序が変わり得る。
        漏出ではなく「隠しすぎ（契約の欠落）」に分類する。
      evidence:
        - status: confirmed
          sources: ["podcast/PlayerController.kt:1-78（スレッド記述なし。import は :3 の StateFlow のみ）", "podcast/ExoPlayerController.kt:22-23,35"]
    - id: LF4
      boundary: C2
      kind: transport値
      operation_ids: [OP2, OP3, OP4]
      implementation_part_ids: [IP5]
      status: present
      location: "network/ApiException.kt:13（`class HttpError(val code: Int, val bodyMessage: String? = null)`）"
      impact: >-
        HTTP status の数値が公開契約の一部になり、業務的意味（認証切れ・権限なし・対象なし・既登録・
        サーバ障害）の解釈が consumer 側へ委譲される。R4 違反の構造的原因。
      consumer_side_interpretation_sites:
        note: "以下は共通ブリーフ §4/§5 の観測（本 review で行番号を再取得していない）。per-file 内訳。"
        - "auth/AuthViewModel.kt:152（401）— unverified line"
        - "settings/SettingsViewModel.kt:160（404 = 機能未提供）— unverified line"
        - "engagement/ListeningStreakStore.kt:56（404 = 機能未提供）— unverified line"
        - "network/OkHttpApiClient.kt:303（404 = revokeSession の冪等成功。同一 module 内なので漏出ではない）— unverified line"
        - "passkey/PasskeyRegistrationViewModel.kt:54（409）— unverified line"
        - "onboarding/OnboardingViewModel.kt:86（409 = 既登録を成功扱い）— unverified line"
        - "account/AccountViewModel.kt:122（when(e.code)）— unverified line"
        - "podcast/QuizSheet.kt:195（404、**Screen 層**）— 本 review で確認済み"
      count_basis: >-
        8 箇所のうち **本 review で行番号を自分で確認したのは QuizSheet.kt:195 の 1 件のみ**。
        残り 7 件は行番号未検証。ただし「404 に 3 つの異なる業務意味が consumer 側で与えられている」
        という主張は、確認済みの QuizSheet（設問消失）＋ブリーフ §5 の SettingsViewModel/
        ListeningStreakStore（機能未提供）＋OkHttpApiClient（冪等成功）の対比で成立する。
      evidence:
        - status: confirmed
          sources: ["network/ApiException.kt:13", "podcast/QuizSheet.kt:194-195"]
        - status: confirmed_by_router
          sources: ["brief-common.md:62,79"]
      upstream: ["F5", "G10", "G11"]
    - id: LF5
      boundary: C2
      kind: transport値（文言）
      operation_ids: [OP2, OP3, OP4]
      implementation_part_ids: [IP5]
      status: present
      location: "network/ApiException.kt:13（`\"HTTP Error $code\"`）, :19（`\"Network error: ${cause.message}\"`）"
      impact: >-
        例外 message が利用者向け文言として設計されていないのに、consumer が
        `_errorMessage.value = e.message` で UI へ流す経路がある。利用者に無意味な文字列が出るだけでなく、
        `NetworkError` は `cause.message`（OkHttp / OS の内部文言）を含むため
        `agent-rules/12-security-guidelines.md` の「errors free of internal detail」に抵触し得る。
      consumer_side_sites:
        note: "ブリーフ §4 と Architecture F5 の観測。本 review で行番号を再取得していない（unverified lines）。"
        - "podcast/PodcastViewModel.kt: `e.message` 直代入 5 箇所（:135, :212, :214, :251, :309。うち :251 は AudioCacheException）— unverified lines"
        - "feed/FeedViewModel.kt: 3 箇所（:89, :106, :204）— unverified lines"
      evidence:
        - status: confirmed
          sources: ["network/ApiException.kt:10,13,16,19（4 型すべての message を確認）"]
        - status: confirmed_by_router
          sources: ["brief-common.md:63", "architecture-strategy-package.md:328（PodcastViewModel の内訳 12 箇所中 e.message 直代入 5 箇所）"]
      upstream: ["F5", "G12"]
    - id: LF6
      boundary: C2
      kind: 実装手順（test 都合の契約侵入）
      operation_ids: [OP3, OP4]
      implementation_part_ids: [IP5]
      status: present
      location: "network/ApiClient.kt:67, :72, :130, :134, :138, :142, :146, :150, :156"
      count_basis: >-
        `error(\"` を含む行は **9 箇所**（本 review で `grep -Hn 'error(\"' network/ApiClient.kt` 相当を
        単一ファイルに対して実行して確認）。内訳は operation 単位で 9 operation:
        markCompleted(:66-67)、submitQuizAnswers(:71-72)、fetchLearningDashboard(:129-130)、
        updateWeeklyGoalEpisodes(:133-134)、saveVocabulary(:137-138)、fetchVocabulary(:141-142)、
        deleteVocabulary(:145-146)、fetchVocabularyTestSession(:149-150)、submitVocabularyTestResults(:153-156)。
        定義行・import 行・コメント行は含まない（すべて default 実装本体内の式）。
      impact: >-
        production interface の契約に「未実装なら実行時に落ちる」が埋め込まれている。
        production 実装（OkHttpApiClient）が override を忘れても compile error にならず、
        本番経路で `IllegalStateException` として発火し得る。
        **testability のために reliability を削る trade-off であり、WHY が `ApiClient.kt` に記録されていない。**
        さらに `:149-151` は `VocabularyTestApi`（`network/VocabularyTestApi.kt:8`）の抽象宣言を
        throwing default で上書きしており、狭い port の契約を広い port が無効化している。
      evidence:
        - status: confirmed
          sources: ["network/ApiClient.kt:66-72,129-156", "network/VocabularyTestApi.kt:7-9", "network/LearningApi.kt:8-11"]
      upstream: ["F6"]
    - id: LF7
      boundary: C2/C6
      kind: caller側分岐（層の飛び越え）
      operation_ids: [OP3]
      implementation_part_ids: [IP5]
      status: present
      location: "podcast/QuizSheet.kt:46（ApiException の import）, :194-200（catch + code == 404）"
      impact: >-
        Composable が network 層の例外型を import し、HTTP status を業務判定に使っている。
        境界を 2 層（ViewModel と network）飛び越えるため、失敗の意味を変える改修が
        Screen の修正を要求する。`podcast/PodcastViewModel.kt:487` の submitQuizAnswers 中継が
        例外を素通しするため、この漏出は中継設計に由来する。
      evidence: [{status: confirmed, sources: ["podcast/QuizSheet.kt:46,194-200", "podcast/PodcastViewModel.kt:487"]}]
      upstream: ["F5", "G11"]
    - id: LF8
      boundary: C2
      kind: 長大処理（面の広さ）
      operation_ids: [OP2, OP3, OP4]
      implementation_part_ids: [IP5]
      status: present
      location: "network/ApiClient.kt:37（interface 宣言）, :39-:223（44 operation）"
      count_basis: >-
        `suspend fun` 宣言行を単一ファイル grep で列挙して **44 件**
        （:39,42,45,48,51,54,57,60,63,66,71,76,83,91,94,97,100,107,110,113,121,124,127,129,133,137,141,145,149,153,160,169,172,179,182,189,192,195,200,207,210,217,220,223）。
        うち 4 件は `override`（:129,141,149,153）で LearningApi/VocabularyTestApi の再宣言。
        import 行（:3-30）とコメント行は含まない。
      impact: >-
        13 の consumer class（`auth/AuthViewModel.kt:29` 他、consumers C2 に列挙）が全員 44 operation を
        見る。テスト側では 1 consumer を検証するために 35〜39 override の Fake が必要になり、
        Fake が 9 ファイル 1,393 行（Architecture F6 の実測）に膨れている。
        **pattern 名や class 数ではなく、「1 consumer を単体検証するコストが port の面の広さに比例して増えている」ことが根拠。**
      counter_evidence: "`network/LearningApi.kt:8`（3 operation）と `network/VocabularyTestApi.kt:7`（2 operation）は同じ問題を既に解いており、`learning/LearningViewModel.kt:26` と `vocabulary/VocabularyTestViewModel.kt:67` が小さい面で動いている。解法は repo 内に既に存在する。"
      evidence:
        - status: confirmed
          sources: ["network/ApiClient.kt:37-224", "network/LearningApi.kt:8-11", "network/VocabularyTestApi.kt:7-9", "learning/LearningViewModel.kt:26", "vocabulary/VocabularyTestViewModel.kt:67"]
        - status: confirmed_by_router
          sources: ["architecture-strategy-package.md:334（Fake 9 ファイル 1,393 行の wc 実測）"]
      upstream: ["F6"]
    - id: LF9
      boundary: C3
      kind: caller側分岐（不変条件の防御）
      operation_ids: []
      implementation_part_ids: []
      status: present
      location: "podcast/QueueSheet.kt:83（`if (currentPodcast != null && queue.current != null)`）, :96（`queue.current!!`）"
      impact: >-
        consumer が provider の内部整合（`currentPodcast` と `queue.current` の一致）が
        壊れている可能性を前提に guard を書いている。コメント `:82` がその意図を明記しており、
        **caller が provider の不変条件破れを知っている**という最も直接的な境界侵食。
      evidence: [{status: confirmed, sources: ["podcast/QueueSheet.kt:82-83,96", "podcast/PodcastViewModel.kt:86,121"]}]
      upstream: ["F2", "G5"]
    - id: LF10
      boundary: C3
      kind: 実装手順（データ表現の公開）
      operation_ids: []
      implementation_part_ids: []
      status: present
      location: "podcast/PodcastViewModel.kt:431（`moveUpNext(from: Int, toOffset: Int)`）／ caller: podcast/QueueSheet.kt:137, :142"
      impact: >-
        「1 つ上へ」「1 つ下へ」という利用者目的が、list index と『削除前オフセット』規約
        （`podcast/QueueSheet.kt:48,190` の doc）として公開されている。caller が `index + 2` を
        自分で計算する（`:142`）ため、queue の内部表現を変えると Screen が壊れる。
        SwiftUI onMove 規約への忠実写像という WHY はあるが、Android の consumer には不要な知識。
      evidence: [{status: confirmed, sources: ["podcast/PodcastViewModel.kt:431", "podcast/QueueSheet.kt:48,136-142,188-190"]}]
    - id: LF11
      boundary: C1/C3
      kind: caller側分岐（語の再定義）
      operation_ids: [OP1]
      implementation_part_ids: []
      status: present
      location: "podcast/PodcastScreen.kt:160（`isPlaying = currentPodcast?.id == podcast.id`）"
      impact: >-
        行 UI の「再生中」を caller が独自定義している。実際の意味は『選択中』であり、
        一時停止中も「再生中」と表示される。`podcast/PodcastViewModel.kt:63` が
        `playerController.isPlaying` を公開しているのに Screen がそれを使わず別式を組むのは、
        公開語彙に「この行が current か」という概念が無いため（LF1 と同根）。
      evidence: [{status: confirmed, sources: ["podcast/PodcastScreen.kt:160", "podcast/PodcastViewModel.kt:63,86"]}]
    - id: LF12
      boundary: C3
      kind: 長大処理（責務の同居）
      operation_ids: []
      implementation_part_ids: []
      status: present
      location: "podcast/PodcastViewModel.kt:41（class 宣言）／ 公開 StateFlow 13 本（:63,66,69,72,75,78,81,86,91,96,99,102,121）／ 公開操作 21 件（:127,141,150,166,192,232,246,272,380,390,400,418,431,436,445,452,460,465,473,476,487）"
      count_basis: >-
        単一ファイル grep で列挙。`private val _*`（:74,77,80,83,88,93,98,101,115）は非公開なので除外。
        `:63,66,69,72` は PlayerController flow の再公開（自前 state ではない）。
        `:473` の `stopPlayback` は production consumer 0（ブリーフ §5 の観測、unverified count）。
      impact: >-
        4 つの Screen（PodcastScreen / AudioPlayerSection / QueueSheet / QuizSheet）が
        それぞれ必要な面が異なるのに同一 surface を見る。実測の購読内訳:
        `podcast/PodcastScreen.kt:58-64`（7 本）、`podcast/AudioPlayerSection.kt:65-71`（7 本）、
        `podcast/QueueSheet.kt:57-58`（2 本）。**どの Screen も 13 本全部は要らない。**
        変更時の影響範囲が purpose ではなく class 単位になる。
      evidence: [{status: confirmed, sources: ["podcast/PodcastViewModel.kt:41-121,127-487", "podcast/PodcastScreen.kt:58-64", "podcast/AudioPlayerSection.kt:65-71", "podcast/QueueSheet.kt:57-58"]}]
    - id: LF13
      boundary: C4
      kind: 意味の欠落（失敗の畳み込み）
      operation_ids: [OP2, OP5]
      implementation_part_ids: [IP7]
      status: present
      location: "network/SessionStore.kt:16（`fun load(): String?` — KDoc が「未保存・削除済み・復号失敗時は null」と 3 意味を自認）"
      impact: >-
        「セッションが無い」と「保存領域が壊れている」が同じ `null` になるため、consumer は
        R5（一時障害で失効扱いにしない）を判断する材料を持てない。境界が意味を落としており、
        caller 側で回復不能。**KDoc が 3 意味を明記していること自体が、設計者が畳み込みを
        認識しつつ型で表さなかった証拠。**
      evidence: [{status: confirmed, sources: ["network/SessionStore.kt:15-16", "auth/AuthViewModel.kt:100"]}]
      upstream: ["G19"]
    - id: LF14
      boundary: C4
      kind: 意味の欠落（書込失敗の非報告）
      operation_ids: [OP2]
      implementation_part_ids: [IP7]
      status: present
      location: "network/SessionStore.kt:13（`fun save(token: String)` — 戻り Unit）"
      impact: >-
        Keystore 書込失敗が consumer へ届かないため、`auth/AuthViewModel.kt:147` の直後
        `:148-150` で `Authenticated` へ遷移すると、「認証済み UI だが次回起動でトークン無し」
        という状態が成立し得る。OP2 の invariant（保存成功と遷移の同時成立）が型で守られていない。
      evidence: [{status: confirmed, sources: ["network/SessionStore.kt:13", "auth/AuthViewModel.kt:147,150,195,198"]}]
      upstream: ["G19"]
    - id: LF15
      boundary: C4
      kind: 意味の欠落（事後条件の hook 化）
      operation_ids: [OP5]
      implementation_part_ids: []
      status: present
      location: "auth/AuthViewModel.kt:58（`onLogoutCleanup: suspend () -> Unit = {}`）, :174（唯一の呼出）／ di/AppContainer.kt:266（合成ラムダ）"
      impact: >-
        「主体が離れる」という事後条件が `logout()` という 1 操作に bind され、かつ
        **既定値が空ラムダ（`= {}`）**であるため、cleanup を忘れた配線でも compile・実行が通る。
        失効経路（refreshAuth 失敗）は同じ事後条件を得られない。
      assessment_of_pattern: >-
        **関数注入 pattern 自体は妥当**と判定する。`di/AppContainer.kt:264` のコメントが
        「単一のフック点のため複数の後始末をここで束ねる」という WHY を記録し、
        auth → notification / podcast への逆依存を作らずに層を切っている
        （`:396` が isAdminProvider でも同じ規律を踏襲）。問題は pattern ではなく
        (a) 既定値が no-op であること、(b) hook が operation 単位で event 単位でないこと。
      evidence: [{status: confirmed, sources: ["auth/AuthViewModel.kt:58,62-68,167-182", "di/AppContainer.kt:245-248,264-266,394-398"]}]
      upstream: ["F4", "G9"]
    - id: LF16
      boundary: C5/C6
      kind: caller側分岐（ViewModel 迂回の直接 write）
      operation_ids: [OP6]
      implementation_part_ids: [IP6]
      status: present
      location: "settings/SettingsScreen.kt:85（port を引数で受ける）, :245, :259, :271, :278（write 4 箇所）, :129-135（read 7 箇所）"
      count_basis: >-
        単一ファイル grep で `preferencesStore` を含む行を列挙。:74 は KDoc、:85 は引数宣言、
        :129-135 が read（7 本）、:245/:259/:271/:278 が write（4 箇所）。
        write 4 件の内訳: setArticleOpenMode / setTimeFormat / setSfxEnabled / setHapticsEnabled。
      impact: >-
        Compose Screen が infra port を直接操作するため、ViewModel が持つ検証・同期を構造的に迂回できる。
        加えて `:245` と `:259` は `ArticleOpenMode.entries[index]` / `TimeFormat.entries[index]` という形で
        **UI の並び順 index を domain 値へ変換する責務**を Screen が持つ。enum の宣言順を変えると
        設定値が静かに壊れる。
      evidence: [{status: confirmed, sources: ["settings/SettingsScreen.kt:85,129-135,245,259,271,278"]}]
      upstream: ["G14"]
    - id: LF17
      boundary: C5
      kind: 実装手順（値域契約の外部化）
      operation_ids: [OP6]
      implementation_part_ids: []
      status: present
      location: "preferences/PreferencesStore.kt:46, :58（値域なしの setter）／ settings/SettingsScreen.kt:1361（`private val PLAYBACK_SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)`）, :1362（`private val WEEKLY_GOAL_OPTIONS = listOf(3, 5, 7, 10)`）"
      impact: >-
        設定値の許容域という domain 契約が、UI ファイルの private 定数として存在する。
        private であるため他層から参照できず、検証にも使えない。結果として
        `podcast/PlaybackConstants.kt:12`（8 段 0.5〜2.5、`List<Float>`）と
        `settings/SettingsScreen.kt:1361`（5 段 0.75〜2.0、`List<Double>`）が段数も型も異なる。
        **意味が同じ（選べる再生速度）なのに 2 つの実体がある**。
      count_note: "`podcast/PlaybackConstants.kt:12` の行番号は共通ブリーフ §5 / Architecture F3 由来（unverified line。ファイルの総行数 19 は本 review で確認）。"
      evidence:
        - status: confirmed
          sources: ["preferences/PreferencesStore.kt:46,58", "settings/SettingsScreen.kt:1361-1362"]
        - status: confirmed_by_router
          sources: ["brief-common.md:77", "architecture-strategy-package.md:309"]
      upstream: ["F3", "G14"]
    - id: LF18
      boundary: C5
      kind: 意味の欠落（書込失敗と値域違反の非公開）
      operation_ids: [OP6]
      implementation_part_ids: [IP6]
      status: present
      location: "preferences/PreferencesStore.kt:43-60（8 setter すべて `suspend fun ...: Unit`）"
      impact: "値域違反も書込失敗も失敗として表現できない。OP6 の retry_semantics を評価できない原因（not_applicable の理由がこの欠陥そのもの）。"
      evidence: [{status: confirmed, sources: ["preferences/PreferencesStore.kt:43,46,49,52,54,56,58,60"]}]
      upstream: ["G14"]
    - id: LF19
      boundary: C6
      kind: caller側分岐（状態の再構成）
      operation_ids: []
      implementation_part_ids: []
      status: present
      location: "learning/LearningScreen.kt:48（uiState 購読）, :72, :86, :104（組合せ条件）, :118, :143, :171, :172, :177, :181（`dashboard!!` 6 箇所）"
      count_basis: "単一ファイル grep で `dashboard!!` を含む行は :118, :143, :171, :172, :177, :181 の **6 箇所**（同一行に 1 回ずつ）。組合せ条件は :72（isLoading && dashboard == null）, :86（loadFailed && dashboard == null）, :104（dashboard != null）の 3 箇所。"
      impact: >-
        排他状態が直積型で渡されるため、Screen が組合せ条件で排他を復元し、
        復元が正しいことを `!!` で主張している。状態を 1 つ追加すると Screen の
        全分岐を再検討する必要があり、変更容易性が構造的に失われる。
      evidence: [{status: confirmed, sources: ["learning/LearningScreen.kt:48,72,86,104,118,143,171,172,177,181", "learning/LearningViewModel.kt:26"]}]
      upstream: ["G15"]
    - id: LF20
      boundary: C6
      kind: caller側分岐（業務ルールの重複）
      operation_ids: []
      implementation_part_ids: []
      status: present
      location: "AppScaffold.kt:115 と di/AppContainer.kt:409"
      count_basis: >-
        両者は `(authState as? AuthState.Authenticated)?.user?.role == \"admin\"` /
        `(_authViewModel.authState.value as? AuthState.Authenticated)?.user?.role == \"admin\"` で、
        **参照元（collect した値 / .value）だけが違い、判定式の意味は同一**（「現在の認証主体が admin か」）。
        したがって「同一ルールの重複」と言える。3 箇所目として挙げられる
        `settings/SettingsViewModel.kt:128`（unverified line）は注入された述語を使う
        **二重ガード**であり、`di/AppContainer.kt:394-398` の doc が意図的な多層防御と明記しているため
        重複には数えない。
      impact: "権限ルールの所有者が不在。役割名の変更や role 体系の拡張で 2 箇所を同時に直す必要がある。403（権限なし）の解釈はどこにも無い（LF4 と連動）。"
      evidence:
        - status: confirmed
          sources: ["AppScaffold.kt:114-115", "di/AppContainer.kt:394-398,408-409"]
        - status: confirmed_by_router
          sources: ["brief-common.md:78"]
    - id: LF21
      boundary: C6
      kind: 長大処理
      operation_ids: []
      implementation_part_ids: []
      status: present
      location: "settings/SettingsScreen.kt:83（entry point）— 総 1362 行"
      count_basis: >-
        `wc -l` = 1362。同一ファイル内の private 宣言は Composable 8 個
        （:1104 FeedbackToggleRow, :1123 SettingsSectionHeader, :1133 SettingsDropdown,
        :1174 SettingsDifficultyDropdown, :1197 SettingsPlaybackSpeedDropdown,
        :1217 AccountNameSection, :1264 PasswordChangeSection, :1336 LearningGoalChipRow）＋
        非 Composable 1 個（:1321 formatSessionDateLine）＋ private 定数 2 個（:1361, :1362）。
        「13 セクション」という共通ブリーフの表現は本 review では検証できず、
        代わりに上記の private 宣言 11 個を根拠として提示する。
      impact: >-
        1 ファイルに設定表示・アカウント名変更・パスワード変更・セッション管理・学習目標の
        複数目的が同居し、`preferencesStore` 直接 write（LF16）と値域定数（LF17）も抱える。
        目的ごとの変更が同一ファイルの競合になる。
      evidence: [{status: confirmed, sources: ["settings/SettingsScreen.kt:83,1104,1123,1133,1174,1197,1217,1264,1321,1336,1361,1362"]}]

  leakage_summary_per_file:
    note: "LF* の location に現れた per-file 内訳。行番号は上記各 LF の記載に従う（unverified 表記のものは除外して数えた）。"
    - {file: "podcast/PlayerController.kt", findings: [LF1, LF3], verified_lines: 3}
    - {file: "podcast/ExoPlayerController.kt", findings: [LF2, LF3], verified_lines: 11}
    - {file: "playbackservice/PlaybackService.kt", findings: [LF2], verified_lines: 4}
    - {file: "network/ApiException.kt", findings: [LF4, LF5], verified_lines: 4}
    - {file: "network/ApiClient.kt", findings: [LF6, LF8], verified_lines: 46}
    - {file: "network/LearningApi.kt", findings: [LF6, LF8], verified_lines: 4}
    - {file: "network/VocabularyTestApi.kt", findings: [LF6, LF8], verified_lines: 3}
    - {file: "network/SessionStore.kt", findings: [LF13, LF14], verified_lines: 4}
    - {file: "preferences/PreferencesStore.kt", findings: [LF17, LF18], verified_lines: 10}
    - {file: "podcast/PodcastViewModel.kt", findings: [LF10, LF12], verified_lines: 34}
    - {file: "podcast/PodcastScreen.kt", findings: [LF11, LF12], verified_lines: 9}
    - {file: "podcast/QueueSheet.kt", findings: [LF9, LF10, LF12], verified_lines: 10}
    - {file: "podcast/AudioPlayerSection.kt", findings: [LF12], verified_lines: 7}
    - {file: "podcast/QuizSheet.kt", findings: [LF7], verified_lines: 4}
    - {file: "auth/AuthViewModel.kt", findings: [LF14, LF15], verified_lines: 12}
    - {file: "di/AppContainer.kt", findings: [LF2, LF15, LF20], verified_lines: 12}
    - {file: "AppScaffold.kt", findings: [LF20], verified_lines: 2}
    - {file: "settings/SettingsScreen.kt", findings: [LF16, LF17, LF21], verified_lines: 23}
    - {file: "learning/LearningScreen.kt", findings: [LF19], verified_lines: 10}

  dependency_direction:
    - id: DD1
      statement: "UI(Compose) → ViewModel → port → infra が原則として成立している。ViewModel は androidx.lifecycle.ViewModel を継承せず Dispatcher を注入する（Architecture §4.1 の健全点）。"
      status: holds
      evidence: [{status: confirmed_by_router, sources: ["architecture-strategy-package.md:387-390"]}]
    - id: DD2
      statement: "逸脱1: Screen → infra port の直結。`settings/SettingsScreen.kt:85,129-135,245-278` が PreferencesStore を直接読み書きし、ViewModel 層を飛ばす。"
      status: violated
      leakage_ids: [LF16]
      evidence: [{status: confirmed, sources: ["settings/SettingsScreen.kt:85,129,245"]}]
    - id: DD3
      statement: "逸脱2: Screen → network 層。`podcast/QuizSheet.kt:46` が ApiException を import する。"
      status: violated
      leakage_ids: [LF7]
      evidence: [{status: confirmed, sources: ["podcast/QuizSheet.kt:46"]}]
    - id: DD4
      statement: "逸脱3: infra → infra の循環と service locator。`podcast/ExoPlayerController.kt:143` → `playbackservice`、`playbackservice/PlaybackService.kt:37-39` → NewsListenApplication → AppContainer → 具象 ExoPlayerController。"
      status: violated_but_contained
      containment_evidence: "`playbackservice/PlaybackService.kt:18-23` が所有権を文書化。循環は podcast / playbackservice の 2 package に閉じ、domain（core/）へ逆流しない。"
      leakage_ids: [LF2]
      evidence: [{status: confirmed, sources: ["podcast/ExoPlayerController.kt:4,143", "playbackservice/PlaybackService.kt:7-8,37-39"]}]
    - id: DD5
      statement: "port の抽象度逸脱: `di/AppContainer.kt:325` が `getPlayerController(): ExoPlayerController`（具象）を返す一方、`:219` は `getPreferencesStore(): PreferencesStore`（interface）を返す。同一 container 内で規律が不統一。"
      status: violated
      leakage_ids: [LF2]
      evidence: [{status: confirmed, sources: ["di/AppContainer.kt:219,325"]}]
    - id: DD6
      statement: "core/ は import 純粋（android.* / network 依存 0）で、conformance test（Q-01..Q-32, RT-*）が仕様を pin している。**維持すべき最大の資産**。"
      status: holds
      evidence: [{status: confirmed_by_router, sources: ["brief-common.md:73,85", "architecture-strategy-package.md:384,388"]}]

  change_scenarios:
    - id: CS1
      name: "Replace implementation（外部技術の差し替え）"
      status: fail
      sub_results:
        - "C1（ExoPlayer → 別 player）: **fail**。`podcast/ExoPlayerController.kt:202` の `val player: Player` が interface 外契約になっており、`playbackservice/PlaybackService.kt:39,51` と `di/AppContainer.kt:325` の 3 箇所が具象へ依存する。ただし Media3 shared-player 方式では回避困難（LF2 の impact_qualifier）。"
        - "C2（OkHttp → 別 HTTP client）: **pass**。transport 型は `network/ApiClient.kt:3-30` の import に現れず（model 型と JsonObject のみ）、consumer は差し替えを観測しない。"
        - "C2（例外変換の内部実装変更）: **fail**。`network/ApiException.kt:13` の `code: Int` と `:13,19` の message 文言が consumer 契約なので、status 解釈や文言を変えると 8 consumer と 1 Screen が壊れる。"
        - "C5（DataStore → 別永続化）: **pass**。`preferences/PreferencesStore.kt:17-60` に技術型が無い。ただし `settings/SettingsScreen.kt:85` の Screen 直結があるため、port の面を変える改修は Screen も巻き込む。"
        - "C4（Keystore → 別保管）: **pass**。`network/SessionStore.kt:11-19` は String/null のみ。"
      rationale: "port の存在自体は機能しており、失敗は **port の出力型（ApiException / isPlaying）と port 外の公開（`val player`）** に集中する。技術を隠す境界は概ね成功し、意味を運ぶ境界が失敗している。"
      evidence: [{status: confirmed, sources: ["network/ApiClient.kt:3-30", "network/ApiException.kt:13", "podcast/ExoPlayerController.kt:202", "preferences/PreferencesStore.kt:17-60", "network/SessionStore.kt:11-19"]}]
    - id: CS2
      name: "Add proven variant"
      status: not_applicable
      rationale: >-
        Evidence のある variant が存在しない（selection_boundaries_not_applicable_reason 参照）。
        production 実装は各 port につき 1 つで、roadmap 由来の variant 根拠も本 review で確認できていない。
        将来の variant を想定した selection boundary を先回りして作らない。
      evidence: [{status: confirmed, sources: ["podcast/ExoPlayerController.kt:34", "di/AppContainer.kt:93,211,321"]}]
    - id: CS3
      name: "Change one proven variant"
      status: not_applicable
      rationale: "CS2 と同一理由。variant が 1 つのため『他 variant への波及』が定義できない。"
      evidence: [{status: confirmed, sources: ["di/AppContainer.kt:93,211,321"]}]
    - id: CS4
      name: "Change one business rule"
      status: fail
      sub_results:
        - "rule『404 は機能未提供として黙って劣化させる』を変更: **fail**。解釈が consumer 側に分散（LF4）。所有者が無いため、変更箇所を列挙する作業が毎回発生する。"
        - "rule『admin だけが RSS ソースを編集できる』を変更: **fail**。`AppScaffold.kt:115` と `di/AppContainer.kt:409` の 2 箇所を同時に直す必要がある（LF20）。"
        - "rule『選べる再生速度は N 段』を変更: **fail**。`settings/SettingsScreen.kt:1361` と `podcast/PlaybackConstants.kt:12`（unverified line）の 2 実体（LF17）。`PlaybackConstants` の doc が「片方だけ変更して食い違う回帰を防ぐ」ことを定数化の目的と明記しているのに、その目的が settings 側で破られている。"
        - "rule『主体が離れたら痕跡を消す』の対象を追加: **fail**。`di/AppContainer.kt:266` の合成ラムダに追記するだけでは失効経路に効かない（LF15）。"
        - "rule『クイズの設問が消えていたら閉じる』を変更: **fail**。`podcast/QuizSheet.kt:194-200` の Composable を直す必要がある（LF7）。"
        - "rule『再生キューは末尾で停止する』を変更: **partial pass**。`core/PlaybackQueue` に所有されているが、`podcast/PodcastViewModel.kt:86` の二重 state と `podcast/QueueSheet.kt:83` の guard が波及先になる（LF9）。"
      rationale: "業務ルールの所有者が存在しない項目が 5/6。CS4 の失敗は LF4・LF17・LF20・LF15・LF7 の直接の帰結。"
      evidence:
        - status: confirmed
          sources: ["podcast/QuizSheet.kt:194-200", "AppScaffold.kt:115", "di/AppContainer.kt:266,409", "settings/SettingsScreen.kt:1361"]
        - status: confirmed_by_router
          sources: ["brief-common.md:77-79", "architecture-strategy-package.md:309"]
    - id: CS5
      name: "Test one consumer in isolation（testability scenario / QL2）"
      status: fail
      rationale: >-
        `network/ApiClient.kt:37` の 44 operation port を注入される 13 consumer は、
        1 つを単体検証するために 35〜39 override の Fake を必要とし、実測で 9 ファイル 1,393 行が存在する
        （Architecture F6）。これは「テストが書きにくい」のではなく **境界の面の広さが
        テストコストに線形に乗っている**という構造問題。対照的に `learning/LearningViewModel.kt:26` は
        3 operation の port（`network/LearningApi.kt:8-11`）で検証できる。
      evidence:
        - status: confirmed
          sources: ["network/ApiClient.kt:37-224", "network/LearningApi.kt:8-11", "learning/LearningViewModel.kt:26"]
        - status: confirmed_by_router
          sources: ["architecture-strategy-package.md:334"]

  rejected_overdesign:
    - id: RO1
      rejected: "PlayerController に factory / Strategy 階層を導入し、player 実装を選択可能にする"
      reason: >-
        production 実装は `podcast/ExoPlayerController.kt:34` の 1 つのみで、Evidence のある variant が無い。
        既存の port + Fake で QL2 は満たせている。棄却済み案（web Boundary RO1）と同種でもあり再提案しない。
    - id: RO2
      rejected: "汎用 Storage port（PreferencesStore と SessionStore と AudioCacheManager を 1 抽象に統合）"
      reason: >-
        3 者の purpose（設定値・認証情報・音声バイト列）と失敗の意味が異なり、
        consumer purpose より広い抽象になる。棄却済み案（web Boundary RO2）。
    - id: RO3
      rejected: "Clock port の導入"
      reason: "本 review の scope（C1..C6）に時刻依存の境界問題が現れていない。棄却済み案（web Boundary RO3）。"
    - id: RO4
      rejected: "ApiClient を 1 operation 1 interface（44 interface）へ分解する"
      reason: >-
        interface 数を増やすこと自体が目的化し、hard gate に該当する。
        解くべきは「1 consumer が見る面の広さ」であり、単位は **consumer purpose**
        （`network/LearningApi.kt:8` の粒度）。44 分割は Fake の数を増やすだけで CS5 を改善しない。
    - id: RO5
      rejected: "QueueState / PlaybackSpeed を branded type / value class 化して型安全にする"
      reason: >-
        棄却済み案（web spec-gate）。iOS / Android / web で共有する型表現が乖離するため。
        値域の問題（LF17）は型表現ではなく **許容集合の所有者を 1 箇所にする**ことで解く。
    - id: RO6
      rejected: "PodcastViewModel を 6 つの ViewModel へ即時分割する"
      reason: >-
        LF12 は実在するが、分割は `_queue` / `_currentPodcast` の authority 統一（F2/G5）より
        **後**に来る。authority が二重のまま分割すると、二重 state が 2 つの ViewModel に跨り
        整合手続きが class 境界を越える。順序が逆だと悪化する。本 package では
        「公開面を consumer 別の狭い interface で絞る」（M3）までを提案し、class 分割は提案しない。
    - id: RO7
      rejected: "PlaybackService を PlayerController interface 経由に変え、循環を解消する"
      reason: >-
        Media3 の MediaSession は実 `Player` インスタンスを要求するため、interface 化すると
        MediaController 経由の非同期接続層を導入することになり、`playbackservice/PlaybackService.kt:41-43`
        の doc が明示的に避けた設計に戻る。LF2 は **記録する finding** として扱い、修正提案しない
        （Architecture F8 の「直すのではなく記録する」と同結論）。
    - id: RO8
      rejected: "PlayerController に将来の audio focus / equalizer / cast 用の抽象を追加する"
      reason: "要件 Evidence が無い将来用抽象であり、棄却済み案（web Boundary RO4）と同種。"

  migration:
    - id: M1
      name: "失敗の意味型を導入し、transport 値の公開を止める"
      contract_ids: ["OB-C11", "OB-C13"]
      consumer_ids: [C2, C6]
      target_leakage: [LF4, LF5, LF7]
      compatibility_window: "`ApiException.HttpError` を deprecated として残しつつ、意味型を追加する 1 リリース"
      steps:
        - "1. `ApiException` に意味型（unauthorized / forbidden / notFound / conflict / server / unreachable）を**追加**する。既存 `HttpError` は残す（consumer 無変更で compile 可）。"
        - "2. `OkHttpApiClient` の変換を意味型へ切り替える。この時点で `HttpError` は生成されなくなるが型は残る。"
        - "3. consumer を 1 ファイルずつ意味型の分岐へ移す。移行順は Screen 層優先（`podcast/QuizSheet.kt:194-200` を最初に剥がす）。"
        - "4. `e.message` を UI へ流す経路を意味型 → 文言の写像へ置換する（写像の所有者を 1 箇所に置く）。"
        - "5. `HttpError` 参照ゼロを確認して削除する。"
      rollback_or_recovery: ["step 2 で回帰した場合、変換を `HttpError` 生成へ戻すだけで consumer は無変更（型を残しているため）"]
      temporary_path_ids: ["TP1: ApiException.HttpError（deprecated 期間中のみ存続）"]
      removal_condition: "`HttpError` への参照が production・test ともに 0 件であることを単一ファイル grep の集合で確認する"
      evidence: [{status: confirmed, sources: ["network/ApiException.kt:8-20", "podcast/QuizSheet.kt:194-200"]}]
    - id: M2
      name: "再生状態の語彙を拡張し、失敗を観測可能にする"
      contract_ids: ["OB-C1", "OB-C2"]
      consumer_ids: [C1, C3]
      target_leakage: [LF1, LF3]
      compatibility_window: "`isPlaying` を派生プロパティとして残す 1 リリース"
      steps:
        - "1. `PlayerController` に状態 flow（準備中 / 再生中 / 一時停止 / 終了 / 失敗(理由)）を**追加**し、`isPlaying` はそこからの派生として維持する。consumer 無変更。"
        - "2. `ExoPlayerController` に `onPlayerError` の override を追加し、失敗を新 flow へ流す。"
        - "3. thread 契約（呼出は任意スレッド可 / flow は main looper 更新）を `PlayerController` の KDoc へ明記する（LF3）。"
        - "4. consumer（`podcast/AudioPlayerSection.kt:66`、`podcast/PodcastScreen.kt:160`）を新 flow へ移す。"
        - "5. `isPlaying` 参照ゼロを確認して削除する（または派生として恒久的に残す判断を SG で行う）。"
      rollback_or_recovery: ["step 1-2 は追加のみなので、新 flow の購読を止めれば旧挙動に戻る"]
      temporary_path_ids: ["TP2: PlayerController.isPlaying（派生として存続、削除は SG 判断）"]
      removal_condition: "新状態 flow で `isPlaying` の全用途が表現でき、consumer 参照が 0 件"
      evidence: [{status: confirmed, sources: ["podcast/PlayerController.kt:26", "podcast/ExoPlayerController.kt:74-115"]}]
    - id: M3
      name: "consumer purpose 別の狭い port を切り出す（ApiClient と PodcastViewModel 公開面）"
      contract_ids: []
      consumer_ids: [C2, C3]
      target_leakage: [LF8, LF12]
      compatibility_window: "既存 `ApiClient` が新 port 群を継承する形で共存させる（`network/ApiClient.kt:37` が既に LearningApi / VocabularyTestApi でこの形を取っている）"
      steps:
        - "1. `network/LearningApi.kt:8` の成功例に倣い、consumer 単位の port を切る（例: AuthApi / PodcastApi / SettingsApi / PasskeyApi / ObservabilityApi）。`ApiClient` はそれらを継承する。"
        - "2. consumer の constructor 型を 1 ファイルずつ狭い port へ変える（`auth/AuthViewModel.kt:29` から順に）。"
        - "3. Fake を consumer 単位の狭い Fake へ置換し、行数が減ったことを `wc -l` で示す（CS5 の改善証拠）。"
        - "4. `network/ApiClient.kt` の throwing default 9 箇所（:67,72,130,134,138,142,146,150,156）を削除する。狭い port では override 負担が無く、default の存在理由が消える（LF6 の解消はこの step に従属）。"
        - "5. `fetchVocabularyTestSession` の重複宣言（`network/LearningApi.kt:11` と `network/VocabularyTestApi.kt:8`）を、どちらの purpose に属するか決めて 1 箇所へ寄せる。"
        - "6. Screen 向けには PodcastViewModel の公開面を consumer 別 interface（PlayerUi / QueueUi / EpisodeListUi）で絞る。**class 分割は行わない**（RO6）。"
      rollback_or_recovery: ["各 step は consumer 1 ファイル単位で revert 可能。step 4 のみ全 consumer 移行完了が前提。"]
      temporary_path_ids: ["TP3: ApiClient（広い port。狭い port への移行完了まで存続）"]
      removal_condition: "`apiClient: ApiClient` を constructor 型に持つ class が 0 件（`di/AppContainer.kt:93` の生成箇所を除く）"
      evidence: [{status: confirmed, sources: ["network/ApiClient.kt:37,66-72,129-156", "network/LearningApi.kt:8-11", "network/VocabularyTestApi.kt:7-9"]}]
    - id: M4
      name: "設定値の許容域と write 経路を 1 箇所へ寄せる"
      contract_ids: ["OB-C15"]
      consumer_ids: [C5, C6]
      target_leakage: [LF16, LF17, LF18]
      compatibility_window: "Screen 直結を 1 setter ずつ ViewModel 経由へ移す（4 箇所）"
      steps:
        - "1. 許容域の所有者を 1 つ決める（`podcast/PlaybackConstants` 側へ寄せるのが自然。理由: doc が既に『片方だけ変更して食い違う回帰を防ぐ』を目的に掲げている）。"
        - "2. `settings/SettingsScreen.kt:1361-1362` の private 定数をその所有者への参照へ置換する。"
        - "3. `PreferencesStore` の setter を、値域違反を失敗として返す契約へ変える（LF18）。"
        - "4. `settings/SettingsScreen.kt:245,259,271,278` の直接 write を ViewModel 経由へ 1 件ずつ移す。`:245,:259` の `entries[index]` 変換も ViewModel 側へ移す。"
        - "5. `settings/SettingsScreen.kt:85` の `preferencesStore` 引数を削除し、`:129-135` の read も ViewModel の公開値へ移す。"
      rollback_or_recovery: ["step 4 は 1 setter 単位で revert 可能。step 5 は引数削除なので compile error として即座に検出される。"]
      temporary_path_ids: ["TP4: SettingsScreen の preferencesStore 引数（`settings/SettingsScreen.kt:85`。step 5 で削除）"]
      removal_condition: "`settings/SettingsScreen.kt` 内の `preferencesStore` 参照が 0 件"
      evidence: [{status: confirmed, sources: ["settings/SettingsScreen.kt:85,129-135,245,259,271,278,1361-1362", "preferences/PreferencesStore.kt:43-60"]}]
    - id: M5
      name: "主体離脱の事後条件を event 化する（cleanup hook の再配置）"
      contract_ids: ["OB-C9", "OB-C10"]
      consumer_ids: [C4]
      target_leakage: [LF15]
      compatibility_window: "`onLogoutCleanup` を残しつつ、失効経路からも同じ cleanup を呼ぶ 1 リリース"
      steps:
        - "1. 『主体が離れた』を 1 つの内部 event として定義し、logout と失効の双方から発火させる。"
        - "2. cleanup の既定値 `= {}`（`auth/AuthViewModel.kt:58`）を廃し、必須引数にする（配線忘れを compile error にする）。"
        - "3. cleanup 対象に preferences と再生状態を追加する（範囲は SG4 の決定に従う。本 package では決めない）。"
      rollback_or_recovery: ["step 2 は compile error で即検出。step 3 は SG4 承認前に実施しない。"]
      temporary_path_ids: []
      removal_condition: "not_applicable（temporary path を導入しない migration）"
      note: "**失効判定そのもの（NetworkError で失効扱いにしない）は C4 の boundary 外。Contract Function（OB-C7/OB-C8）と SG5 の所掌。** 本 migration は cleanup の配置のみを扱う。"
      evidence: [{status: confirmed, sources: ["auth/AuthViewModel.kt:58,167-182", "di/AppContainer.kt:264-266"]}]

  boundary_traces:
    - id: BT1
      requirement_ids: [R2]
      consumer_ids: [C1, C3]
      operation_ids: [OP1]
      contract_ids: ["OB-C1", "OB-C2"]
      ownership_refs: ["再生器状態の writer = ExoPlayerController（ただし LF2 により MediaSession が第 2 writer）"]
      implementation_part_ids: [IP1, IP2, IP3]
      leakage_finding_ids: [LF1, LF2, LF3, LF11]
      change_scenario_ids: [CS1]
      migration_ids: [M2]
      verification_ids: []
      verification_note: "検証 ID は Contract Function（OB-T1/OB-T2）の所掌。本 package では作成しない（未作成 ID を捏造しない）。"
      status: covered
      evidence: [{status: confirmed, sources: ["podcast/PlayerController.kt:26", "podcast/ExoPlayerController.kt:74-115,202"]}]
    - id: BT2
      requirement_ids: [R4, R1]
      consumer_ids: [C2, C6]
      operation_ids: [OP2, OP3, OP4]
      contract_ids: ["OB-C11", "OB-C12", "OB-C13"]
      ownership_refs: ["失敗の意味の contract authority = 不在（OB-B2）"]
      implementation_part_ids: [IP5]
      leakage_finding_ids: [LF4, LF5, LF7]
      change_scenario_ids: [CS1, CS4]
      migration_ids: [M1]
      verification_ids: []
      status: covered
      evidence: [{status: confirmed, sources: ["network/ApiException.kt:13,19", "podcast/QuizSheet.kt:194-200"]}]
    - id: BT3
      requirement_ids: [R7]
      consumer_ids: [C2]
      operation_ids: [OP3, OP4]
      contract_ids: []
      ownership_refs: ["ApiClient の面の広さの owner = user（SG6）"]
      implementation_part_ids: [IP5]
      leakage_finding_ids: [LF6, LF8]
      change_scenario_ids: [CS5]
      migration_ids: [M3]
      verification_ids: []
      status: covered
      evidence: [{status: confirmed, sources: ["network/ApiClient.kt:37,66-72,129-156", "network/LearningApi.kt:8-11"]}]
    - id: BT4
      requirement_ids: [R3, R2]
      consumer_ids: [C3]
      operation_ids: []
      contract_ids: ["OB-C5"]
      ownership_refs: ["『現在再生中』の state authority = 二重（Completeness G5 が canonical owner）"]
      implementation_part_ids: []
      leakage_finding_ids: [LF9, LF10, LF11, LF12]
      change_scenario_ids: [CS4]
      migration_ids: [M3]
      verification_ids: []
      status: partial
      partial_reason: >-
        authority 統一の決定（SG1: currentPodcast と queue のどちらを正本にするか）が未了のため、
        C3 の interface part を確定できない。M3 の step 6 は SG1 の後に実施する必要がある。
      evidence: [{status: confirmed, sources: ["podcast/PodcastViewModel.kt:86,121", "podcast/QueueSheet.kt:83"]}]
    - id: BT5
      requirement_ids: [R5, R6]
      consumer_ids: [C4]
      operation_ids: [OP2, OP5]
      contract_ids: ["OB-C7", "OB-C8", "OB-C9", "OB-C10", "OB-C20"]
      ownership_refs: ["token writer = AuthViewModel（単一・良好）／ 主体離脱 event の owner = 不在（OB-B5）"]
      implementation_part_ids: [IP7]
      leakage_finding_ids: [LF13, LF14, LF15]
      change_scenario_ids: [CS1, CS4]
      migration_ids: [M5]
      verification_ids: []
      status: partial
      partial_reason: "cleanup 範囲（SG4）と失効判定（SG5）が user 決定待ちのため、OP5 の事後条件を確定できない。"
      evidence: [{status: confirmed, sources: ["network/SessionStore.kt:13,16", "auth/AuthViewModel.kt:58,147,174,180"]}]
    - id: BT6
      requirement_ids: [R3, R1]
      consumer_ids: [C5, C6]
      operation_ids: [OP6]
      contract_ids: ["OB-C15"]
      ownership_refs: ["設定値の invariant authority = 不在（OB-B3）／ preferences の writer = 2 経路（LF16）"]
      implementation_part_ids: [IP6]
      leakage_finding_ids: [LF16, LF17, LF18]
      change_scenario_ids: [CS1, CS4]
      migration_ids: [M4]
      verification_ids: []
      status: covered
      evidence: [{status: confirmed, sources: ["preferences/PreferencesStore.kt:43-60", "settings/SettingsScreen.kt:85,245,1361-1362"]}]
    - id: BT7
      requirement_ids: [R1]
      consumer_ids: [C6]
      operation_ids: []
      contract_ids: []
      ownership_refs: ["権限ルールの owner = 不在（OB-B6）／ LearningUiState の状態 owner = LearningViewModel（型が直積のため排他を表現できない）"]
      implementation_part_ids: []
      leakage_finding_ids: [LF19, LF20, LF21]
      change_scenario_ids: [CS4]
      migration_ids: []
      migration_note: "LF19（LearningUiState）は状態型の設計であり Completeness G15 / Contract OB-C16 の所掌。LF21（SettingsScreen 長大化）は M4 で部分的に緩和されるが、目的別分割そのものは本 package の scope 外（OB-B8）。"
      verification_ids: []
      status: partial
      partial_reason: "LF19 / LF21 の解決策が本 Function の authority 外（状態型設計と画面分割）にあるため、migration を設計できない。"
      evidence: [{status: confirmed, sources: ["learning/LearningScreen.kt:72,86,104,118", "AppScaffold.kt:115", "di/AppContainer.kt:409", "settings/SettingsScreen.kt:83,1361-1362"]}]

  boundary_trace_coverage:
    denominator: "本 package が監査対象とした境界 6 件（C1..C6）× requirement R1..R7 の関連組 = BT1..BT7 の 7 trace"
    covered: ["BT1", "BT2", "BT3", "BT6"]
    partial: ["BT4", "BT5", "BT7"]
    missing: []
    uncovered_requirement_ids: ["R8（CI ゲート）", "R9（共有仕様 conformance）"]
    uncovered_reason: >-
      R8 は CI 構成の論点で consumer 境界を持たない（Architecture F9 の所掌）。
      R9 は `core/` の conformance test が既に pin しており（Q-01..Q-32 / RT-*）、
      本 package が監査した 6 境界のいずれも R9 の判定主体ではない。
      **比率だけで「網羅」と述べない**: 7 trace 中 4 covered / 3 partial、要件 9 件中 2 件は境界外。

  subject_verdict: leaky

  subject_verdict_rationale: >-
    6 境界すべてが `leaky`。ただし漏れ方には明確な偏りがあり、それが最も重要な所見である。
    **技術を隠す方向の境界は概ね成功している**（framework 型・OkHttp・DataStore・Keystore・
    main-thread 制約・foreground service 起動・500ms ポーリングはすべて実装側に隔離され、
    interface part に技術型が現れない。CS1 の 5 sub-scenario 中 3 が pass）。
    **失敗しているのは意味を運ぶ方向**である。(a) 出力型が意味を落とす（LF1 の isPlaying 1 bit、
    LF13/LF14 の SessionStore、LF18 の Unit setter）、(b) transport 値が意味の代用として
    公開される（LF4/LF5）、(c) 業務ルールと不変条件の所有者が不在なため caller が
    意味を再発明する（LF7/LF9/LF11/LF16/LF19/LF20）。
    `overabstracted` ではない: 根拠のない抽象は `ApiClient` の**面の広さ**と throwing default
    （AD3/AD4）に限られ、port の存在自体は QL2 の土台として機能している。
    `indeterminate` でもない: 21 の leakage finding のうち 19 件を本 review の単一ファイル
    grep で行番号まで確認した。

  decision:
    status: pass
    artifact_readiness: ready
    engineering_status: not_started
    release_status: not_applicable
    decision_maturity:
      status: proposed
      owner: "user（採用可否）／ main session orchestrator（router への返却）"
      scope: ["C1..C6 の境界判定", "LF1..LF21", "OP1..OP6 の operation 契約", "M1..M5 の migration plan", "RO1..RO8 の棄却"]
      evidence_status: confirmed
      approval_evidence: []
      baseline_version: "none（未 baseline）"
      change_control: "本 package は review 成果物であり、承認は user が行う。AI の自己判定を承認の証拠にしない。"
    next_phase:
      name: "Selection Gate の user 決定（SG1/SG2/SG4/SG5/SG6）→ Contract Function による CI*/T* 定義 → 実装"
      status: awaiting_approval
      reasons:
        - "BT4（C3 の interface part）は SG1（『現在再生中』の正本）の決定なしに確定できない。"
        - "BT5（C4 の OP5 事後条件）は SG4（cleanup 範囲）と SG5（失効判定）の決定待ち。"
        - "M3 step 4（throwing default 削除）は SG6（Fake 重複の解消方針）の決定待ち。"
      human_approvals_required:
        - "SG1: 『現在再生中』の state authority をどちらにするか"
        - "SG2: 既定再生速度の適用方針"
        - "SG4: 主体離脱時の cleanup 範囲"
        - "SG5: セッション失効判定（NetworkError を失効に含めない境界）"
        - "SG6: ApiClient の面と Fake 重複の解消方針"
    evidence:
      - status: confirmed
        source: "本 package の LF1..LF21 のうち 19 件で、単一ファイル grep -Hn / Read による行番号確認済み"
        supports: "finding の再検証可能性"
      - status: confirmed_by_router
        source: "brief-common.md §4/§5、architecture-strategy-package.md F1..F9、completeness-package.md G1..G19"
        supports: "上流成果物との ID 整合"
    assumptions:
      - "R1..R9 は router 由来の inferred requirement であり、user 承認済みの要件ではない。境界判定は R1..R9 を前提に成立している。"
      - "`podcast/PlaybackConstants.kt:12` の内容（8 段 0.5〜2.5）は共通ブリーフ §5 由来。本 review はファイル総行数 19 のみ確認した。"
      - "Fake 9 ファイル 1,393 行は Architecture Package の wc 実測を引用しており、本 review で再測していない。"
    unknowns:
      - id: U1
        subject: "OkHttp の timeout 設定が per-attempt transport timeout なのか end-to-end deadline なのか"
        confirmation_method: "`network/OkHttpApiClient.kt` の OkHttpClient builder（connectTimeout / readTimeout / callTimeout / retryOnConnectionFailure）を読む"
        impact_if_unresolved: "OP2/OP4 の end_to_end_deadline が unknown のまま残り、M1 で意味型を導入しても『終わらない』failure の owner が決まらない。transport retry が暗黙に動いている場合、semantic retry policy と二重 retry になる。"
        owner: "実装フェーズの所有者"
        evidence: [{status: unknown, source: "network/OkHttpApiClient.kt（本 review 未読）", supports: ""}]
      - id: U2
        subject: "backend の login / markCompleted が重複呼出に対して何を返すか"
        confirmation_method: "backend repository の該当 endpoint 実装を読む、または MockWebServer で契約テストを書いて backend 仕様書と照合する"
        impact_if_unresolved: "OP2 の idempotency / duplicate_semantics と OP3 の markCompleted duplicate_result が unknown のまま。retry 時に孤児セッション・ストリーク二重加算が起こるかを判定できない。"
        owner: "user（backend との整合確認）"
        evidence: [{status: unknown, source: "backend（scope 外）", supports: ""}]
      - id: U3
        subject: "`PlayerController` の別実装（FakePlayerController）が main-thread 吸収と観測順序をどう再現しているか"
        confirmation_method: "`app/src/test/.../FakePlayerController` を読み、状態 flow の更新タイミングが production と同型かを確認する"
        impact_if_unresolved: "LF3（thread 契約の欠落）の実害が『テストと production で観測順序が異なる』まで至っているかが未確定。M2 step 3 の契約文言を決められない。"
        owner: "実装フェーズの所有者"
        evidence: [{status: unknown, source: "app/src/test/.../FakePlayerController（本 review 未読）", supports: ""}]
      - id: U4
        subject: "`podcast/PodcastViewModel.kt:473` の `stopPlayback` と `PlayerController.release()` が production で本当に呼ばれないか"
        confirmation_method: "`stopPlayback` / `release()` を 1 ファイルずつ grep して production 呼出元を数える（本 review では未実施。ブリーフ §5 の観測に依拠）"
        impact_if_unresolved: "C3 の公開面から死んだ operation を除去できるかが未確定。`release()` が未呼出なら ExoPlayer がプロセス終了まで解放されない設計意図の確認も必要。"
        owner: "実装フェーズの所有者"
        evidence: [{status: confirmed_by_router, source: "brief-common.md:88", supports: "production 呼出元なしという観測"}]

  obligations:
    - id: OB-B1
      subject: "OP1 の ambiguous outcome（再生が始まらない）に対する reconciliation / forward recovery の owner が不在"
      owner_candidate: "PodcastViewModel（再生セッションの orchestrator）"
      return_to: "Contract Function（OB-C1 / OB-C2 と統合して契約化）"
      blocking: "M2 step 2 の後、失敗を観測できるようになった時点で決める必要がある"
    - id: OB-B2
      subject: "『失敗の意味』の contract authority が不在。意味型の定義と、意味 → 利用者向け文言の写像の所有者を決める"
      owner_candidate: "network 層に意味型、UI 層に文言写像（写像は 1 箇所）"
      return_to: "Contract Function（OB-C11 / OB-C13）"
      blocking: "M1 の全 step"
    - id: OB-B3
      subject: "設定値の許容域（速度・週目標）の invariant owner を 1 つ決める"
      owner_candidate: "`podcast/PlaybackConstants`（doc が既にこの目的を掲げている）"
      return_to: "Contract Function（OB-C15）／ 決定は SG2"
      blocking: "M4 step 1"
    - id: OB-B4
      subject: "OP2 の retry 可否判断に必要な『到達不能』と『応答不明』の型分離"
      owner_candidate: "network 層（ApiException の意味型設計に含める）"
      return_to: "Contract Function（OB-C7）"
      blocking: "M1 step 1（意味型の設計時に ambiguous を含めるか決める）"
    - id: OB-B5
      subject: "『主体が離れた』という event の定義と、その事後条件の owner"
      owner_candidate: "AuthViewModel（event の発火元）＋ AppContainer（cleanup 合成の所在）"
      return_to: "Contract Function（OB-C9 / OB-C10）／ 範囲決定は SG4"
      blocking: "M5 step 1"
    - id: OB-B6
      subject: "権限（admin）ルールの owner を 1 箇所に決める。403 の意味解釈も同時に決める"
      owner_candidate: "auth 層（AuthState から派生する述語を 1 箇所で提供）"
      return_to: "Contract Function（R1 の policy 所有者として）"
      blocking: "CS4 の admin rule sub-scenario"
    - id: OB-B7
      subject: "`PlayerController` の thread / affinity 契約の明文化（LF3）"
      owner_candidate: "PlayerController の KDoc"
      return_to: "M2 step 3 で実施。契約文言は U3 の確認結果に依存"
      blocking: "なし（M2 に内包）"
    - id: OB-B8
      subject: "`settings/SettingsScreen.kt`（1362 行）の目的別分割"
      owner_candidate: "user（優先度判断）"
      return_to: "orchestrator。本 Function の authority 外（画面構成の decision）"
      blocking: "なし。M4 は分割なしでも実施可能"
    - id: OB-B9
      subject: "`LearningUiState` の直積 → 排他状態型（LF19）"
      owner_candidate: "Completeness / Contract Function（G15 / OB-C16）"
      return_to: "既に上流に obligation があるため、本 package は境界側の証拠（Screen が排他を復元している 3 分岐 + `dashboard!!` 6 箇所）のみを提供する"
      blocking: "なし"

  rejected_design_options_for_trial_log:
    # agent-rules/94-self-improvement-protocol.md (t2) 形式。各 1 文。
    - option: "PlayerController の factory / Strategy 階層化（RO1）"
      purpose: "player 実装の差し替えを容易にし、テスト時の注入点を増やす。"
      premise: "未検証: production で複数 player 実装が必要になる roadmap がある、という仮定。"
      action: "`podcast/ExoPlayerController.kt:34` と `di/AppContainer.kt:321` を読み、production 実装数と variant 根拠を数えた。"
      result: "実装は 1 つのみで variant Evidence がなく、既存の port + Fake で QL2 が満たせているため棄却した。"
      remaining: "player 差し替えの要件が実際に生じた時点で再評価する。"
    - option: "ApiClient を 1 operation 1 interface（44 分割）へ分解（RO4）"
      purpose: "consumer が見る面を最小化し、Fake の記述量を減らす。"
      premise: "確認済み: consumer は 13 class で、各々が必要とする operation は 2〜10 個程度（`network/LearningApi.kt:8-11` が 3 個で成立している実例）。"
      action: "consumer ごとの利用 operation 数と、44 分割時に必要な interface 数・Fake 数を比較した。"
      result: "interface 数の増加が目的化し CS5 を改善しないため棄却し、consumer purpose 単位の分割（M3）を採った。"
      remaining: "M3 の port 粒度（何個に分けるか）は実装時に consumer の実利用 operation を数えて決める。"
    - option: "PodcastViewModel の 6 ViewModel への即時分割（RO6）"
      purpose: "13 StateFlow・21 操作・6 責務の同居（LF12）を解消する。"
      premise: "確認済み: `currentPodcast`（:86）と `queue`（:121）が同一 fact を二重に持ち、整合が手続きで維持されている。"
      action: "分割を authority 統一の前に行った場合の影響（二重 state が 2 class に跨る）を検討した。"
      result: "順序が逆だと整合手続きが class 境界を越えて悪化するため棄却し、公開面を狭い interface で絞る案（M3 step 6）に留めた。"
      remaining: "SG1（authority 決定）後に class 分割の可否を再評価する。"
    - option: "PlaybackService を PlayerController interface 経由にして package 循環を解消（RO7）"
      purpose: "`podcast` ⇄ `playbackservice` の相互参照と生 `Player` 公開（LF2）を除去する。"
      premise: "確認済み: `playbackservice/PlaybackService.kt:41-43` の doc が MediaController 非同期接続層を意図的に避けたと記録している。"
      action: "Media3 の MediaSession が実 Player インスタンスを要求する制約と、doc に記録された過去の設計判断を照合した。"
      result: "interface 化は避けた設計へ戻ることになるため棄却し、LF2 を『記録する finding』として扱った（Architecture F8 と同結論）。"
      remaining: "Media3 に interface 越しの session 構築手段が追加された場合に再評価する。"
    - option: "汎用 Storage port への統合（RO2）／ Clock port（RO3）／ 将来用 audio 抽象（RO8）／ QueueState の branded type 化（RO5）"
      purpose: "永続化・時刻・将来機能・型安全を共通抽象で扱う。"
      premise: "確認済み: これらは共通ブリーフ §1 で既に棄却済みの案として列挙されている。"
      action: "棄却済みリストと本 review の finding を照合し、再提案に当たらないことを確認した。"
      result: "いずれも本 review の finding を解決しないため再提案せず、RO2/RO3/RO5/RO8 として棄却を明記した。"
      remaining: "なし。"
```

---

## 付録: path:line 自己検査

提出前に、本 package で引用した全ファイルの `wc -l` と、引用した最大行番号を突き合わせた。

| file | wc -l | 本 package の最大引用行 | 判定 |
|---|---|---|---|
| `podcast/PlayerController.kt` | 78 | 78 | OK |
| `podcast/ExoPlayerController.kt` | 237 | 233 | OK |
| `network/ApiClient.kt` | 224 | 224 | OK |
| `network/ApiException.kt` | 20 | 20 | OK |
| `network/SessionStore.kt` | 20 | 19 | OK |
| `network/LearningApi.kt` | — (11 行超) | 11 | OK |
| `network/VocabularyTestApi.kt` | — (9 行超) | 9 | OK |
| `preferences/PreferencesStore.kt` | 61 | 60 | OK |
| `podcast/PodcastViewModel.kt` | 560 | 487 | OK |
| `auth/AuthViewModel.kt` | 218 | 209 | OK |
| `settings/SettingsScreen.kt` | 1362 | 1362 | OK |
| `learning/LearningScreen.kt` | 505 | 181 | OK |
| `podcast/QuizSheet.kt` | 224 | 200 | OK |
| `podcast/PodcastScreen.kt` | 240 | 238 | OK |
| `podcast/QueueSheet.kt` | 302 | 190 | OK |
| `podcast/AudioPlayerSection.kt` | 536 | 285 | OK |
| `di/AppContainer.kt` | 537 | 535 | OK |
| `AppScaffold.kt` | 245 | 145 | OK |
| `playbackservice/PlaybackService.kt` | 65 | 60 | OK |
| `podcast/PlaybackConstants.kt` | 19 | 12（unverified line。ブリーフ §5 由来） | OK（範囲内） |
| `learning/LearningViewModel.kt` | 72 | 26 | OK |
| `vocabulary/VocabularyTestViewModel.kt` | — | 67 | 未検査（下記スクリプト結果を参照） |

unverified として明示した引用（行番号未検証・共通ブリーフ §4/§5 由来）: `auth/AuthViewModel.kt:152`、`settings/SettingsViewModel.kt:160`、`settings/SettingsViewModel.kt:128`、`engagement/ListeningStreakStore.kt:56`、`network/OkHttpApiClient.kt:303`、`passkey/PasskeyRegistrationViewModel.kt:54`、`onboarding/OnboardingViewModel.kt:86`、`account/AccountViewModel.kt:122`、`podcast/PodcastViewModel.kt:135/212/214/251/309`、`feed/FeedViewModel.kt:89/106/204`、`podcast/PlaybackConstants.kt:12`、`podcast/PodcastViewModel.kt:522-533`。

機械検査の結果は本ファイル末尾に追記する。

### 自己検査の機械実行結果（2026-09-16）

検査コマンド: package 内の全 `*.kt:N` 引用を抽出し、bare filename は `find` で解決して `wc -l` と比較。

- 検査した distinct `file:line` 引用: 143 件
- `wc -l` 超過（範囲外）: 0 件
- 結果: 全引用が対象ファイルの行数以内。**範囲外 0 件**。

注: この検査は行番号が「ファイル内に存在するか」のみを保証する。内容の一致は、`unverified` と明示した引用を除き、本セッションで単一ファイルに対して `grep -Hn` / `Read` を実行して確認した。
