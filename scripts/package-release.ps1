param(
    [string]$Version = "",
    [string]$ApkPath = "",
    [string]$OutputDir = "",
    [switch]$Build,
    [switch]$GitHubRelease
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot
try {
    if (-not $Version) {
        $gradleFile = Join-Path $repoRoot "app\build.gradle.kts"
        $gradleText = Get-Content -LiteralPath $gradleFile -Raw
        $match = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
        if (-not $match.Success) { throw "versionName not found in app\build.gradle.kts" }
        $Version = $match.Groups[1].Value
    }

    if (-not $OutputDir) {
        $OutputDir = Join-Path $repoRoot "artifacts\android\v$Version"
    }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

    if ($Build) {
        Write-Host "Building release APK..." -ForegroundColor Cyan
        & .\gradlew.bat assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "Gradle assembleRelease failed with exit code $LASTEXITCODE" }
    }

    if (-not $ApkPath) {
        $candidates = @(
            (Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk")
        )
        $rootApks = Get-ChildItem -LiteralPath $repoRoot -Filter "*.apk" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -ExpandProperty FullName
        $candidates += $rootApks
        $ApkPath = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    }

    if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
        throw "APK not found. Run with -Build or pass -ApkPath <path-to-apk>."
    }

    $date = Get-Date -Format "yyyyMMdd-HHmmss"
    $artifactBase = "OccupancyCounter-v$Version-$date"
    $destApk = Join-Path $OutputDir "$artifactBase.apk"
    Copy-Item -LiteralPath $ApkPath -Destination $destApk -Force

    $hash = Get-FileHash -LiteralPath $destApk -Algorithm SHA256
    $shaFile = Join-Path $OutputDir "$artifactBase.sha256"
    "$($hash.Hash)  $(Split-Path $destApk -Leaf)" | Set-Content -LiteralPath $shaFile -Encoding UTF8

    $notesFile = Join-Path $OutputDir "release-notes-v$Version.md"
    @"
# OccupancyCounter v$Version

## Artifact

- APK: $(Split-Path $destApk -Leaf)
- SHA256: $($hash.Hash)

## Install

```powershell
adb install -r "$destApk"
```

## Runtime setup

1. Open Android app settings.
2. Set the TestDashboard endpoint URL.
3. If TestDashboard has `TESTDASHBOARD_API_KEY`, set the same value in "Server API key".
4. Enable server sending only after confirming the endpoint and API key.

## Notes

- APK/AAB files are intentionally excluded from Git.
- Keep signing keys and `local.properties` out of Git.
"@ | Set-Content -LiteralPath $notesFile -Encoding UTF8

    Write-Host ""
    Write-Host "Release artifact packaged." -ForegroundColor Green
    Write-Host "APK:    $destApk"
    Write-Host "SHA256: $($hash.Hash)"
    Write-Host "Notes:  $notesFile"

    if ($GitHubRelease) {
        $tag = "android-v$Version"
        $gh = Get-Command gh -ErrorAction SilentlyContinue
        if (-not $gh) { throw "gh command not found. Install GitHub CLI or rerun without -GitHubRelease." }
        Write-Host "Creating GitHub release $tag..." -ForegroundColor Cyan
        & gh release create $tag $destApk $shaFile --title "OccupancyCounter v$Version" --notes-file $notesFile
        if ($LASTEXITCODE -ne 0) { throw "gh release create failed with exit code $LASTEXITCODE" }
    }
}
finally {
    Pop-Location
}
