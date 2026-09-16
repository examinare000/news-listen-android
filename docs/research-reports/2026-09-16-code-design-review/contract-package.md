# Contract Package — news-listen-android（mino-design-by-contract / review mode）

日付: 2026-09-16 ／ 対象: `/Users/rio/git/news-listen/android`（`app/src/main/java/com/rioikeda/newslisten/`）

```yaml
contract_package:
  subject: "android の公開 operation 群（core/PlaybackQueue・core/PlaybackSourceResolver・podcast/PlayerController・podcast/PodcastViewModel・auth/AuthViewModel・network/SessionStore(+Keystore)・network/AuthInterceptor・network/ApiClient(+OkHttp)・network/AudioCacheManager・preferences/PreferencesStore）の契約適合監査"
  mode: review
  mutation_authorized: false
  routing_context:
    origin: integrated
    requested_by: router
    return_to: router
    requested_artifact: contract_package
    re_routing: prohibited
  upstream_inputs:
    completeness_package:
      path: "scratchpad/review/completeness-package.md"
      consumed_ids: [OB-C1..OB-C20, OB-T1..OB-T20, G1..G19, IV1..IV9, DP1..DP7, SD1..SD6]
      read_range: "§10〜§14（行 1092-1290）のみ。§1〜§9（ME*/AP*/OW* の定義本体）と §15（Selection Gate）は本ロールの指示読み範囲外"
      note: "OB-C*/OB-T* を契約抽出の入力とし、本 package が実在 ID の CI*/T* を発行して逆 trace する。ME*/AP*/OW* は ID として引用するが定義本体は未読であり、意味は OB-* の statement 経由でのみ利用した。"
    architecture_strategy_package:
      path: "scratchpad/review/architecture-strategy-package.md"
      consumed_ids: [ARCH-SG1..ARCH-SG8, "§3.1 authority 表 (a)-(f)", F1..F9]
      read_range: "§3.1（行 273-285）と §6（行 492-677）のみ。§1/§2/§4/§5/§7 は未読"
      note: "ARCH-SG* は全て status: pending。本 package は pending gate の選択結果を契約へ組み込まず、blocked_by で明示して obligation として返す。"
  id_prefix_convention:
    rationale: "Architecture package の SG 番号（SG1..SG8）と Completeness package の SG 番号（SG1..SG4）が衝突するため、引用時は接頭辞で区別する。"
    ARCH-SG*: "architecture-strategy-package.md §6 の Selection Gate"
    COMP-SG*: "completeness-package.md §15 の Selection Gate（本ロールは §15 を読んでいないため、番号のみ OB-C19 の記述から借用し内容は unknown 扱い）"

  platform_context:
    runtime: "Android（Kotlin/JVM unit test = Robolectric 非使用の JVM test, Media3 ExoPlayer, OkHttp, DataStore, Android Keystore）"
    required_platforms: [android_device, jvm_unit_test]
    note: "契約条件は OS 別 path/shell に分岐しない。分岐するのは『JVM unit test から到達できるか』であり、これは environment_condition として個別 CI に記載する（CI-P03/CI-A10/CI-X06）。"
  platform_validation:
    parity_result: not_applicable
    rationale: "対象は単一 platform（Android）であり、複数 OS 間の契約 parity は論点でない。web/iOS との parity は共有仕様 §2/§6 の conformance（CI-Q*/CI-S01）として別途 item 化している。"
    evidence: {status: confirmed, sources: ["app/src/test/java/com/rioikeda/newslisten/core/PlaybackQueueConformanceTest.kt:20", "/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:190"]}

  change_safety:
    applicability: not_applicable
    not_applicable_reason: "review mode（mutation_authorized: false）であり公開契約の変更を提案しない。本 package は既存契約の充足/欠落の判定と、欠落に対する RED テスト仕様の定義のみを行う。"
    evidence: {status: confirmed, sources: ["scratchpad/review/brief-common.md:13"]}
```

---

## 0. verdict

```yaml
subject_verdict: insufficient
subject_verdict_rationale: >-
  公開 operation に対する契約項目 97 件のうち、実装が契約を満たしている（met）のは 63 件、
  部分的（partial）7 件、未充足（unmet）27 件。unmet の内訳は「失敗の表現が存在しない」
  （CI-P02/CI-P03/CI-N02/CI-N17）「不変条件を型で守っていない」（CI-Q01/CI-P13/CI-X01/CI-X02）
  「共有仕様の規定が実装されていない」（CI-S04）「失敗保証が呼び出し元へ返らない」（CI-A07/CI-A11）
  の 4 系統に集中する。さらに既存テスト 1 件（AuthViewModelTest:95）が R5 違反挙動を正として
  pin しており、契約を入れる前にテストの削除/反転が必要である。conformance（CI-Q*）は
  Q-01〜Q-32 が 1:1 で対応し全 met だが、仕様 §2.1 不変条件 2/3 を守る constructor 契約が
  抜けており、conformance が緑でも不正キューを構築できる（CI-Q01 unmet）。

decision: ready_with_obligations
decision_rationale: >-
  要求された contract_package の必須要素（CI* の 6 条件・Evidence 状態・実装適合の path:line・
  T* の Given-When-Then・既存テスト対応・coverage 分母/分子・要件 trace）は全て揃っている。
  ただし 9 件の CI は pending の Selection Gate（ARCH-SG1/2/3/4/5/6/7）に依存し、
  owner 未確定のため契約文を確定できない。これらは OB-N* として router へ返す。
decision_blockers: []
open_obligation_ids: [OB-N1, OB-N2, OB-N3, OB-N4, OB-N5, OB-N6, OB-N7, OB-N8]
```

---

## 1. 要件 R1〜R9 → CI 対応（trace 表）

| R | statement 要旨 | 対応 CI | 充足状況 |
|---|---|---|---|
| R1 | 業務ルールが 1 箇所に所有され ViewModel/Screen に重複しない | CI-N02, CI-N09, CI-N18, CI-P26, CI-X03 | unmet 3 / partial 2 |
| R2 | 再生の不正状態を公開経路から構築できない | CI-Q01, CI-Q02, CI-P02, CI-P03, CI-P06, CI-P07, CI-P13, CI-P23, CI-N17, CI-S03 | met 0 / partial 1 / unmet 9 |
| R3 | 現在再生中・再生速度・再生位置の source of truth が一意で writer が 1 つ | CI-P13, CI-P27, CI-S04, CI-X01, CI-X02, CI-X06, CI-A20 | unmet 7 |
| R4 | 消費者が失敗の意味を受け取り transport 値に依存しない | CI-N01, CI-N02, CI-N03, CI-N04, CI-P28, CI-N09 | met 3 / unmet 3 |
| R5 | 失効時に認証済み UI と保存トークンを残さず、一時障害で失効扱いにしない | CI-A03, CI-A04, CI-A07, CI-A08, CI-A10, CI-A11, CI-A12 | met 1 / partial 1 / unmet 5 |
| R6 | 主体が離れる遷移の事後条件としてユーザー固有キャッシュが消える（spec §6.3） | CI-A15, CI-A16, CI-A17, CI-A18, CI-X08, CI-N15 | met 3 / unmet 3 |
| R7 | テストが production 経路を通り契約 CI* に対応付く。Fake の重複で契約が分散しない | CI-N18, CI-A10, CI-P03 | unmet 3（§6 coverage の構造的原因） |
| R8 | CI が test・lint・build を独立ゲートとして実行する | 契約項目なし（out_of_contract_scope） | — |
| R9 | 共有再生仕様 §2/§3/§6 に準拠する | 下表（行単位） | 下表 |

### 1.1 R9 の行単位対応

R9 は「仕様の行 ID に対する conformance」であるため、行 ID 単位で CI を割り当てる。

| spec 行 ID / 箇所 | CI | 既存テスト | 判定 |
|---|---|---|---|
| §2.1 不変条件 1（`items` は id 一意） | CI-Q02 | `PlaybackQueueConformanceTest.kt:100`（Q-09）, `:122`（Q-11） | **partial**（op 経路のみ。constructor/`setQueue`/`copy` は素通し） |
| §2.1 不変条件 2（`currentIndex` は null または範囲内） | CI-Q01 | なし | **unmet**（IV8 / OB-C17） |
| §2.1 不変条件 3（`items` 空 ⟹ `currentIndex` null） | CI-Q01b | `:80`（Q-07）, `:265`（Q-25） | **partial**（`setQueue`/`remove` 経路は met。constructor 素通し） |
| §2.1 不変条件 4（`current` の導出） | CI-Q04 | `:20`（Q-01）, `:28`（Q-02） | met |
| §2.1 不変条件 5（`upNext` の導出） | CI-Q05 | `:20`（Q-01）, `:28`（Q-02）, `:335`（Q-32） | met |
| §2.2 アクセサ | CI-Q04, CI-Q05 | 同上 | met |
| §2.3 `start` = Q-03 | CI-Q06 | `:38` | met |
| §2.4 `setQueue` = Q-04〜Q-07 | CI-Q07 | `:48`, `:59`, `:69`, `:80` | met |
| §2.5 `add` = Q-08, Q-09 | CI-Q08 | `:90`, `:100` | met |
| §2.6 `playNext` = Q-10〜Q-13 | CI-Q09 | `:111`, `:122`, `:133`, `:142` | met |
| §2.7 `moveUpNext` = Q-26〜Q-32 | CI-Q13, CI-Q14 | `:277`, `:288`, `:298`, `:308`, `:317`, `:326`, `:335` | met |
| §2.8 `jump` = Q-14, Q-15 | CI-Q10 | `:154`, `:164` | met |
| §2.9 `advance` = Q-16〜Q-19 | CI-Q11 | `:174`, `:184`, `:194`, `:204` | met |
| §2.10 `remove` = Q-20〜Q-25 | CI-Q12 | `:215`, `:225`, `:235`, `:245`, `:255`, `:265` | met |
| §3 RT-01〜RT-15, RT-A01, RT-A02 | 契約項目なし | `RelativeTimeConformanceTest.kt`（17 @Test） | **out_of_contract_scope**（共通ブリーフ §1 により本 review では再検証せず。相対時刻は本ロールの契約対象 operation に含まれない） |
| §6.1 `resolvePlaybackSource`（spec:284-296） | CI-S01, CI-S02, CI-S03 | `PlaybackSourceResolverTest.kt`（4 @Test） | met 2 / unmet 1 |
| §6.2 `resolveResumePosition` server-wins（spec:298-303） | CI-S04, CI-S05 | なし | **unmet**（実装不在。SD1 / G3 / OB-C3） |
| §6.3 logout キャッシュ削除（spec:305-316） | CI-A18, CI-X08 | `AuthViewModelTest.kt:231` は cleanup 呼出のみ検査 | **unmet**（spec に Android 行が無く、実装は音声+FCM のみ。SD2 / G9） |

**R9 の conformance coverage**: 仕様行 ID 分母 32（Q-01〜Q-32）／分子 32（全 ID が `PlaybackQueueConformanceTest.kt` に 1:1 で存在。テスト名に行 ID を含む規約 spec:274 を満たす）。
ただし §2.1 の不変条件は行 ID を持たず Q-* 表に現れないため、**conformance 32/32 は「不変条件 2 が守られている」ことを一切保証しない**（CI-Q01 unmet）。「32/32 だから §2 準拠」と言ってはならない。

---

## 2. CI-Q* — `core/PlaybackQueue.kt`（共有仕様 §2 conformance の中核）

### 2.1 met（既存 conformance テストが oracle を持つ）

