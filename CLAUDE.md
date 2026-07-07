# CLAUDE.md

会議室の滞在人数をカウントする Android アプリ（Kotlin）。余った Android 端末を IoT デバイス化し、CameraX + ML Kit Face Detection（オフライン）で「映っている顔の数＝現在の滞在人数」を計測、OkHttp で会議室予約システムのエンドポイントへ JSON POST する。`meeting/` パッケージに会議録音・議事録生成連携もある。

## ビルド・テスト

- 要 JDK 17 以上（AGP 9.2.0 / Gradle 9.4.1 / Kotlin 2.2.10）
- ユニットテスト: `./gradlew test`
- デバッグ APK: `./gradlew assembleDebug`（要 Android SDK: `ANDROID_HOME` または `local.properties` の `sdk.dir`）
- リリース署名は `local.properties` の `KEYSTORE_*` を参照（未設定でも `test` / `assembleDebug` は通る）

## 構成

- `app/src/main/java/com/example/occupancycounter/`
  - `MainActivity.kt` — カメラプレビュー・人数表示・スムージング
  - `FaceAnalyzer.kt` — ML Kit Face Detection ラッパー
  - `ServerClient.kt` — OkHttp による JSON POST
  - `SettingsActivity.kt` / `AppPrefs.kt` — 設定画面（PreferenceFragment）・SharedPreferences ラッパー
  - `meeting/` — 会議録音・議事録連携（MeetingActivity / MeetingRecorder / RecordingUploader / JobStore）
- `app/src/test/` — JUnit4 ユニットテスト
- `docs/` — GitHub Pages ダッシュボード（index.html / dashboard.js / config.js / style.css）と RELEASE.md
- `scripts/package-release.ps1` — リリース APK のパッケージング（Windows PowerShell）

## 規約

- コミットは conventional commits（feat / fix / docs / chore / refactor …）。件名は英語、本文は日本語可
- 文言の追加・変更時は `values/strings.xml`（英語）と `values-ja/strings.xml`（日本語）を必ず両方更新する
- minSdk 24 / targetSdk 36 を維持する
- コミット禁止: `release.jks`・`local.properties`・API キー・`*.apk` / `*.aab` / `artifacts/`（いずれも .gitignore 済み）

## リリース

手順の詳細は `docs/RELEASE.md` を参照（`.claude/skills/release/` に要約あり）。
