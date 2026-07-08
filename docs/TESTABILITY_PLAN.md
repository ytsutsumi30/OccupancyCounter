# テスト可能化 設計プラン

現状のユニットテストは `AppPrefsTest` 1本。核心ロジック（人数スムージング・送信判定・アップロード再送・ジョブ永続化）は Activity と Android API に密結合でテスト不能。本プランは**依存を切り離す継ぎ目（seam）の設計**を固定化するもので、テストの量産自体は tester エージェント（Sonnet）に委譲できる状態を作る。

## 設計原則

- **DI フレームワークは入れない**（この規模では過剰）。コンストラクタ引数のデフォルト値で注入する
- ロジックは「純 Kotlin クラス」へ抽出し、Android 依存（Context / View / Camera / MediaRecorder）は薄い皮に追い出す
- ハードウェア境界（CameraX、MediaRecorder、ML Kit 本体）はユニットテスト対象外と割り切る

## 継ぎ目の設計（4つ）

### S-1: `HeadcountSmoother`（新規・純 Kotlin）

`MainActivity.onCountUpdated` 前半（`recentCounts` バッファ・最頻値・confidence 判定）を抽出。

```kotlin
class HeadcountSmoother(private val window: Int = 5) {
    data class Output(val stable: Int, val confidence: Confidence)
    fun add(raw: Int): Output   // バッファ更新→最頻値と confidence を返す
}
```

- MainActivity は `smoother.add(rawCount)` を呼ぶだけになる
- `ServerClient.Confidence` は smoothing の概念なので `HeadcountSmoother.Confidence` へ移す

### S-2: `SendPolicy`（新規・純 Kotlin）

`onCountUpdated` 後半の「変化時のみ＋最低送信間隔」判定と、送信失敗時の巻き戻し（BACKLOG B-02 の修正）を抽出。

```kotlin
class SendPolicy(private val intervalMs: () -> Long, private val clock: () -> Long = System::currentTimeMillis) {
    fun shouldSend(stable: Int): Boolean          // 判定のみ（状態は変えない）
    fun recordAttempt(stable: Int)                // 送信直前に呼ぶ
    fun recordFailure()                           // 失敗時に呼ぶ → 次フレームで再送可能に
}
```

- `clock` 注入により時間依存テストが決定的になる

### S-3: `ServerClient` / `RecordingUploader` — OkHttpClient と設定値の注入

Context→AppPrefs 直結をやめ、必要な値だけ受け取る。

```kotlin
class ServerClient(
    private val client: OkHttpClient = defaultClient,
    private val apiKeyProvider: () -> String
)
class RecordingUploader(
    private val client: OkHttpClient = defaultClient,   // ServerClient と共有（B-13 解消）
    private val apiKeyProvider: () -> String
)
```

- テストは **OkHttp MockWebServer** で実 HTTP スタックごと検証（`testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` を追加）
- 検証対象: JSON ボディの形、`X-API-Key` ヘッダの有無、multipart の `meta`/`audio` パート構造、HTTP エラー→Result 変換

### S-4: `JobStore` — Context ではなく baseDir を受け取る

```kotlin
class JobStore(private val jobsDir: File)   // 呼び出し側: JobStore(File(context.filesDir, "storage/jobs"))
```

- JVM ユニットテストで一時ディレクトリ（`@Rule TemporaryFolder`）を渡すだけで全 API がテスト可能になる
- 注意: Android の `org.json` は Gradle ユニットテストではスタブのため、`testImplementation("org.json:json:20240303")` を追加して実挙動（**JSONObject.NULL→optString が "null" を返す件 = BACKLOG B-04**）を再現すること

## 最初に書くテスト（優先順）

| ID | 対象 | 検証内容 |
|---|---|---|
| T-01 | `HeadcountSmoother` | ウォームアップ中は tentative／窓が満杯かつ全一致で confirmed／最頻値タイの挙動／窓あふれの追い出し |
| T-02 | `JobStore` | 全フィールドのラウンドトリップ（**null フィールドが "null" 文字列にならないこと** = B-04 回帰）、破損ファイルのスキップ、状態遷移（RECORDING→PENDING→UPLOADING→UPLOADED/FAILED）、`loadAll` の降順ソート |
| T-03 | `ServerClient` + MockWebServer | 送信 JSON の形・API キーヘッダ・blank endpoint 拒否・HTTP 500→失敗コールバック |
| T-04 | `SendPolicy` | 変化なし→送らない／間隔未満→送らない→間隔経過で送る／失敗後に同値でも再送する（B-02 回帰） |
| T-05 | `RecordingUploader` + MockWebServer | multipart 2 パートの構造・meta JSON・`job_id` パース・エラー変換 |

## 実施ステップ

1. **リファクタ（挙動不変）**: S-1〜S-4 の抽出のみ。既存テスト＋手動確認で挙動維持を確認 → 1 コミット
2. **テスト基盤**: mockwebserver / org.json を testImplementation に追加、T-01〜T-03 を実装 → CI がカバレッジの安全網になる
3. **バグ修正と回帰テストを対で**: B-04 → T-02 強化、B-02 → T-04、B-01 → T-01 に「欠測フレーム」ケース追加
4. T-04/T-05 と残テストの量産（tester エージェント / Sonnet に委譲可）

## 検証方法

- 各ステップで `./gradlew test`（ローカル）＋ push で CI（test + assembleDebug）
- ステップ1完了時は実機で「起動→検出→送信成功表示」「録音→停止→アップロード成功」の2フローを目視確認（`docs/RELEASE.md` の確認項目に準ずる）