| CI | kind | 契約条件 | 実装 | 既存テスト（oracle） |
|---|---|---|---|---|
| CI-Q03 | invariant | すべての操作は純関数であり receiver を変更せず新しい `PlaybackQueue` を返す（副作用・外部 I/O なし） | `core/PlaybackQueue.kt:23-26`（`data class` + 全 `val`）、`:36,39,47,55,72,84,101,125` が新 instance を返す | `PlaybackQueueConformanceTest.kt:100`（Q-09）・`:133`（Q-12）・`:164`（Q-15）・`:255`（Q-24）・`:308`（Q-29）・`:317`（Q-30）・`:326`（Q-31）が「無変更」＝同値返却を検査 |
| CI-Q04 | postcondition | `current` = `currentIndex` が null なら null、そうでなければ `items[currentIndex]`（仕様 不変条件 4） | `core/PlaybackQueue.kt:28-29`（`getOrNull` により範囲外も null に落ちる） | `:20`（Q-01）・`:28`（Q-02） |
| CI-Q05 | postcondition | `upNext` = `currentIndex` が null なら `items` 全体、そうでなければ以降の要素列（不変条件 5） | `core/PlaybackQueue.kt:32-33` | `:20`（Q-01）・`:28`（Q-02）・`:335`（Q-32） |
| CI-Q06 | postcondition | `start(item)` ⟹ `items=[item]`、`currentIndex=0`（既存キュー破棄） | `core/PlaybackQueue.kt:36` | `:38`（Q-03） |
| CI-Q07 | precondition | `setQueue(items, startAt)`: `items` 空 ⟹ 空キュー。非空 ⟹ `currentIndex = clamp(startAt, 0, size-1)`（範囲外入力を拒否せず正規化する） | `core/PlaybackQueue.kt:39-44` | `:48`（Q-04）・`:59`（Q-05）・`:69`（Q-06）・`:80`（Q-07） |
| CI-Q08 | idempotency | `add(item)`: 同一 id が既にあれば no-op（2 回目以降の呼び出しが状態を変えない） | `core/PlaybackQueue.kt:47-48` | `:90`（Q-08）・`:100`（Q-09） |
| CI-Q09 | postcondition | `playNext(item)`: 現在の直後へ挿入。現在と同一 id なら no-op。既存重複は除去後に挿入。現在不在なら先頭挿入で `currentIndex` は null 維持 | `core/PlaybackQueue.kt:55-66`（`:61` が現在 id から index を再計算） | `:111`（Q-10）・`:122`（Q-11）・`:133`（Q-12）・`:142`（Q-13） |
| CI-Q10 | postcondition | `jump(id)`: 見つかれば `currentIndex` を更新して `found=true`、無ければキュー不変で `found=false` | `core/PlaybackQueue.kt:72-75` | `:154`（Q-14）・`:164`（Q-15） |
| CI-Q11 | postcondition | `advance()`: 未再生非空⟹先頭 / 未再生空⟹null / 次あり⟹+1 / 末尾⟹`currentIndex` 維持で null | `core/PlaybackQueue.kt:84-91` | `:174`（Q-16）・`:184`（Q-17）・`:194`（Q-18）・`:204`（Q-19） |
| CI-Q12 | postcondition | `remove(id)`: 未存在⟹no-op / 空化⟹null / 前方削除⟹−1 / 同位置⟹次を昇格（末尾クランプ）/ 後方⟹不変 | `core/PlaybackQueue.kt:101-114` | `:215`（Q-20）・`:225`（Q-21）・`:235`（Q-22）・`:245`（Q-23）・`:255`（Q-24）・`:265`（Q-25） |
| CI-Q13 | postcondition | `moveUpNext(from, toOffset)`: SwiftUI `onMove` の削除前オフセット方式。`toOffset == upNext.size` は末尾移動。範囲外は no-op | `core/PlaybackQueue.kt:125-137`（`:132` の `insertAt` 補正が方式の実体） | `:277`（Q-26）・`:288`（Q-27）・`:298`（Q-28）・`:308`（Q-29）・`:317`（Q-30）・`:326`（Q-31） |
| CI-Q14 | invariant | `moveUpNext` は `currentIndex` を変更しない（再生済み・現在は動かない） | `core/PlaybackQueue.kt:136`（`copy(items = ...)` のみで `currentIndex` 非指定） | `:277`（Q-26）・`:288`（Q-27）・`:298`（Q-28）・`:335`（Q-32） |
| CI-Q15 | failure_guarantee | 全操作は例外を投げない全域関数である（不正入力は no-op またはクランプで吸収） | `core/PlaybackQueue.kt:74`（`idx<0`）・`:103`（`idx<0`）・`:128`（範囲ガード）・`:43`（`coerceIn`） | `:164`（Q-15）・`:255`（Q-24）・`:308`（Q-29）・`:317`（Q-30）。`Q-30` は iOS でクラッシュしていた行（spec:237）であり、この CI の存在理由そのもの |

Evidence: 上表 13 件すべて `{status: confirmed, sources: 上記 path:line}`（本セッションで `core/PlaybackQueue.kt` 全 137 行と `PlaybackQueueConformanceTest.kt` の 32 テスト名を読了）。

### 2.2 partial / unmet

```yaml
- id: CI-Q01
  operation: "PlaybackQueue<T>(items, currentIndex) — public primary constructor / data class copy()"
  kind: invariant
  statement: "構築時に currentIndex == null または 0 <= currentIndex <= items.size - 1 を保証する（共有仕様 §2.1 不変条件 2『範囲外の値は生成しない』）。違反入力は拒否または正規化する。"
  evidence: {status: confirmed, sources: ["/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:50", "core/PlaybackQueue.kt:23-26"]}
  conformance:
    status: unmet
    sources: ["core/PlaybackQueue.kt:23-26（init ブロックも require もない public constructor）", "core/PlaybackQueue.kt:28-29（getOrNull で null に落とすため違反が沈黙する）", "core/PlaybackQueue.kt:84-91（currentIndex=5, size=2 で next=6>=2 のため永久に (this, null)）"]
    note: "setQueue（:39-44）だけが coerceIn で守る。他の 9 操作と constructor / copy は素通し。"
  requirement_ids: [R2, R9]
  upstream: [OB-C17, G16, IV8, DP4]
  existing_tests: []
  blocked_by: []
  test_spec:
    id: T-Q01
    verifies: [CI-Q01]
    given: "要素 2 件のリスト items=[a,b]"
    when: "PlaybackQueue(items = items, currentIndex = 5) を構築する（および currentIndex = -1、items=emptyList() かつ currentIndex = 0 の 3 系列）"
    then: "構築が失敗する（IllegalArgumentException）か、currentIndex が仕様範囲へ正規化される。いずれの場合も『非空キューで current も next も無い』状態が観測されない"
    oracle: "公開 API（構築式の結果、または投げられた例外型）と、構築できた場合の current / upNext / advance() の戻り値"
    regression_guard: "Q-01〜Q-32（PlaybackQueueConformanceTest.kt の 32 テスト）が変更なしで green を維持すること。とくに Q-18（:194）は『末尾で currentIndex を維持』を要求するため、正規化方針が Q-18 と矛盾しないことを確認する"

- id: CI-Q01b
  operation: "PlaybackQueue の構築全般（constructor / copy / remove / setQueue）"
  kind: invariant
  statement: "items が空 ⟹ currentIndex は null（共有仕様 §2.1 不変条件 3）"
  evidence: {status: confirmed, sources: ["/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:51"]}
  conformance:
    status: partial
    sources: ["core/PlaybackQueue.kt:40-41（setQueue は空で PlaybackQueue() を返す）", "core/PlaybackQueue.kt:106-108（remove は空化で null）", "core/PlaybackQueue.kt:23-26（constructor は PlaybackQueue(emptyList(), 0) を許す）"]
  requirement_ids: [R2, R9]
  upstream: [OB-C17]
  existing_tests: ["PlaybackQueueConformanceTest.kt:80（Q-07 setQueue 空）", "PlaybackQueueConformanceTest.kt:265（Q-25 remove で空化）"]
  blocked_by: []
  test_spec:
    id: T-Q01b
    verifies: [CI-Q01b]
    given: "空の items"
    when: "PlaybackQueue(items = emptyList(), currentIndex = 0) を構築する"
    then: "拒否されるか currentIndex が null に正規化される"
    oracle: "構築結果の currentIndex / 投げられた例外型"

- id: CI-Q02
  operation: "PlaybackQueue の構築全般（constructor / copy / setQueue / add / playNext）"
  kind: invariant
  statement: "items は id について一意（共有仕様 §2.1 不変条件 1）"
  evidence: {status: confirmed, sources: ["/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:49"]}
  conformance:
    status: partial
    sources: ["core/PlaybackQueue.kt:47-48（add が重複を弾く）", "core/PlaybackQueue.kt:59（playNext が filterNot で既存重複を除去）", "core/PlaybackQueue.kt:39-44（setQueue は重複を含む items をそのまま受ける）", "core/PlaybackQueue.kt:23-26（constructor / copy も同様）"]
    note: "仕様の文言も『add / playNext が重複を排除して維持する』と op 限定であり、構築経路を守る主体が仕様側にもいない。仕様の不足として SD4 系の差分に相当する。"
  requirement_ids: [R2, R9]
  upstream: [OB-C17]
  existing_tests: ["PlaybackQueueConformanceTest.kt:100（Q-09）", "PlaybackQueueConformanceTest.kt:122（Q-11）"]
  blocked_by: []
  test_spec:
    id: T-Q02
    verifies: [CI-Q02]
    given: "同一 id を 2 件含む items=[a, a']"
    when: "PlaybackQueue(items = items, currentIndex = 0) を構築し、続いて setQueue(items, 0) を呼ぶ"
    then: "重複が拒否されるか、id 一意へ正規化される（どちらを採るかは仕様側の決定が必要 → OB-N1）"
    oracle: "構築結果の items の id 列 / 投げられた例外型"
```

---

## 3. CI-P* — `podcast/PlayerController.kt` と `podcast/PodcastViewModel.kt`

### 3.1 met

| CI | operation | kind | 契約条件 | 実装 | 既存テスト |
|---|---|---|---|---|---|
| CI-P01 | `prepare(url, metadata)` | postcondition | 指定 URL を再生準備し、`durationSeconds` は未確定のあいだ null を公開する。`metadata` は MediaStyle 通知のタイトル/アーティストになる | `podcast/PlayerController.kt:32,51`、`podcast/ExoPlayerController.kt:95-97` | `PodcastViewModelTest.kt:108`（最新 URL 再取得後 prepare）、`PlaybackMetadataTest.kt`（5 @Test） |
| CI-P04 | `play()` | postcondition | 呼出後 `isPlaying` が true になる | `podcast/PlayerController.kt:54`、`podcast/ExoPlayerController.kt:104-112` | `PodcastViewModelTest.kt:411`（togglePlayPause 経由） |
| CI-P05 | `pause()` | postcondition | 呼出後 `isPlaying` が false になり、`positionSeconds` は保持される | `podcast/PlayerController.kt:57` | `PodcastViewModelTest.kt:411`、`:293`（一時停止中も位置同期が続く＝位置が保持されている） |
| CI-P08 | `stop()` | postcondition | メディア・位置・再生状態をクリアし、以後 `prepare`/`play` で別メディアを再生できる（再利用可能） | `podcast/PlayerController.kt:70`（契約 doc `:66-69`） | `PodcastViewModelTest.kt:497`（再生停止後に別 Podcast を再生できる） |
| CI-P10 | `onPlaybackCompleted` | postcondition | 完聴時に呼ばれ、呼び出し元が次の再生開始時に上書きする（後勝ち 1 系統） | `podcast/PlayerController.kt:42`、`podcast/ExoPlayerController.kt:87-91` | `PodcastViewModelTest.kt:436`、`:521`、`:555` |
| CI-P11 | `PodcastViewModel.play` | idempotency | 二重 play / 連続 play でも位置同期タイマーは後勝ちの 1 系統のみ残る | `podcast/PodcastViewModel.kt:272,522` | `PodcastViewModelTest.kt:555`、`:521` |
| CI-P12 | `handlePlaybackEnded`（`onPlaybackCompleted` 経由） | postcondition | 完聴時の順序は markCompleted（遷移前の id）→ streak 再取得 → `queue.advance` → `play(next)`。markCompleted 失敗でも遷移は進む（best-effort） | `podcast/PodcastViewModel.kt:348-370`（`:351` 遷移前 id、`:361-362` advance） | `PodcastViewModelTest.kt:436`、`:466`、`:723`、`:752` |
| CI-P14 | `download(podcast)` | idempotency | 既に downloading または downloaded なら何もしない（重複ダウンロード防止） | `podcast/PodcastViewModel.kt:192,204` | `PodcastViewModelTest.kt:915`、`:859`、`:879` |
| CI-P15 | `removeDownload(id)` | failure_guarantee | 不正 id は `errorMessage` に反映し、キャッシュ状態を壊さない | `podcast/PodcastViewModel.kt:246` | `PodcastViewModelTest.kt:935`、`:952` |
| CI-P16 | `cancelDownloadsAndClearCache()` | postcondition | 進行中ダウンロードを中断してキャッシュ書き込みを残さず、完了済みキャッシュも削除して `downloadedIds` を空にする | `podcast/PodcastViewModel.kt:232` | `PodcastViewModelTest.kt:1009`、`:1038` |
| CI-P17 | `playNow` / `playNext` / `addToQueue` | postcondition | `playNow`=キュー内にあれば jump、無ければ現在の次に挿入して jump。`playNext`/`addToQueue`=未再生なら即再生、再生中なら挿入/追加のみ | `podcast/PodcastViewModel.kt:380-382,390-392,400-402` | `PodcastViewModelTest.kt:602`、`:618`、`:638`、`:657`、`:672`、`:690`、`:705` |
| CI-P18 | `removeFromQueue(id)` | postcondition | キューモデルのみ更新し、現在再生中の id を渡しても実再生は継続する | `podcast/PodcastViewModel.kt:418-419` | `PodcastViewModelTest.kt:794`、`:813` |
| CI-P19 | `moveUpNext(from, toOffset)` | postcondition | `PlaybackQueue.moveUpNext` へ委譲し結果を `queue` StateFlow へ反映する（`withContext(dispatcher)` で書込を直列化。WHY は `:426-430`） | `podcast/PodcastViewModel.kt:431-432` | `PodcastViewModelTest.kt:837` |
| CI-P20 | `togglePlayPause()` | postcondition | 再生中なら pause、停止中なら play | `podcast/PodcastViewModel.kt:436` | `PodcastViewModelTest.kt:411` |
| CI-P21 | `skipForward()` / `skipBackward()` | precondition | 現在位置 ±30/15 秒を `[0, duration]` にクランプしてから `seekTo` へ渡す | `podcast/PodcastViewModel.kt:445-457` | `PodcastViewModelTest.kt:344`、`:366` |
| CI-P22 | `setSpeed(speed)`（ViewModel → Controller） | postcondition | UI が提示する 8 段すべてが `PlayerController.setSpeed` へ伝播する | `podcast/PodcastViewModel.kt:465` | `PodcastViewModelTest.kt:391` |
| CI-P24 | 位置同期（`startPositionSync` / `syncPosition`） | environment_condition | 再生中は 15 秒ごとに `updatePlaybackPosition` を PATCH する。`stopPlayback` で最終同期後にタイマーを止める。キャッシュ再生中のオフラインでは `NetworkError` を握って同期を継続する | `podcast/PodcastViewModel.kt:522,536,473,502-512` | `PodcastViewModelTest.kt:273`、`:317`、`:246` |
| CI-P26 | `play(podcast)` の再生可否ゲート | precondition | `processing` は `fetchPodcast` を呼ばず再生不可メッセージ。`failed` / `partial_failed` は `error_message` を再生不可メッセージへ反映 | `podcast/PodcastViewModel.kt:272,549` | `PodcastViewModelTest.kt:146`、`:159`、`:171`、`:771` |
| CI-P25a | `play(podcast)` の失敗時 | failure_guarantee | `fetchPodcast` 失敗時は `prepare` を呼ばず `errorMessage` を設定する（部分再生を起こさない） | `podcast/PodcastViewModel.kt:304-311` | `PodcastViewModelTest.kt:131` |
| CI-P29 | `play(podcast)` の source 解決 | precondition | `resolvePlaybackSource` の結果が CACHED ならオンラインでも `fetchPodcast` を呼ばずキャッシュ URI で prepare、UNAVAILABLE なら再生せずエラー | `podcast/PodcastViewModel.kt:293` | `PodcastViewModelTest.kt:185`、`:210`、`:230` |

