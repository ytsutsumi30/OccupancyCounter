---
name: release
description: OccupancyCounter の APK リリース手順。リリース APK のビルド、パッケージング（sha256・リリースノート生成）、GitHub Release 作成を頼まれたときに使う。
---

# APK リリース手順

前提: Windows（PowerShell）で実行。署名情報は `local.properties` の `KEYSTORE_*`。詳細は `docs/RELEASE.md`。

1. バージョン確認: `app/build.gradle.kts` の `versionCode` / `versionName` を必要に応じて上げる
2. ビルド＋パッケージング:
   - リリース APK をビルドしてまとめる: `.\scripts\package-release.ps1 -Build`
   - 既存 APK を使う: `.\scripts\package-release.ps1 -ApkPath <apkのパス>`
3. 出力先: `artifacts/android/v<version>/`（APK + sha256 + release-notes-v<version>.md）
4. GitHub Release まで作成する場合は `-GitHubRelease` を追加（要 GitHub CLI ログイン済み）。タグ名は `android-v<version>`。同名タグが既にあれば GitHub 側で削除するか `versionName` を上げて再実行
5. 動作確認: `adb install -r <apk>` → 初回起動後、設定画面で endpoint URL / API キー / device_id・会議室マッピング / 議事録録音 endpoint を確認

注意:

- `artifacts/`, `*.apk`, `*.aab`, `release.jks` はコミット禁止（.gitignore 済み）
- WSL からは `powershell.exe -File scripts/package-release.ps1 ...` で実行できるが、パス変換に注意。基本は Windows 側での実行を推奨
