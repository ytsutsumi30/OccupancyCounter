# APK Release Artifact 運用

APK / AAB はサイズが大きく、署名情報や配布履歴を含むため Git にはコミットしません。  
このリポジトリでは `*.apk`, `*.aab`, `artifacts/` を `.gitignore` で除外し、配布物は手元成果物または GitHub Releases に置きます。

## 1. 手元成果物としてまとめる

既存APKを使う場合:

```powershell
cd C:\PRJ2\dev2\OccupancyCounter
.\scripts\package-release.ps1 -ApkPath .\app-release-v1.0-20260514.apk
```

release APKをビルドしてからまとめる場合:

```powershell
cd C:\PRJ2\dev2\OccupancyCounter
.\scripts\package-release.ps1 -Build
```

出力先:

```text
artifacts/android/v<version>/
├── OccupancyCounter-v<version>-<timestamp>.apk
├── OccupancyCounter-v<version>-<timestamp>.sha256
└── release-notes-v<version>.md
```

`artifacts/` は Git 除外です。社内配布、Teams添付、ファイルサーバー配置などはこのフォルダの中身を使います。

## 2. GitHub Releases に登録する

GitHub CLI にログイン済みの場合だけ、次でRelease作成まで行えます。

```powershell
cd C:\PRJ2\dev2\OccupancyCounter
.\scripts\package-release.ps1 -Build -GitHubRelease
```

既存APKをReleaseへ上げる場合:

```powershell
.\scripts\package-release.ps1 -ApkPath .\app-release-v1.0-20260514.apk -GitHubRelease
```

作成されるタグ名は `android-v<version>` です。既に同名タグ/Releaseがある場合は、GitHub側で削除するか、`app/build.gradle.kts` の `versionName` を上げてから再実行してください。

## 3. インストール確認

```powershell
adb install -r .\artifacts\android\v1.0\OccupancyCounter-v1.0-<timestamp>.apk
```

初回起動後に設定画面で以下を確認します。

- TestDashboard endpoint URL
- Server API key (`TESTDASHBOARD_API_KEY` を有効化している場合)
- device_id と会議室マッピング
- 議事録録音 endpoint

## 4. Gitに入れないもの

- `*.apk`
- `*.aab`
- `artifacts/`
- `release.jks`
- `local.properties`

署名キー、APIキー、APK本体はGitではなく、配布先・管理台帳・GitHub Releasesで管理します。