Evidence: 上表 20 件すべて `{status: confirmed, sources: 上記 path:line}`（`podcast/PlayerController.kt` 全 78 行を読了、`PodcastViewModel.kt` は `grep -Hn` による宣言行と upstream の行引用を突き合わせ）。

> ID の連番について: `CI-P23` は発行しない。`seekTo` の値域契約は `CI-P06` に統合したため欠番であり、未作成 ID を後から埋めない。

### 3.2 partial / unmet

```yaml
- id: CI-P02
  operation: "PlayerController の公開状態（isPlaying / positionSeconds / durationSeconds / playbackSpeed）"
  kind: invariant
  statement: "再生状態は idle | preparing | playing | paused | ended | failed(reason) を相互に区別できる単一の値として公開され、isPlaying はそこから導出される（同じ false が 4 つの異なる意味を表さない）"
  evidence: {status: confirmed, sources: ["podcast/PlayerController.kt:26-35"]}
  conformance:
    status: unmet
    sources: ["podcast/PlayerController.kt:26（isPlaying: StateFlow<Boolean> のみ）", "podcast/ExoPlayerController.kt:87-98（STATE_ENDED / STATE_IDLE）", "podcast/ExoPlayerController.kt:104-112（onIsPlayingChanged）"]
    note: "3 つの writer が同じ false を書き、一時停止・終了・未準備・失敗が同一表現になる（IV2）。"
  requirement_ids: [R2, R4]
  upstream: [OB-C1, G1, IV2]
  existing_tests: []
  blocked_by: [ARCH-SG6]
  test_spec:
    id: T-P02
    verifies: [CI-P02]
    given: "FakePlayerController ではなく実 PlayerController 契約に対する 5 つの局面（prepare 直後 / play 後 / pause 後 / 終了後 / 失敗後）"
    when: "各局面で公開状態を読む"
    then: "5 つの局面が相互に区別できる値になる"
    oracle: "公開された再生状態値の等価比較（Boolean の isPlaying では 5 局面を区別できないため oracle として不適格）"
    note: "ARCH-SG6 が pending（O6-A 状態拡張 / O6-B callback 追加 / O6-C 何もしない）。O6-B を選ぶと『状態値の等価比較』という oracle が成立せず T-P02 の形が変わるため、gate 確定前に実装へ渡してはならない"

- id: CI-P03
  operation: "PlayerController（実装 ExoPlayerController）の再生失敗"
  kind: failure_guarantee
  statement: "player の失敗（source 不到達 / decode 失敗 / network）は PlaybackFailure として観測可能になり、利用者へ告知可能な意味で公開される"
  evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:71-115"]}
  conformance:
    status: unmet
    sources: ["podcast/ExoPlayerController.kt:71-115（Player.Listener 実装に onPlayerError の override が無い）", "podcast/ExoPlayerController.kt:93-98（STATE_IDLE で位置 0・duration null に戻るだけ）"]
  requirement_ids: [R2, R7]
  upstream: [OB-C2, G2, DP5]
  existing_tests: []
  blocked_by: [ARCH-SG6]
  environment_condition: "ExoPlayer は JVM unit test から駆動できない。この CI の検証は (a) PlayerController interface レベルの契約テスト（Fake が failed を注入でき、ViewModel が反応する）と (b) ExoPlayerController の onPlayerError 配線を検証する instrumented test の 2 段に分かれる。androidTest ディレクトリが存在しないため (b) は現状実行基盤が無い（OB-N6）"
  test_spec:
    id: T-P03
    verifies: [CI-P03]
    given: "到達不能 URL、および非音声バイト列を書いたキャッシュファイル"
    when: "prepare して play する"
    then: "失敗が観測可能な値（reason を伴う）として公開され、isPlaying=false かつ position=0 という『停止と同じ見た目』にならない"
    oracle: "公開された失敗値とその reason"

- id: CI-P06
  operation: "PodcastViewModel.seekTo(seconds) → PlayerController.seekTo(seconds)"
  kind: precondition
  statement: "seekTo は 0 <= seconds <= (duration ?: seconds) に正規化してから実装へ渡す"
  evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:460", "podcast/PlayerController.kt:60"]}
  conformance:
    status: unmet
    sources: ["podcast/PodcastViewModel.kt:460-462（clamp せず素通し）", "podcast/PodcastViewModel.kt:445-457（skip 系だけが clamp する非対称）"]
    note: "UI ドラッグ由来の入力が唯一の防波堤であり、値域の owner が不在（OW7）。"
  requirement_ids: [R2]
  upstream: [OB-C6, G6]
  existing_tests: ["PodcastViewModelTest.kt:344 / :366（skipBackward / skipForward の clamp のみ。seekTo 直呼びの値域テストは無い）"]
  blocked_by: []
  test_spec:
    id: T-P06
    verifies: [CI-P06]
    given: "duration=100.0 を公開する Fake player で再生中"
    when: "seekTo(-10.0) と seekTo(500.0) を呼ぶ"
    then: "実装へ渡る値が 0.0 と 100.0 に正規化される"
    oracle: "FakePlayerController が記録した seekTo の引数列"

- id: CI-P07
  operation: "PodcastViewModel.setSpeed(speed) → PlayerController.setSpeed(speed)"
  kind: precondition
  statement: "setSpeed は許容集合（PlaybackConstants の速度段）の要素のみ受け付ける"
  evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:465", "podcast/PlayerController.kt:63"]}
  conformance:
    status: unmet
    sources: ["podcast/PlayerController.kt:63（任意 Float を受ける）", "podcast/PodcastViewModel.kt:465-467（検証なし）"]
    note: "選択肢が 2 箇所に二重定義（podcast/PlaybackConstants.kt:12 の 8 段 Float と settings/SettingsScreen.kt:1361 の 5 段 Double）で不一致。どちらが許容集合の正本かが未決のため、契約文の右辺が確定できない。"
  requirement_ids: [R2, R3]
  upstream: [OB-C6, G6, IV7]
  existing_tests: ["PodcastViewModelTest.kt:391（8 段すべての伝播は検査するが、集合外値の拒否は検査しない）"]
  blocked_by: [ARCH-SG2]
  test_spec:
    id: T-P07
    verifies: [CI-P07]
    given: "Fake player を注入した ViewModel"
    when: "setSpeed(0f) と setSpeed(9f) を呼ぶ"
    then: "実装へ渡らない（拒否）か、許容集合の最近傍へ正規化される"
    oracle: "FakePlayerController が記録した setSpeed の引数列"
    note: "ARCH-SG2 の付帯論点（8 段 / 5 段のどちらを正本とするか）が pending。集合が決まらないと then が書けない"

- id: CI-P09
  operation: "PlayerController.release()"
  kind: environment_condition
  statement: "release 後に prepare / play を呼んではならない（Media3 の ExoPlayer.release は再利用不可）。release は PlayerController の生存期間終了時にのみ呼ぶ"
  evidence: {status: confirmed, sources: ["podcast/PlayerController.kt:72-77（契約 doc）", "podcast/PlayerController.kt:16-22（WHY: シングルトン DI のためエピソード切替では stop を使う）"]}
  conformance:
    status: partial
    sources: ["podcast/PlayerController.kt:77（実装は『使用を検出したら異常終了させてよい』と許容するだけで、違反検出の義務を課していない）"]
    note: "production に release の呼出元が存在しない（ブリーフ §5、confirmed_by_router）。契約は文書として存在するが、違反を検出する機構もテストもない。"
  requirement_ids: [R2, R7]
  upstream: []
  existing_tests: []
  blocked_by: []
  test_spec:
    id: T-P09
    verifies: [CI-P09]
    given: "PlayerController 実装（Fake と ExoPlayer の双方）"
    when: "release() を呼んだ後に prepare(url, metadata) を呼ぶ"
    then: "明示的な失敗が観測される（黙って no-op して『再生されないだけ』にならない）"
    oracle: "投げられた例外型、または公開された失敗値"

- id: CI-P13
  operation: "PodcastViewModel の再生セッション遷移（play / playNow / handlePlaybackEnded / stopInternal）"
  kind: invariant
  statement: "遷移の完了後、currentPodcast は queue.current と同一 id か、両方 null である"
  evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:83-86", "podcast/PodcastViewModel.kt:115-121"]}
  conformance:
    status: unmet
    sources: ["podcast/PodcastViewModel.kt:361-368（advance 後に play(next)）", "podcast/PodcastViewModel.kt:502-512（stopInternal が _currentPodcast=null）", "podcast/PodcastViewModel.kt:304-311（NETWORK 失敗で errorMessage のみ設定し beginPlayback:324 に到達しない）"]
    note: "整合は :341-346 の doc が自認するとおり手続きで保たれており、新しい遷移を追加するたびに再実装が必要。DP1 の系列で乖離を構築できる（IV1）。"
  requirement_ids: [R2, R3]
  upstream: [OB-C5, G5, IV1, DP1]
  existing_tests: ["PodcastViewModelTest.kt:771（自動遷移先が processing の場合は queue と currentPodcast が整合することを検査＝この不変条件の 1 経路のみ pin）", "PodcastViewModelTest.kt:752（末尾停止時に Podcast を保持）"]
  blocked_by: [ARCH-SG1]
  test_spec:
    id: T-P13
    verifies: [CI-P13]
    given: "キュー [A, B] で A を再生中。B は未キャッシュかつオンラインで、fetchPodcast(B) が ApiException.NetworkError を投げる Fake"
    when: "A の onPlaybackCompleted を発火させる"
    then: "currentPodcast?.id == queue.current?.id である（または両方 null）"
    oracle: "currentPodcast と queue.current の id 比較"
    note: "ARCH-SG1 が pending。O1-A（queue を正本）なら currentPodcast は derived になり本 CI は型で自明になるが、O1-A は『停止』を表す queue 状態が無いという仕様 §2.1 側の不足に触るため 3 platform 合意が必要。O1-C（現状維持 + 2 経路を test で pin）なら T-P13 が恒久的な回帰ガードになる"

- id: CI-P27
  operation: "PodcastViewModel.beginPlayback（play / playNow / handlePlaybackEnded から）"
  kind: postcondition
  statement: "再生開始後の playbackSpeed は、セッション中に明示指定が無い限り preferencesStore.defaultPlaybackSpeed に等しい"
  evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:21-22"]}
  conformance:
    status: unmet
    sources: ["podcast/PodcastViewModel.kt:324-331（prepare → play のみ。setSpeed 呼出なし）", "podcast/PodcastViewModel.kt:465-467（setSpeed の唯一の呼出元は UI の速度選択）"]
    note: "PodcastViewModel は PreferencesStore への依存を現状持たない。既定速度は保存・server 同期されるが再生に適用されない（G4）。"
  requirement_ids: [R3]
  upstream: [OB-C4, G4]
  existing_tests: []
  blocked_by: [ARCH-SG2]
  test_spec:
    id: T-P27
    verifies: [CI-P27]
    given: "defaultPlaybackSpeed = 1.5 を返す InMemoryPreferencesStore を注入した ViewModel"
    when: "play(podcast) を呼ぶ"
    then: "FakePlayerController の playbackSpeed が 1.5 になる"
    oracle: "setSpeed に渡った引数、または playbackSpeed StateFlow の値"
    note: "ARCH-SG2 が pending（O2-A 毎回適用＋切替で reset / O2-B 初回のみ / O2-C 2 概念を統合）。O2-B を選ぶと『セッション中に明示指定が無い限り』の範囲がエピソード単位でなくアプリ起動単位になり、given が変わる"

- id: CI-P28
  operation: "PodcastViewModel.errorMessage（全 operation の失敗経路）"
  kind: invariant
  statement: "UI へ出す文言は失敗の意味から決まり、例外 message / transport 文字列を含まない"
  evidence: {status: confirmed, sources: ["network/ApiException.kt:13（\"HTTP Error $code\"）", "network/ApiException.kt:19（\"Network error: ${cause.message}\"）"]}
  conformance:
    status: unmet
    sources: ["podcast/PodcastViewModel.kt:135,212,214,251,309（_errorMessage.value = e.message）"]
    note: "利用者に無意味であるだけでなく内部詳細を露出する（QL4 制約に触れる）。feed/FeedViewModel.kt:89,106,204 も同型だが本ロールの契約対象 operation 外。"
  requirement_ids: [R4]
  upstream: [OB-C13, G12]
  existing_tests: ["PodcastViewModelTest.kt:94 / :131 / :900（errorMessage への『反映』は検査するが、文言が transport 文字列を含まないことは検査していない）"]
  blocked_by: []
  test_spec:
    id: T-P28
    verifies: [CI-P28]
    given: "fetchPodcast が HttpError(500) を投げる Fake、および NetworkError を投げる Fake"
    when: "play(podcast) を呼ぶ"
    then: "errorMessage が \"HTTP Error 500\" / \"Network error:\" を含まず、失敗の意味に対応した固定文言になる"
    oracle: "errorMessage 文字列（部分一致の否定 + 期待文言との一致）"
```

---

## 4. CI-S* — `core/PlaybackSourceResolver.kt`（§6.1）と resume 規則（§6.2）

| CI | kind | 契約条件 | 実装 | 既存テスト | 判定 |
|---|---|---|---|---|---|
| CI-S01 | postcondition | `resolvePlaybackSource(hasCached, isOnline)`: cached 有 ⟹ CACHED（オンライン可否を問わず）/ cached 無 + online ⟹ NETWORK / cached 無 + offline ⟹ UNAVAILABLE | `core/PlaybackSourceResolver.kt:16-21` | `PlaybackSourceResolverTest.kt`（4 @Test。4 入力組合せを全被覆: `キャッシュ有りオンラインならcachedを返す` / `キャッシュ有りオフラインでもcachedを返す` / `キャッシュ無しオンラインならnetworkを返す` / `キャッシュ無しオフラインならunavailableを返す`） | **met**（真理値表を全域被覆。spec:284-296 と一致＝SD3） |
| CI-S02 | invariant | 純関数であり外部 I/O・時刻・乱数に依存しない（JVM unit test から引数だけで全分岐に到達できる） | `core/PlaybackSourceResolver.kt:16-21`（`core/` は `android.*` / `network` import 0。ブリーフ §5 confirmed_by_router） | 同上（Fake もモックも不要な 4 テストがこの CI の証拠） | **met** |

```yaml
- id: CI-S03
  operation: "resolvePlaybackSource(hasCached=true, ...) → CACHED 経路の再生"
  kind: failure_guarantee
  statement: "キャッシュ済みエピソードの再生が失敗した場合、当該キャッシュは無効として扱われ（削除または CACHED 判定から除外）、次回は NETWORK 経路へ退避する"
  evidence: {status: confirmed, sources: ["core/PlaybackSourceResolver.kt:16-21", "network/AudioCacheManager.kt:54-62"]}
  conformance:
    status: unmet
    sources: ["core/PlaybackSourceResolver.kt:17-19（hasCached を常に CACHED と判定し、退避条件が引数に無い）", "network/AudioCacheManager.kt:54-62（bytes を無検証で書く）", "podcast/PodcastViewModel.kt:293（単一呼出点だが失敗フィードバック経路がない）"]
    note: "CACHED は常に優先されるため自動復旧しない。CI-P03（失敗が観測できない）と合わせ、破損キャッシュは恒久的な再生不能になる（IV9 / DP5）。"
  requirement_ids: [R2, R9]
  upstream: [OB-C18, G17, IV9, DP5]
  existing_tests: []
  blocked_by: [ARCH-SG6]
  test_spec:
    id: T-S03
    verifies: [CI-S03, CI-N17]
    given: "id=X のキャッシュに非音声バイト列が書かれており isCached(X)=true"
    when: "play(X) して player が失敗を報告する"
    then: "isCached(X) が false になる（または resolvePlaybackSource が X に対して CACHED を返さなくなる）"
    oracle: "isCached の戻り値 / resolvePlaybackSource の戻り値"
    note: "CI-P03（失敗の観測）が前提。CI-P03 が unmet のあいだ T-S03 は書けても『失敗を報告する』入力を production 経路で作れない（依存順序: T-P03 → T-S03）"

- id: CI-S04
  operation: "resolveResumePosition(serverSeconds, localSeconds | duration) — 未実装"
  kind: postcondition
  statement: "再生開始後の position は resolveResumePosition の結果に等しい（共有仕様 §6.2 の server-wins: サーバー側値が優先、ローカルで進めた位置は破棄）"
  evidence: {status: confirmed, sources: ["/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:300-303", "model/PodcastResponse.kt:31（playbackPositionSeconds はデコードされる）"]}
  conformance:
    status: unmet
    sources: ["podcast/PodcastViewModel.kt:324-331（beginPlayback は prepare → play のみで seek しない）", "podcast/PodcastViewModel.kt:522,536（位置は 15 秒ごとに書かれるが読まれない＝書き専用）"]
    note: "spec は server-wins を『現在の実装』と書いているが android には対応関数も呼出も存在しない（SD1 / G3）。仕様と実装のどちらへ寄せるかが未決。"
  requirement_ids: [R3, R9]
  upstream: [OB-C3, G3, SD1]
  existing_tests: []
  blocked_by: [ARCH-SG3]
  test_spec:
    id: T-S04
    verifies: [CI-S04]
    given: "server 位置 120 秒・duration 600 秒の PodcastResponse を返す Fake"
    when: "play(podcast) を呼ぶ"
    then: "seekTo(120.0) が prepare の後・play の前後いずれか仕様で定めた位置で 1 回だけ呼ばれる"
    oracle: "FakePlayerController が記録した seekTo の引数と呼出回数"
    structure: "純関数 resolveResumePosition を core へ置き表駆動テストを与える層と、beginPlayback が seek を呼ぶことを検証する ViewModel 層の 2 段（CI-S01/CI-S02 と同じ pattern）"
    note: "ARCH-SG3 が pending。O3-C（実装せず PATCH を削除）を選ぶと T-S04 は不要になり、代わりに『位置 PATCH が送られない』テストになる。gate 確定前に実装へ渡さない"

- id: CI-S05
  operation: "resolveResumePosition の完聴境界"
  kind: precondition
  statement: "server 位置が duration に十分近い（完聴相当）とき、resume 先は 0 に戻す（即 STATE_ENDED による意図しない自動スキップを防ぐ）"
  evidence:
    status: unknown
    sources: ["/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:298-303（閾値規則の記述が無い）"]
    confirmation_method: "ADR-022（docs/adr/022-server-side-playback-position-and-preferences.md）の resolveResumePosition 定義を読み、閾値規則が ADR 側に存在するかを確認する。無ければ 3 platform 合意事項として仕様へ追記が必要"
    impact_if_unresolved: "CI-S04 を実装した瞬間に『完聴済みエピソードを開くと即座に次へ飛ぶ』回帰が入る。ARCH-SG3 の O3-B がこの回帰そのものを指摘している"
  conformance: {status: unmet, sources: ["podcast/ExoPlayerController.kt:87-91 → podcast/PodcastViewModel.kt:348-370（STATE_ENDED が handlePlaybackEnded を発火して次へ進む）"]}
  requirement_ids: [R3, R9]
  upstream: [OB-C3]
  existing_tests: []
  blocked_by: [ARCH-SG3]
  test_spec:
    id: T-S05
    verifies: [CI-S05]
    given: "server 位置 = duration（または duration の 99%）の PodcastResponse"
    when: "play(podcast) を呼ぶ"
    then: "resume 先が 0 になり、handlePlaybackEnded が発火しない"
    oracle: "seekTo の引数と、markCompleted / advance の呼出回数 0"
```

---

## 5. CI-A* — `auth/AuthViewModel.kt` / `network/SessionStore.kt`(+`KeystoreSessionStore`) / `network/AuthInterceptor.kt`

### 5.1 met

| CI | operation | kind | 契約条件 | 実装 | 既存テスト |
|---|---|---|---|---|---|
| CI-A01 | `refreshAuth()` | postcondition | 保存トークンが無ければ `Unauthenticated` へ遷移し API を呼ばない | `auth/AuthViewModel.kt:99-102` | `AuthViewModelTest.kt:60` |
| CI-A02 | `refreshAuth()` | postcondition | トークンあり + `me()` 成功 ⟹ `Authenticated(user)` で user を保持し、`onAuthenticated` を呼ぶ | `auth/AuthViewModel.kt:105-107` | `AuthViewModelTest.kt:69`、`:271`、`:285` |
| CI-A04 | `refreshAuth()` | postcondition | `me()` が 401 ⟹ トークンを破棄して `Unauthenticated` | `auth/AuthViewModel.kt:111-113`、`:152` | `AuthViewModelTest.kt:81` |
| CI-A05 | `login(username, password)` | precondition | 空入力なら API を呼ばず文言を表示する | `auth/AuthViewModel.kt:139` | `AuthViewModelTest.kt:202` |
| CI-A06 | `login(username, password)` | postcondition | 成功 ⟹ トークンを保存し `Authenticated`、`onAuthenticated` を呼ぶ。401 / 429 は意味に応じた文言 | `auth/AuthViewModel.kt:147-149,152` | `AuthViewModelTest.kt:163`、`:179`、`:191`、`:298`、`:312` |
| CI-A09 | `SessionStore.clear()` | idempotency | 複数回呼んでも同じ事後条件（`load()==null`）へ到達する | `network/SessionStore.kt:19` | `InMemorySessionStoreTest.kt`（4 @Test: `clear後はloadがnullを返す` 等）。**Keystore 実装は対象外** |
| CI-A13 | `AuthInterceptor.intercept` | precondition | scheme・host・port の三点一致のときだけ `X-API-Key` を付与する（署名付き外部 URL への誤付与防止） | `network/AuthInterceptor.kt:30-36` | `AuthInterceptorTest.kt`（6 @Test: `baseUrl外のホストには認証ヘッダを付けない` / `同一ホストだがschemeが異なるURLにはヘッダが付かない` / `同一ホストだがportが異なるURLにはヘッダが付かない`）、`OkHttpApiClientTest.kt:55`、`:93` |
| CI-A14 | `AuthInterceptor.intercept` | postcondition | `tokenProvider()` が null を返す間は `Authorization` を付与しない | `network/AuthInterceptor.kt:37` | `AuthInterceptorTest.kt`（`tokenProviderが非nullならBearerが付く` / `tokenProviderがnullならBearerが付かない`）、`OkHttpApiClientTest.kt:67`、`:80` |
| CI-A15 | `logout()` | failure_guarantee | API 失敗・cleanup 例外でもトークンを破棄して `Unauthenticated` へ遷移する（ベストエフォート。spec:318 と一致＝SD5） | `auth/AuthViewModel.kt:167-181` | `AuthViewModelTest.kt:215`、`:241` |
| CI-A16 | `logout()` | postcondition | `onLogoutCleanup` が呼ばれる。`login` / `refreshAuth` では呼ばれない | `auth/AuthViewModel.kt:167-182` | `AuthViewModelTest.kt:231`、`:255`、`:325` |
| CI-A19 | `completePasskeyLogin(response)` | postcondition | トークンを保存し `Authenticated`、`onAuthenticated` を呼ぶ | `auth/AuthViewModel.kt:194-197` | `AuthViewModelTest.kt:337`、`:350` |
| CI-A21 | `applyProfileUpdate(updatedUser)` | postcondition | `Authenticated` 状態なら user を差し替え、非 `Authenticated` なら no-op | `auth/AuthViewModel.kt:209-211` | `AuthViewModelTest.kt:363`、`:377` |

Evidence: 上表 12 件すべて `{status: confirmed, sources: 上記 path:line}`（`SessionStore.kt` 全 20 行・`AuthInterceptor.kt` 全 40 行を読了、`AuthViewModel.kt` は `grep -Hn` による宣言/遷移行）。

### 5.2 partial / unmet

```yaml
- id: CI-A03
  operation: "refreshAuth()"
  kind: prohibited_transition
  statement: "unauthorized 以外の失敗（network / decoding / server）では sessionStore.clear() を呼ばず、Authenticated / Unknown を維持したまま再試行可能な状態へ遷移する"
  evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:105-115", "network/ApiException.kt:16,19"]}
  conformance:
    status: unmet
    sources: ["auth/AuthViewModel.kt:105-115（ApiException 全種を catch-all して clear() → Unauthenticated）", "auth/AuthViewModel.kt:96-97（catch-all を意図的と明記するコメント）"]
    note: "機内モード起動やサーバ 5xx で有効セッションが端末から消える（DP2）。R5 の prohibition に直接違反する。"
  requirement_ids: [R5]
  upstream: [OB-C7, G7, DP2]
  existing_tests:
    conflicting: ["AuthViewModelTest.kt:95 `トークンありでmeがNetworkErrorでもトークン破棄してUnauthenticatedになる`"]
    note: "**この既存テストは違反挙動を正として pin している**。CI-A03 を導入するには当該テストを反転（『トークンが保持される』）する必要があり、単なるテスト追加では RED にできない。t-wada 式 TDD の手順としては『既存テストの意図を仕様変更として反転 → RED → 実装』の順を明示する必要がある"
  blocked_by: [ARCH-SG5]
  test_spec:
    id: T-A03
    verifies: [CI-A03]
    given: "有効トークンが保存済みで、me() が ApiException.NetworkError を投げる Fake"
    when: "refreshAuth() を呼ぶ"
    then: "sessionStore.load() != null かつ authState != Unauthenticated（再試行可能な状態）"
    oracle: "sessionStore.load() の戻り値と authState の値"
    contrast_case: "同じ given で me() が unauthorized のときだけ clear + Unauthenticated になる対照ケース（= CI-A04 の既存テスト :81 を維持）"
    note: "ARCH-SG5 が pending（O5-A Unauthorized 型追加 / O5-B オフライン猶予状態 / O5-C NetworkError だけ別扱い）。O5-B を選ぶと then が『Authenticated 相当の UI が出る』へ変わり、QL4 とのトレードオフを user が承認する必要がある"

- id: CI-A07
  operation: "SessionStore.save(token)"
  kind: failure_guarantee
  statement: "save は保存の成否を呼び出し元へ返し、失敗時に Authenticated へ遷移しない"
  evidence: {status: confirmed, sources: ["network/SessionStore.kt:13（fun save(token: String) — 戻り値 Unit）"]}
  conformance:
    status: unmet
    sources: ["network/SessionStore.kt:12-13（成否を表す戻り値が型に無い）", "network/KeystoreSessionStore.kt:49-52（暗号化失敗を飲んで no-op）", "auth/AuthViewModel.kt:147-149（save の直後に無条件で Authenticated へ遷移）"]
    note: "IV3（Authenticated かつ永続トークン無し）の直接原因。当該起動中は 401 が続き、再起動で無言のログアウトになる。"
  requirement_ids: [R5, R7]
  upstream: [OB-C20, G19, IV3]
  existing_tests: []
  blocked_by: []
  test_spec:
    id: T-A07
    verifies: [CI-A07]
    given: "save が常に失敗する SessionStore 実装（テスト用。現在の interface では失敗を表現できないため、まず戻り値型の変更が必要）"
    when: "login(user, pass) を成功応答で呼ぶ"
    then: "authState が Authenticated にならず、保存失敗が利用者へ告知される"
    oracle: "authState の値と errorMessage"

- id: CI-A08
  operation: "SessionStore.load()"
  kind: postcondition
  statement: "未保存・削除済み・復号失敗時は null を返す（呼び出し元は『トークンが無い』と同義に扱える）"
  evidence: {status: confirmed, sources: ["network/SessionStore.kt:15-16"]}
  conformance:
    status: partial
    sources: ["network/KeystoreSessionStore.kt:69-91（envelope deserialize 失敗 :71-74 / 復号失敗 :83 で clearBrokenState :88-91 を経て null）", "network/KeystoreSessionStore.kt:55-61（cacheLoaded のメモリキャッシュ）"]
    note: "戻り値の契約は満たすが、『復号失敗で prefs を削除した』事実が authState へ届かない（CI-A11）。起動後に初めて load が失敗する順序では IV4 になる（DP7、到達可能性は unknown）。"
  requirement_ids: [R5]
  upstream: [OB-C20, G19, IV4, DP7]
  existing_tests: ["InMemorySessionStoreTest.kt は interface 意味論のみ（`何も保存していない状態ではloadがnullを返す` / `saveした値がloadで取得できる` / `saveを2回呼ぶと最新の値で上書きされる`）"]
  blocked_by: []
  test_spec:
    id: T-A08
    verifies: [CI-A08]
    given: "prefs に壊れた envelope（deserialize 不能）が入った KeystoreSessionStore"
    when: "load() を呼ぶ"
    then: "null が返り、prefs が消去され、かつその事実が観測可能な値として公開される"
    oracle: "load() の戻り値と、通知経路（CI-A11 で定義）の観測値"
    environment_condition: "Android Keystore は JVM unit test から使えない。KeystoreSessionStore の暗号層を port（`Cipher` 相当の interface）として切り出すか instrumented test を用意しないと本 test は書けない（OB-N6）"

- id: CI-A10
  operation: "KeystoreSessionStore.save / load / clear"
  kind: failure_guarantee
  statement: "暗号化・復号の失敗が沈黙せず、上位（AuthViewModel）が状態遷移の判断に使える形で返る"
  evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:40-53", "network/KeystoreSessionStore.kt:69-91"]}
  conformance:
    status: unmet
    sources: ["network/KeystoreSessionStore.kt:49-52（catch して no-op）", "network/KeystoreSessionStore.kt:20-27（この writer は単体テスト対象外と自認するコメント）"]
  requirement_ids: [R5, R7]
  upstream: [OB-C20, G19]
  existing_tests:
    partial: ["EncryptedTokenEnvelopeTest.kt（3 @Test。envelope の直列化のみで暗号層の失敗経路は非対象）"]
    note: "KeystoreSessionStore 自体のテストファイルは app/src/test/java/com/rioikeda/newslisten/network/ に存在しない（ls 済み）"
  blocked_by: []
  test_spec:
    id: T-A10
    verifies: [CI-A10, CI-A07]
    given: "暗号化 port が常に例外を投げるよう注入した KeystoreSessionStore"
    when: "save(token) を呼ぶ"
    then: "失敗が呼び出し元へ返る（例外または失敗値）"
    oracle: "save の戻り値 / 投げられた例外型"
    prerequisite: "暗号操作を interface として注入可能にする構造変更が先に必要（現状は Keystore を直接掴むため JVM test から到達不能）"

- id: CI-A11
  operation: "トークンを削除する全 writer（logout / refreshAuth 失敗 / clearBrokenState）"
  kind: invariant
  statement: "保存トークンを削除した writer は必ず authState へ通知する（『トークン無し』かつ『Authenticated』の状態を作らない）"
  evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:88-91（clearBrokenState）", "auth/AuthState.kt:13-22"]}
  conformance:
    status: unmet
    sources: ["network/KeystoreSessionStore.kt:88-91（authState への通知経路が無い alternate writer）"]
    note: "IV4 の主要因。SessionStore が auth へ通知すると依存方向が network → auth へ逆流するため、ARCH-SG5 末尾が推奨する関数注入（`onUnauthorized: () -> Unit`）と同じ pattern で解く必要がある。"
  requirement_ids: [R5]
  upstream: [OB-C20, G19, IV4]
  existing_tests: []
  blocked_by: [ARCH-SG5]
  test_spec:
    id: T-A11
    verifies: [CI-A11]
    given: "認証済み（Authenticated）かつ、load が復号失敗で prefs を消去する SessionStore"
    when: "load() が呼ばれる"
    then: "authState が Unauthenticated へ遷移する"
    oracle: "authState の値"

- id: CI-A12
  operation: "セッション中の任意 API 呼び出し（AuthInterceptor / ApiClient 経由）"
  kind: postcondition
  statement: "セッション中に unauthorized を受けた場合、authState は Unauthenticated へ遷移し保存トークンは削除される"
  evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20（unauthorized を表す型が無い）", "network/AuthInterceptor.kt:26-39（ヘッダ付与のみで response を見ない）"]}
  conformance:
    status: unmet
    sources: ["network/AuthInterceptor.kt:26-39", "auth/AuthState.kt:13-22（遷移の入口が refreshAuth / login / logout のみ）"]
    note: "認証済み UI のまま全 API が失敗し続ける（IV4）。G8 は 401 経路で独立に blocker。"
  requirement_ids: [R5]
  upstream: [OB-C8, G8]
  existing_tests: []
  blocked_by: [ARCH-SG5]
  test_spec:
    id: T-A12
    verifies: [CI-A12]
    given: "Authenticated 状態で、任意の API が unauthorized を返す MockWebServer"
    when: "その API を呼ぶ"
    then: "authState が Unauthenticated になり sessionStore.load() == null"
    oracle: "authState と sessionStore.load()"
    note: "ARCH-SG5 が pending。interceptor から auth 状態を書くと依存方向が逆流するため、`di/AppContainer.kt:72` の tokenProvider と同型の関数注入（onUnauthorized）で実現する方針が推奨候補として記録されている（ARCH §6 SG5 末尾）"

- id: CI-A17
  operation: "onLogoutCleanup（di/AppContainer が合成するラムダ）"
  kind: idempotency
  statement: "cleanup は部分失敗しても再実行で同じ事後条件へ到達し、失敗した構成要素が観測可能である"
  evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:167-182"]}
  conformance:
    status: unmet
    sources: ["di/AppContainer.kt:266-281（各 try/catch が沈黙。confirmed_by_upstream: completeness-package §11 G9 / §12 DP6）"]
  requirement_ids: [R6]
  upstream: [OB-C10, G9, DP6]
  existing_tests: ["AuthViewModelTest.kt:241（cleanup 例外でも遷移することは検査。失敗の観測可能性と再実行の冪等は非対象）"]
  blocked_by: []
  test_spec:
    id: T-A17
    verifies: [CI-A17]
    given: "cleanup の一部（FCM 解除）だけが失敗する合成ラムダ"
    when: "logout() を 1 回、続けてもう 1 回呼ぶ"
    then: "1 回目で失敗が観測可能な値として残り、2 回目で全構成要素が完了状態になる"
    oracle: "cleanup 結果の可観測値と、音声キャッシュ / FCM トークンの最終状態"

- id: CI-A18
  operation: "主体が離れる遷移（logout と失効の両方）"
  kind: postcondition
  statement: "遷移の完了後、ユーザー固有 data（音声キャッシュ・FCM トークン・server 同期 preferences・再生セッション）が端末に残らない（spec §6.3）"
  evidence:
    status: confirmed
    sources: ["/Users/rio/git/news-listen/docs/design/shared-playback-spec.md:305-316（表に Web 行と iOS 行のみ。**Android 行が無く削除対象が仕様側で未規定**）"]
  conformance:
    status: unmet
    sources: ["auth/AuthViewModel.kt:111-114（失効経路には cleanup が無い）", "auth/AuthViewModel.kt:167-182（logout 経路のみ）", "preferences/PreferencesStore.kt:42-60（clear 系 API が存在しない）"]
    note: "spec 側の不足（SD2）と実装側の部分適用が重なっている。仕様に Android 行を追記しないと契約の右辺が確定しない。"
  requirement_ids: [R6, R9]
  upstream: [OB-C9, G9, DP6, SD2]
  existing_tests: ["AuthViewModelTest.kt:231（cleanup ラムダが呼ばれることのみ。削除対象の事後条件は非対象）"]
  blocked_by: [ARCH-SG4]
  test_spec:
    id: T-A18
    verifies: [CI-A18, CI-X08]
    given: "音声キャッシュあり・preferences 変更済み・再生中の状態"
    when: "logout() を呼ぶ（および失効経路: me() が unauthorized）"
    then: "両経路で同じ事後条件に到達する（削除範囲は ARCH-SG4 の決定に従う）"
    oracle: "AudioCacheManager.cacheSize()、PreferencesStore の各 StateFlow 値、queue / currentPodcast、player の停止状態"
    note: "ARCH-SG4 が pending（O4-A 現状範囲を失効経路にも / O4-B preferences 4 key と再生状態も / O4-C 範囲を変えず ADR で受容）。O4-C は QL4 が constraint であるため user の明示承認なしに採れない。gate 未決のあいだ then の右辺が書けない"

- id: CI-A20
  operation: "syncPreferences()（refreshAuth / login の成功直後）"
  kind: precondition
  statement: "server から受け取った preferences 値は、PreferencesStore へ書く前に許容集合で検証される"
  evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:121-126"]}
  conformance:
    status: unmet
    sources: ["auth/AuthViewModel.kt:125-126（server 値を無検証で setDefaultPlaybackSpeed / setWeeklyGoalEpisodes へ渡す）"]
    note: "DP3 の系列（server が 0.0 / 999 を返す）で定義外の値が永続化され、端末を再インストールしない限り自己回復しない。CI-X01/CI-X02/CI-X03 と同一原因（invariant owner 不在 OW6）。"
  requirement_ids: [R3]
  upstream: [OB-C15, G14, IV6, IV7, DP3]
  existing_tests:
    conflicting: ["AuthViewModelTest.kt:111 / :125（server 値が StateFlow と PreferencesStore へ反映されることを検査。値域検証を入れると、テストが使う値が許容集合内であれば維持できるが、テストが境界値を使っている場合は反転が必要）"]
  blocked_by: []
  test_spec:
    id: T-A20
    verifies: [CI-A20, CI-X02, CI-X03]
    given: "default_playback_speed=0.0、weekly_goal_episodes=999 を返す Fake ApiClient"
    when: "refreshAuth() を成功させる"
    then: "PreferencesStore の値が既定または直前の妥当値に留まる"
    oracle: "PreferencesStore.defaultPlaybackSpeed / weeklyGoalEpisodes の StateFlow 値"
```

---

## 6. CI-N* — `network/ApiClient.kt` / `OkHttpApiClient.kt` / `AudioCacheManager.kt`

### 6.1 met

| CI | operation | kind | 契約条件 | 実装 | 既存テスト |
|---|---|---|---|---|---|
| CI-N01 | `validateResponse(response)` | postcondition | 429 ⟹ `RateLimited(retryAfterSeconds)`（`Retry-After` 欠落時は null）。その他の非 2xx ⟹ `HttpError(code, bodyMessage)` | `network/OkHttpApiClient.kt:456-462` | `OkHttpApiClientTest.kt:115`、`:127`、`:192` |
| CI-N03 | 全 HTTP operation | failure_guarantee | `IOException`（接続不可・タイムアウト）⟹ `ApiException.NetworkError` にラップ | `network/ApiException.kt:19` | `OkHttpApiClientTest.kt:181` |
| CI-N04 | 全 HTTP operation | failure_guarantee | JSON デコード失敗 ⟹ `ApiException.DecodingError` にラップ | `network/OkHttpApiClient.kt:446` | `OkHttpApiClientTest.kt:204` |
| CI-N05 | `downloadAudio(url)` | precondition | 署名付き外部 URL（別ホスト）へ認証ヘッダを付与しない | `network/OkHttpApiClient.kt:144-150` + `network/AuthInterceptor.kt:30-35` | `OkHttpApiClientTest.kt:93` |
| CI-N08a | `updatePlaybackPosition(id, positionSeconds)` | postcondition | PATCH で `position_seconds` ボディを送り `PodcastResponse` を返す | `network/OkHttpApiClient.kt:117` | `OkHttpApiClientTest.kt:157`、`PlaybackPositionRequestDecodingTest.kt`（2 @Test） |
| CI-N09 | `revokeSession(id)` | idempotency | 404 でも例外を投げず冪等成功として扱い、404 以外のエラーは `HttpError` を投げる | `network/OkHttpApiClient.kt:299-305` | `OkHttpApiClientTest.kt:532`、`:540`、`:521` |
| CI-N10 | `AudioCacheManager.validateId(id)` | precondition | 英数字・ハイフン・アンダースコアのみを true とし、空文字・`..`・`/`・`.` を false とする（path traversal 防御） | `network/AudioCacheManager.kt:47` | `AudioCacheManagerTest.kt`（5 @Test: `validateIdは英数字ハイフンアンダースコアのみのidをtrueとする` / `validateIdは空文字をfalseとする` / `validateIdはpath_traversal文字列をfalseとする` / `validateIdはスラッシュを含むidをfalseとする` / `validateIdはドットを含むidをfalseとする`） |
| CI-N11 | `cache(id, bytes)` | precondition | 不正 id は `InvalidId` を投げ、ファイルを書き込まない | `network/AudioCacheManager.kt:54-62`、`:89` | `AudioCacheManagerTest.kt`（`cacheは不正なidに対してInvalidIdをthrowしファイルを書き込まない`） |
| CI-N12 | `cache(id, bytes)` | failure_guarantee | 書込中の `IOException` は `WriteFailed` にラップし、部分ファイルを残さない（中途半端なキャッシュを作らない） | `network/AudioCacheManager.kt:54-62`、`:20` | `AudioCacheManagerTest.kt`（`cacheはIOException発生時にWriteFailedへラップしてthrowしファイルを残さない`） |
| CI-N13 | `isCached(id)` / `cachedFileUri(id)` | invariant | `cache` 後は `isCached=true` かつ `cachedFileUri != null`、`remove` 後は `false` / `null`（2 つの読み取り口が矛盾しない） | `network/AudioCacheManager.kt:65`、`:68` | `AudioCacheManagerTest.kt`（`cacheしたidはisCachedがtrueになりcachedFileUriが取得できる` / `removeするとisCachedがfalseに戻りcachedFileUriがnullになる`） |
| CI-N14 | `remove(id)` | idempotency | 存在しない id でも例外を投げない。不正 id は `InvalidId` を投げる | `network/AudioCacheManager.kt:75`、`:89` | `AudioCacheManagerTest.kt`（`removeは存在しないidに対して例外を投げない冪等操作` / `removeは不正なidに対してInvalidIdをthrowする`） |
| CI-N15 | `removeAll()` | postcondition | キャッシュした全ファイルを削除する（logout cleanup の構成要素） | `network/AudioCacheManager.kt:81` | `AudioCacheManagerTest.kt`（`removeAllはキャッシュした全ファイルを削除する`） |
| CI-N16 | `cacheSize()` | postcondition | キャッシュ済み全ファイルサイズの合計を返し、`remove` 後は減算される | `network/AudioCacheManager.kt:86` | `AudioCacheManagerTest.kt`（`cacheSizeはキャッシュ済み全ファイルサイズの合計を返す` / `cacheSizeはremove後に減算される`） |

Evidence: 上表 13 件すべて `{status: confirmed, sources: 上記 path:line}`（`AudioCacheManager.kt` の宣言行を `grep -Hn` で取得、`ApiException.kt` 全 20 行を読了、`OkHttpApiClient.kt` は該当 operation 行を `grep -Hn`）。

### 6.2 partial / unmet

```yaml
- id: CI-N02
  operation: "ApiClient の全 operation の失敗（consumer が受け取る型）"
  kind: postcondition
  statement: "API 失敗は network | unauthorized | forbidden | rate_limited(retryAfter) | not_found | conflict | server のいずれかの意味として消費者へ渡る"
  evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20（RateLimited | HttpError(code) | DecodingError | NetworkError の 4 種のみ）"]}
  conformance:
    status: unmet
    sources: ["network/ApiException.kt:13（HttpError が status 数値をそのまま consumer へ渡す）", "network/OkHttpApiClient.kt:456-462"]
    note: "結果として HttpError.code の数値比較が 8 箇所（Screen 層 podcast/QuizSheet.kt:195 を含む）へ分散する（ブリーフ §4、confirmed_by_router）。R4 が要求する意味を渡せない。"
  requirement_ids: [R1, R4]
  upstream: [OB-C11, G10, G11]
  existing_tests:
    partial: ["OkHttpApiClientTest.kt:115 / :127（rate_limited と retryAfter は意味として検証済み）", "OkHttpApiClientTest.kt:192（500 は HttpError＝数値のまま）", "OkHttpApiClientTest.kt:472 / :484（400 / 422 も HttpError の code 比較）"]
    note: "rate_limited のみ意味に到達している。他 6 意味は型が無いため oracle を書けない"
  blocked_by: []
  test_spec:
    id: T-N02
    verifies: [CI-N02]
    given: "MockWebServer が 401 / 403 / 404 / 409 / 429(Retry-After) / 500 / 接続断 を返す 7 系列"
    when: "production 経路（OkHttpApiClient）の任意 operation を呼ぶ"
    then: "7 系列が意味ごとに区別できる失敗として返る（status 数値を見ずに分岐できる）"
    oracle: "返された失敗値の型の等価比較（HttpError.code の数値比較は oracle として不適格）"

- id: CI-N06
  operation: "downloadAudio(url)"
  kind: postcondition
  statement: "返る ByteArray は音声として再生可能である（2xx で返った本文の妥当性が検査される）"
  evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:144-150"]}
  conformance:
    status: unmet
    sources: ["network/OkHttpApiClient.kt:452-465（validateResponse は非 2xx のみ弾く）", "network/AudioCacheManager.kt:54-62（受けた bytes を無検証で書く）"]
    note: "200 で HTML エラーページや途中で切れた bytes が返るとそのままキャッシュに入る（DP5）。Content-Type / 長さ / 先頭バイトのいずれも検査していない。"
  requirement_ids: [R2, R9]
  upstream: [OB-C18, G17, IV9, DP5]
  existing_tests: []
  blocked_by: []
  test_spec:
    id: T-N06
    verifies: [CI-N06]
    given: "MockWebServer が 200 で Content-Type: text/html の本文を返す"
    when: "downloadAudio(url) を呼ぶ"
    then: "失敗として拒否される（またはキャッシュ書込前に無効と判定される）"
    oracle: "投げられた失敗型、または cache() が呼ばれないこと"
    note: "『どこまで検査するか』（Content-Type のみ / Content-Length 一致 / magic bytes）は product 判断が必要 → OB-N4"

- id: CI-N07
  operation: "markCompleted(id)"
  kind: idempotency
  statement: "同一 id に対する複数回の markCompleted は 1 回目と同じ事後条件になる（重複完聴記録が集計を壊さない）"
  evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:127"]}
  conformance:
    status: partial
    sources: ["network/OkHttpApiClient.kt:127（空ボディ POST を送るだけ。冪等性は server 側の責務であり client 契約に現れない）"]
    note: "client 側の契約としては『best-effort で失敗を無視する』（podcast/PodcastViewModel.kt:348-370 / PodcastViewModelTest.kt:466）が定義済み。冪等性そのものは server 契約であり、本 package の subject（android の公開 operation）の外側にある。"
  requirement_ids: [R4, R9]
  upstream: []
  existing_tests: ["OkHttpApiClientTest.kt:404 `完聴記録は空ボディのPOSTで送る`（送信形式のみ）", "PodcastViewModelTest.kt:466（失敗でも遷移が進む）"]
  blocked_by: []
  test_spec:
    id: T-N07
    verifies: [CI-N07]
    given: "同一 podcast を 2 回完聴させる（handlePlaybackEnded を 2 回発火）"
    when: "markCompleted が 2 回呼ばれる"
    then: "client は 2 回送る（抑制しない）ことを明示的に pin する、または 1 回に抑制する"
    oracle: "MockWebServer が受けた request 数"
    open_question: "どちらが正しいかは server の冪等保証に依存する。server 契約を確認せずに client 側で抑制を実装すると『別セッションの再完聴が記録されない』回帰になりうる → OB-N5"

- id: CI-N08b
  operation: "updatePlaybackPosition(id, positionSeconds) の順序"
  kind: invariant
  statement: "古いリクエストの応答が新しいリクエストの結果を上書きしない（15 秒周期の同期が並行したときの後勝ち保証）"
  evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:522,536"]}
  conformance:
    status: partial
    sources: ["podcast/PodcastViewModel.kt:522（タイマーは後勝ちの 1 系統に収束する。PodcastViewModelTest.kt:555 / :521 で検証済み）", "network/OkHttpApiClient.kt:117（API 単体には順序保証が無い）"]
    note: "同種の順序契約は settings で既に定式化・検証されている（SettingsViewModelTest.kt:415 `古いリクエストの失敗応答は最新の成功を上書きしない`）。位置同期側には対応するテストが無い。"
  requirement_ids: [R3]
  upstream: []
  existing_tests: ["PodcastViewModelTest.kt:555（タイマー 1 系統）", "PodcastViewModelTest.kt:521（切替時に A の同期が止まる）"]
  blocked_by: []
  test_spec:
    id: T-N08b
    verifies: [CI-N08b]
    given: "position 60 の PATCH が遅延し、position 75 の PATCH が先に完了する Fake"
    when: "両方の応答が届く"
    then: "ViewModel が保持する位置が 75 に留まる"
    oracle: "currentPodcast の playbackPositionSeconds / 次回同期で送られる値"

- id: CI-N17
  operation: "AudioCacheManager.isCached(id) の意味"
  kind: invariant
  statement: "isCached(id) == true ならば当該 id は再生可能である（『ダウンロード済み』表示が再生可能性を含意する）"
  evidence: {status: confirmed, sources: ["network/AudioCacheManager.kt:65", "core/PlaybackSourceResolver.kt:17-19"]}
  conformance:
    status: unmet
    sources: ["network/AudioCacheManager.kt:65（ファイルの存在のみを見る）", "network/AudioCacheManager.kt:54-62（内容の妥当性を誰も検査しない）"]
    note: "CI-S03 / CI-N06 と同一の gap（IV9）。isCached の意味が『ファイルがある』に過ぎず、UI とユーザーの解釈（再生できる）と乖離している。"
  requirement_ids: [R2, R9]
  upstream: [OB-C18, G17, IV9]
  existing_tests: ["AudioCacheManagerTest.kt（`cacheしたidはisCachedがtrueになり…`）は『書いたら true』を pin しており、この CI の反例（書いたが再生不能）を排除しない"]
  blocked_by: []
  test_spec: {id: T-S03, note: "T-S03 と同一シナリオで検証する（verifies: [CI-S03, CI-N17]）。重複 T* を発行しない"}

- id: CI-N18
  operation: "ApiClient interface の未 override メソッド（throwing default）"
  kind: failure_guarantee
  statement: "実装が提供しない operation の呼び出しは compile 時に検出される（実行時例外に落ちない）。また失敗 message に内部識別子を含めない"
  evidence:
    status: confirmed_by_upstream
    sources: ["architecture-strategy-package.md §6 SG7（network/ApiClient.kt:67 の throwing default が id を message に含む、throwing default 9 箇所）", "ブリーフ §4（ApiClient interface 44 メソッド / Fake 9 ファイル 1,393 行）"]
    self_verification: "本ロールは network/ApiClient.kt（224 行）の throwing default 行を自力で grep していない。行番号 :67 は upstream の引用をそのまま用いており、独立確認は未実施"
  conformance:
    status: unmet
    sources: ["network/ApiClient.kt:67（upstream 引用）"]
    note: "production interface に throwing default があるため、Fake の override 漏れが compile error にならず実行時に落ちる。R7（Fake の重複で契約が分散しない）の構造的原因。"
  requirement_ids: [R1, R7]
  upstream: []
  existing_tests: []
  blocked_by: [ARCH-SG7]
  test_spec:
    id: T-N18
    verifies: [CI-N18]
    given: "ApiClient から throwing default を削除した状態"
    when: "コンパイルする"
    then: "override 漏れが compile error になる（テストではなく型で保証する契約＝テストが不要になることが成功条件）"
    oracle: "コンパイル結果。加えて『失敗 message に id が含まれない』ことは文字列検査で pin する"
    note: "ARCH-SG7 が pending（O7-A consumer 別の狭い port / O7-B test 側の共通 Fake 基底 + production default 全削除 / O7-C 現状維持 + message から id 除去）。共通ブリーフ §1 により『汎用の広い port』方向は再提案しない"
```

---

## 7. CI-X* — `preferences/PreferencesStore.kt`（値域）

| CI | operation | kind | 契約条件 | 実装適合 | 既存テスト |
|---|---|---|---|---|---|
| CI-X04 | `setArticleOpenMode` / `setTimeFormat` | precondition | 引数が enum 型であり、定義外の値を型が排除する | **met**（`preferences/PreferencesStore.kt:48-52` が `ArticleOpenMode` / `TimeFormat` を受ける） | `ArticleOpenModeTest.kt`（3 @Test）、`TimeFormatTest.kt`（3 @Test）、`InMemoryPreferencesStoreTest.kt`（`setArticleOpenModeで値を更新するとStateFlowに反映される` 等） |
| CI-X05 | 全 StateFlow の既定値 | postcondition | 未設定時は `Difficulty.DEFAULT` / 1.0 / `IN_APP` / `ABSOLUTE` / true / true / 3 を公開する | **met**（`preferences/PreferencesStore.kt:17-40` の doc と一致） | `InMemoryPreferencesStoreTest.kt`（`既定値はDifficultyのDEFAULT_速度1_0_ArticleOpenModeのIN_APP_TimeFormatのABSOLUTEである` / `フィードバック設定は既定で有効で更新できる`）、`DataStorePreferencesStoreTest.kt:25` |
| CI-X07 | `seenAchievementIds` | invariant | 表示済み実績 ID は重複なく永続化される（祝福の二重表示防止） | **met** | `DataStorePreferencesStoreTest.kt:46` |

```yaml
- id: CI-X01
  operation: "setDefaultDifficulty(code)"
  kind: precondition
  statement: "code は Difficulty.code の許容集合の要素である（定義外の code を永続化しない）"
  evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:18-19,42-43"]}
  conformance:
    status: unmet
    sources: ["preferences/PreferencesStore.kt:43（引数が String で値域が型に無い）", "preferences/DataStorePreferencesStore.kt:81-87（無検証で永続化。confirmed_by_upstream: completeness §11 G14）"]
  requirement_ids: [R3]
  upstream: [OB-C15, G14]
  existing_tests: ["DifficultyTest.kt（5 @Test。Difficulty 側の正規化は検証済みだが store の入口は守られていない）"]
  blocked_by: []
  test_spec: {id: T-X01, verifies: [CI-X01], given: "InMemory / DataStore の両 PreferencesStore 実装", when: "setDefaultDifficulty(\"unknown_level\") を呼ぶ", then: "拒否されるか既定値へ正規化される", oracle: "defaultDifficulty StateFlow の値"}

- id: CI-X02
  operation: "setDefaultPlaybackSpeed(speed)"
  kind: precondition
  statement: "speed は許容集合（PlaybackConstants の速度段）の要素である"
  evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:21-22,45-46"]}
  conformance:
    status: unmet
    sources: ["preferences/PreferencesStore.kt:46（Double を無制約に受ける）", "preferences/DataStorePreferencesStore.kt:85-87（無検証。confirmed_by_upstream）"]
    note: "現状は既定速度が player へ適用されないため実害が出ていない（CI-P27 unmet）。CI-P27 を解消した瞬間に 0.0 倍速が実再生へ流れる（IV7）。**unmet 2 件が互いの症状を隠している**ため、CI-P27 と CI-X02 は同一の増分で塞ぐ必要がある。"
  requirement_ids: [R3]
  upstream: [OB-C15, G14, IV7, DP3]
  existing_tests: ["InMemoryPreferencesStoreTest.kt（`setDefaultPlaybackSpeedで値を更新するとStateFlowに反映される`。許容集合内の値のみ）"]
  blocked_by: [ARCH-SG2]
  test_spec: {id: T-X02, verifies: [CI-X02], given: "両 PreferencesStore 実装", when: "setDefaultPlaybackSpeed(0.0) / (12.0) を呼ぶ", then: "拒否されるか許容集合へ正規化される", oracle: "defaultPlaybackSpeed StateFlow の値"}

- id: CI-X03
  operation: "setWeeklyGoalEpisodes(episodes)"
  kind: precondition
  statement: "episodes は許容集合 {3, 5, 7, 10} の要素である"
  evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:36-37"]}
  conformance:
    status: partial
    sources: ["settings/SettingsViewModel.kt:193-197（UI 由来経路のみ検証。confirmed_by_upstream: completeness §11 G14）", "auth/AuthViewModel.kt:126（server 同期経路は無検証）", "preferences/DataStorePreferencesStore.kt:105-107（store 自体は無検証）"]
    note: "検証が store の外側（1 consumer）にあるため、writer が 3 経路（UI / server 同期 / DataStore 復元）あるうち 1 つしか守られていない。"
  requirement_ids: [R3]
  upstream: [OB-C15, G14, IV6, DP3]
  existing_tests: ["SettingsViewModelTest.kt:397 `許容外の週次目標はAPIへ送らず保持する`", "SettingsViewModelTest.kt:370"]
  blocked_by: []
  test_spec: {id: T-X03, verifies: [CI-X03], given: "両 PreferencesStore 実装", when: "setWeeklyGoalEpisodes(999) を直接呼ぶ", then: "拒否されるか既定 3 へ正規化される", oracle: "weeklyGoalEpisodes StateFlow の値"}

- id: CI-X06
  operation: "PreferencesStore の読み出し（DataStore 復元経路）"
  kind: invariant
  statement: "永続層に定義外の値が入っていても、公開 StateFlow は常に許容集合内の値を出す"
  evidence: {status: confirmed_by_upstream, sources: ["completeness-package.md §11 G14（preferences/DataStorePreferencesStore.kt:41-49 が復元時に検証しない）"]}
  conformance:
    status: unmet
    sources: ["preferences/DataStorePreferencesStore.kt:41-49（upstream 引用。本ロールは当該ファイルを自力で読んでいない）"]
    note: "IV6 / IV7 が一度でも永続化されると、以後の起動すべてに引き継がれる。setter を守るだけでは既に書かれた不正値が回復しない。"
  requirement_ids: [R3]
  upstream: [OB-C15, G14]
  existing_tests: ["DataStorePreferencesStoreTest.kt（5 @Test。既定値と往復は検証するが不正値からの復元は非対象）"]
  blocked_by: []
  test_spec: {id: T-X06, verifies: [CI-X06], given: "DataStore に speed=0.0 / weeklyGoal=999 が書かれた状態", when: "DataStorePreferencesStore を生成して StateFlow を読む", then: "許容集合内の値（既定値）が出る", oracle: "各 StateFlow の初期値"}
  environment_condition: "DataStore の test には既存の DataStorePreferencesStoreTest が使う基盤をそのまま流用できる（JVM unit test で実行可能）"

- id: CI-X08
  operation: "PreferencesStore の消去 API（不在）"
  kind: postcondition
  statement: "主体が離れる遷移で、ユーザー固有 preferences を既定値へ戻す操作が公開される"
  evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:42-60（setter 群のみ。clear / reset 系の宣言が無い）"]}
  conformance:
    status: unmet
    sources: ["preferences/PreferencesStore.kt:42-60"]
    note: "どの key が『ユーザー固有』でどれが『端末設定』かが未決（ARCH-SG4 の evidence_needed）。8 key を一律に扱えないため、消去 API の粒度（全消去 / key 単位）も gate に従属する。"
  requirement_ids: [R6, R9]
  upstream: [OB-C9, G9, DP6]
  existing_tests: []
  blocked_by: [ARCH-SG4]
  test_spec: {id: T-A18, note: "T-A18 と同一シナリオで検証する（verifies: [CI-A18, CI-X08]）。重複 T* を発行しない"}
```

---

## 8. coverage（分母・分子・未 coverage ID）

```yaml
contract_item_inventory:
  denominator_definition: "本 package が発行した contract item CI* の総数。欠番 CI-P23 は発行していないため分母に含めない。"
  total: 97
  by_group: {CI-Q: 16, CI-P: 28, CI-S: 5, CI-A: 21, CI-N: 19, CI-X: 8}
  by_conformance: {met: 63, partial: 7, unmet: 27}

test_coverage:
  numerator_definition: >-
    既存テスト（app/src/test/java/com/rioikeda/newslisten/**）のうち少なくとも 1 件が、
    当該 CI の条件を直接 oracle として検査している CI の数。「関連するテストがある」ではなく
    「その CI が壊れたらそのテストが赤くなる」で判定した。
  denominator: 97
  numerator: 69
  ratio: "69/97 = 71.1%"
  caution: >-
    この比率を「網羅」と呼んではならない。分子 69 のうち 44 件は Fake 経由の ViewModel テストであり、
    production 経路（MockWebServer / 実 ExoPlayer）を通るのは CI-N01/N03/N04/N05/N08a/N09/A13/A14 の
    8 件にすぎない（R7 の未達）。また分母 97 は本 package が発行した item に対する比率であり、
    「公開 operation のすべてに契約が書かれている」ことを意味しない。

uncovered_contract_item_ids:
  count: 28
  ids: [CI-Q01, CI-P02, CI-P03, CI-P06, CI-P07, CI-P09, CI-P13, CI-P27, CI-P28, CI-S03, CI-S04, CI-S05, CI-A03, CI-A07, CI-A10, CI-A11, CI-A12, CI-A17, CI-A18, CI-A20, CI-N02, CI-N06, CI-N17, CI-N18, CI-X01, CI-X02, CI-X06, CI-X08]
  note: "CI-P09 は unmet ではなく partial（契約は doc として存在するが違反検出機構とテストが無い）。他 27 件は unmet。"

test_spec_inventory:
  total: 26
  ids: [T-Q01, T-Q01b, T-Q02, T-P02, T-P03, T-P06, T-P07, T-P09, T-P13, T-P27, T-P28, T-S03, T-S04, T-S05, T-A03, T-A07, T-A08, T-A10, T-A11, T-A12, T-A17, T-A18, T-A20, T-N02, T-N06, T-N07]
  additional: [T-N08b, T-N18, T-X01, T-X02, T-X03, T-X06]
  total_including_additional: 32
  reuse: "CI-N17 は T-S03 を、CI-X08 は T-A18 を再利用する（重複 T* を発行しない）。CI-A08 は T-A08 と T-A10 の 2 件で分担する。"
  note: "CI-Q01b / CI-Q02 / CI-N07 / CI-N08b / CI-X03 は partial のため、T* は「既存テストが触れていない残りの経路」だけを対象にしている。"

existing_test_inventory_used:
  method: "各ファイルで `grep -n '@Test' -A1` を実行しテスト関数名を取得。件数は @Test アノテーションの静的個数（parameterized 展開は数えない）。"
  files:
    - {path: "app/src/test/java/com/rioikeda/newslisten/core/PlaybackQueueConformanceTest.kt", tests: 32, mapped_ci: [CI-Q01b, CI-Q02, CI-Q03, CI-Q04, CI-Q05, CI-Q06, CI-Q07, CI-Q08, CI-Q09, CI-Q10, CI-Q11, CI-Q12, CI-Q13, CI-Q14, CI-Q15]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/podcast/PodcastViewModelTest.kt", tests: 52, mapped_ci: [CI-P01, CI-P04, CI-P05, CI-P10, CI-P11, CI-P12, CI-P14, CI-P15, CI-P16, CI-P17, CI-P18, CI-P19, CI-P20, CI-P21, CI-P22, CI-P24, CI-P25a, CI-P26, CI-P29, CI-N08b]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/auth/AuthViewModelTest.kt", tests: 24, mapped_ci: [CI-A01, CI-A02, CI-A04, CI-A05, CI-A06, CI-A15, CI-A16, CI-A17, CI-A18, CI-A19, CI-A21], conflicting_ci: [CI-A03, CI-A20]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/network/OkHttpApiClientTest.kt", tests: 46, mapped_ci: [CI-N01, CI-N03, CI-N04, CI-N05, CI-N08a, CI-N09, CI-A13, CI-A14], note: "production HTTP 経路（MockWebServer）を通る唯一の主要ファイル。R7 の観点で最も価値が高い"}
    - {path: "app/src/test/java/com/rioikeda/newslisten/network/AudioCacheManagerTest.kt", tests: 14, mapped_ci: [CI-N10, CI-N11, CI-N12, CI-N13, CI-N14, CI-N15, CI-N16], counter_example_missing: [CI-N17]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/core/PlaybackSourceResolverTest.kt", tests: 4, mapped_ci: [CI-S01, CI-S02], note: "真理値表 4 入力を全域被覆。純関数に対する conformance の模範"}
    - {path: "app/src/test/java/com/rioikeda/newslisten/network/AuthInterceptorTest.kt", tests: 6, mapped_ci: [CI-A13, CI-A14]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/network/InMemorySessionStoreTest.kt", tests: 4, mapped_ci: [CI-A08, CI-A09], note: "interface 意味論のみ。KeystoreSessionStore の契約（CI-A10）は未検証"}
    - {path: "app/src/test/java/com/rioikeda/newslisten/preferences/InMemoryPreferencesStoreTest.kt", tests: 6, mapped_ci: [CI-X04, CI-X05]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/preferences/DataStorePreferencesStoreTest.kt", tests: 5, mapped_ci: [CI-X05, CI-X07], counter_example_missing: [CI-X06]}
    - {path: "app/src/test/java/com/rioikeda/newslisten/settings/SettingsViewModelTest.kt", tests: 24, mapped_ci: [CI-X03], note: ":415 は CI-N08b と同型の順序契約の先例"}
    - {path: "app/src/test/java/com/rioikeda/newslisten/network/EncryptedTokenEnvelopeTest.kt", tests: 3, mapped_ci: [], note: "CI-A10 の周辺だが暗号層の失敗経路は非対象"}
    - {path: "app/src/test/java/com/rioikeda/newslisten/core/RelativeTimeConformanceTest.kt", tests: 17, mapped_ci: [], note: "R9 の §3 部分。本ロールの契約対象 operation 外（out_of_contract_scope）"}
  unmapped_test_files_note: >-
    上記以外の test ファイル（model/** の decoding テスト 20 ファイル・feed/passkey/onboarding/account/
    observability/notification/learning/vocabulary/engagement/designsystem の各 ViewModel テスト等）は
    本ロールに割り当てられた契約対象 operation の外側にあるため、CI へ対応付けていない。
    「対応付けなかった」＝「価値が無い」ではない。
```

### 8.1 テストが違反挙動を pin している箇所（契約導入前に反転が必要）

| 既存テスト | 現在 pin している挙動 | 衝突する CI | 必要な手順 |
|---|---|---|---|
| `AuthViewModelTest.kt:95` `トークンありでmeがNetworkErrorでもトークン破棄してUnauthenticatedになる` | 一時障害でトークンを破棄する（R5 違反） | CI-A03 | テストの意図を仕様変更として反転してから RED を作る。テストを残したまま CI-A03 を実装すると必ず赤くなる |
| `AuthViewModelTest.kt:111` / `:125` | server 値を無検証で store へ反映する | CI-A20 | テストが使う値が許容集合内なら維持可。境界値を使っている場合のみ反転 |

---

## 9. obligations（OB-N*: 他 Function・人間へ）

```yaml
obligations:
  - id: OB-N1
    to: human
    via: router
    subject: "共有仕様 §2.1 不変条件 1/2 を構築経路でも守るか（拒否 / 正規化 / 現状維持）"
    rationale: "CI-Q01 / CI-Q01b / CI-Q02 の契約文は『拒否』と『正規化』で then が変わる。Q-04〜Q-07 が setQueue で正規化を選んでいる先例がある一方、Q-18 は末尾で currentIndex を維持することを要求するため、constructor で一律に正規化すると Q-18 と整合しない可能性がある。3 platform（web/iOS/android）で共有する型表現の変更にあたる。"
    blocked_gate: null
    note: "共通ブリーフ §1 により『QueueState の branded type 化』は web レビューで棄却済み。本 obligation は型の置換ではなく init 検証の導入であり別物。"
  - id: OB-N2
    to: human
    via: router
    subject: "ARCH-SG1（現在再生中の authority）・ARCH-SG2（既定速度の適用方針と速度段の正本）・ARCH-SG3（resume 規則）・ARCH-SG4（失効時 cleanup 範囲）・ARCH-SG5（失効と到達不能の分離）・ARCH-SG6（再生状態語彙）・ARCH-SG7（Fake と throwing default）の決定"
    rationale: "以下 13 件の CI は契約文の右辺が gate の決定に従属する。pending のまま実装へ渡すと、gate 確定後に契約とテストを作り直すことになる。"
    dependent_ci: {ARCH-SG1: [CI-P13], ARCH-SG2: [CI-P07, CI-P27, CI-X02], ARCH-SG3: [CI-S04, CI-S05], ARCH-SG4: [CI-A18, CI-X08], ARCH-SG5: [CI-A03, CI-A11, CI-A12], ARCH-SG6: [CI-P02, CI-P03, CI-S03], ARCH-SG7: [CI-N18]}
    note: "本 package は pending gate の default candidate を選択済みとして扱っていない。ARCH-SG8（CI 分割）は契約項目を持たないため本 obligation の対象外。"
  - id: OB-N3
    to: completeness_function
    via: router
    subject: "completeness-package §15 の Selection Gate（COMP-SG1〜COMP-SG4）の内容"
    rationale: "OB-C19（位置同期の送信条件＝一時停止中も PATCH を続けるか）は『COMP-SG1 の決定が前提』と記されているが、本ロールの読み範囲は §10〜§14 に限定されており §15 を読んでいない。CI-P24 は現状の実装挙動（一時停止中も継続。PodcastViewModelTest.kt:293 が pin）を met として記録したが、COMP-SG1 が『再生中のみ』を選んだ場合 CI-P24 は unmet へ反転し、既存テスト :293 が違反挙動を pin している側になる。"
    confirmation_method: "completeness-package.md §15 を読み COMP-SG1 の question と option を確認する"
    impact_if_unresolved: "CI-P24 の met 判定が覆る可能性がある。coverage の met 63 が 62 に、numerator 69 が 68 になる"
  - id: OB-N4
    to: human
    via: router
    subject: "downloadAudio の本文妥当性検査の水準（Content-Type のみ / Content-Length 一致 / magic bytes）"
    rationale: "CI-N06 の then が水準によって変わる。厳しすぎると正常な配信が拒否され、緩いと IV9 が残る。"
  - id: OB-N5
    to: human
    via: router
    subject: "markCompleted の冪等性が server 契約として保証されているか"
    rationale: "CI-N07 は client 側で抑制すべきか送り続けるべきかが server の保証に依存する。client 側で抑制すると『別セッションでの再完聴が記録されない』回帰になりうる。"
    confirmation_method: "backend の /podcasts/{id}/complete の実装または API 仕様を確認する（本 review の参照範囲外）"
  - id: OB-N6
    to: human
    via: router
    subject: "JVM unit test から到達できない契約（CI-P03 の ExoPlayer 配線、CI-A08/CI-A10 の Android Keystore）の検証基盤"
    rationale: "androidTest ディレクトリが存在しないため（ブリーフ §5、confirmed_by_router）、instrumented test の受け皿が無い。選択肢は (a) androidTest を新設、(b) 暗号操作と Player.Listener 配線を port として切り出して JVM test 可能にする、(c) 当該契約を未検証として ADR に記録。"
    dependent_ci: [CI-P03, CI-A08, CI-A10]
  - id: OB-N7
    to: architecture_function
    via: router
    subject: "network/ApiClient.kt の throwing default の実在行の独立確認"
    rationale: "CI-N18 の conformance sources（`network/ApiClient.kt:67`）は architecture package §6 SG7 の引用であり、本ロールは自力で grep していない。共通ブリーフ §0-2 の path:line 規律に従い、引用元の確認責任を明示して返す。"
    confirmation_method: "`grep -Hn 'TODO\\|error(\\|throw ' app/src/main/java/com/rioikeda/newslisten/network/ApiClient.kt`"
  - id: OB-N8
    to: router
    subject: "本ロールの契約対象 operation 外にある upstream obligation の引き受け先"
    rationale: "OB-C12（consumer が HTTP status 数値を参照しない）・OB-C14（quota 期間の文言）・OB-C16（LearningUiState の排他化）は、それぞれ settings/engagement/QuizSheet、feed/FeedViewModel、learning/LearningViewModel を主体とする。本 package の subject（CI-Q/P/S/A/N/X の対象ファイル群）に含まれないため CI を発行していない。捏造せず未引き受けとして返す。"
    unaddressed_upstream: [OB-C12, OB-C14, OB-C16, OB-C19]
    note: "OB-C19 は OB-N3 と重複する（COMP-SG1 待ち）。OB-C1〜OB-C11, OB-C13, OB-C15, OB-C17, OB-C18, OB-C20 は本 package で CI 化済み。"
```

### 9.1 upstream obligation の消化状況

| upstream | 発行した CI | 状態 |
|---|---|---|
| OB-C1 | CI-P02 | 契約化済み・unmet・ARCH-SG6 待ち |
| OB-C2 | CI-P03 | 契約化済み・unmet・ARCH-SG6 待ち・検証基盤なし（OB-N6） |
| OB-C3 | CI-S04, CI-S05 | 契約化済み・unmet・ARCH-SG3 待ち |
| OB-C4 | CI-P27 | 契約化済み・unmet・ARCH-SG2 待ち |
| OB-C5 | CI-P13 | 契約化済み・unmet・ARCH-SG1 待ち |
| OB-C6 | CI-P06, CI-P07 | 契約化済み・unmet（P07 は ARCH-SG2 待ち） |
| OB-C7 | CI-A03 | 契約化済み・unmet・既存テスト衝突あり |
| OB-C8 | CI-A12 | 契約化済み・unmet・ARCH-SG5 待ち |
| OB-C9 | CI-A18, CI-X08 | 契約化済み・unmet・ARCH-SG4 待ち・spec に Android 行が無い |
| OB-C10 | CI-A17 | 契約化済み・unmet |
| OB-C11 | CI-N02 | 契約化済み・unmet |
| OB-C12 | — | **未引き受け**（OB-N8。対象が本 subject 外） |
| OB-C13 | CI-P28 | 契約化済み・unmet（feed 側は subject 外） |
| OB-C14 | — | **未引き受け**（OB-N8） |
| OB-C15 | CI-X01, CI-X02, CI-X03, CI-X06, CI-A20 | 契約化済み・unmet 4 / partial 1 |
| OB-C16 | — | **未引き受け**（OB-N8） |
| OB-C17 | CI-Q01, CI-Q01b, CI-Q02 | 契約化済み・unmet 1 / partial 2 |
| OB-C18 | CI-S03, CI-N06, CI-N17 | 契約化済み・unmet |
| OB-C19 | — | **unknown**（COMP-SG1 未読。OB-N3） |
| OB-C20 | CI-A07, CI-A08, CI-A10, CI-A11 | 契約化済み・unmet 3 / partial 1 |

---

## 10. AI 復唱（proposed_status）

```yaml
ai_restatement:
  proposed_status: matched
  restated_intent: >-
    「android の公開 operation（PlaybackQueue / PlaybackSourceResolver+resume / PlayerController+PodcastViewModel /
    Auth 系 / Network 系 / PreferencesStore）について、事前条件・事後条件・不変条件・失敗保証・冪等性・
    環境条件を contract item として明示し、各項目に Given-When-Then の最小失敗テスト仕様を与え、
    既存テストとの対応と coverage を分母・分子・未 coverage ID で示す」と理解した。
    共有仕様 §2 の Q-01〜Q-32 conformance を中核に置き、仕様 §2.1 の不変条件 1〜5 を CI 化した。
  reviewed_by: unresolved
  note: >-
    proposed_status: matched は自己申告であり承認の証拠ではない。独立評価（adversarial-verifier 等）
    または人間の承認は router が取る。とくに以下は AI が確定してはならない:
    (a) ARCH-SG1〜SG7 の選択（公開契約・data の意味・不可逆 trade-off を含む）、
    (b) OB-N1（3 platform 共有仕様の不変条件の守り方）、
    (c) OB-N4（配信本文の検査水準）、(d) OB-N5（server 契約への依存）。
```

---

## 11. 検討して採らなかった選択肢（trial-log 転記用）

`docs/trial-log/` の既存 2 件（`featured-categories.md` / `launcher-icon-rebrand.md`）を読了した。いずれも本 design 対象（再生・認証・network・preferences の契約）と scope が重ならないため、再提案にあたる項目は無い。`featured-categories.md` の棄却記録（`@StringRes` ラベルの unit test は Context 依存のため削除、UI テストで担保）は本 package の方針と整合する（CI-P28 の oracle を「文字列リソース ID ではなく errorMessage の文字列」に置いた理由の一つ）。

| 案 | 目的 | 前提 | やったこと | 結果（棄却理由） | 残課題 |
|---|---|---|---|---|---|
| CI をファイル単位ではなく operation 単位で 1:1 に発行する | 契約と公開 API の対応を機械的にする | 未検証: 公開 operation は PodcastViewModel 15 + PlayerController 8 + ApiClient 44 等で 80 超あり、1:1 では item が 150 件を超える | operation 一覧を `grep -Hn` で列挙して概算した | 棄却。契約の粒度が「1 操作 1 条件」に固定され、`CI-P12`（完聴時の 3 操作の順序）や `CI-P13`（複数操作をまたぐ不変条件）のような横断契約を表現できない | operation 一覧と CI の全射性（契約の無い公開 operation の洗い出し）は未実施 |
| Q-01〜Q-32 を 32 個の CI にそのまま写す | conformance の trace を 1:1 にする | 前提: 既存 `PlaybackQueueConformanceTest` が行 ID をテスト名に含む（spec:274 の規約）ため、CI を介さず行 ID で直接 trace できる | 行 ID と操作の対応を仕様 §2.2〜§2.10 で確認した | 棄却。CI が既存テスト名の写しになり情報が増えない。代わりに操作単位で CI を束ね（CI-Q06〜CI-Q13）、§1.1 で行 ID → CI の対応表を持つ構成にした | 行 ID を持たない §2.1 不変条件を CI-Q01/Q01b/Q02 として別立てしたため、「Q 表が全緑」と「§2 準拠」が別物であることの明示が §1.1 の注記 1 行に依存している |
| unmet CI にも「現状の暗黙契約」を met として記録する | 実装の現状を契約として固定し回帰を防ぐ | 前提: ARCH-SG* が pending であり、現状挙動が正しいとは決まっていない | CI-P24（一時停止中も位置同期）だけはこの方針で met とした | 部分採用。CI-P24 以外（CI-A03 の catch-all 等）は R5 に直接違反するため met にできない。CI-P24 も COMP-SG1 次第で反転するため OB-N3 を付けた | 「現状を pin する」ことと「仕様違反を正当化する」ことの境界が CI-P24 の 1 件でしか検討されていない |
| `T*` を unmet CI だけに発行する | 成果物を短くする | 前提: met CI は既存テストが oracle を持つ | met CI には T* を発行せず既存テスト名で trace した | 採用。ただし partial 5 件（CI-Q01b/Q02/N07/N08b/X03）には「既存テストが触れていない残りの経路」に限定した T* を発行した | partial の T* が既存テストと重複していないことの機械的確認は未実施 |
| `network/ApiClient.kt` の throwing default を自力で grep して行番号を確定する | path:line 規律を自力の Evidence で満たす | 前提: turn 予算（20）のうち読みに使えるのは残り僅か | upstream 引用（`:67`）を `confirmed_by_upstream` として明示し、独立確認を OB-N7 として返した | 部分採用（時間制約による意図的な不完全）。引用の出自を隠して confirmed と書くことはしなかった | OB-N7 が解決されるまで CI-N18 の conformance sources は独立確認済みでない |

---

## 12. path:line 自己検査

提出前に、本 package が引用したすべての `file:N` が当該ファイルの `wc -l` 以下であることを機械検査した。結果は §12.1 に記す。
