$ErrorActionPreference = "Stop"

$PackageName = "com.antigravity.netflixadshield"
$ApkPath = "app/build/outputs/apk/debug/app-debug.apk"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Netflix Ad Shield - PayPay両立モード 初回セットアップ" -ForegroundColor Cyan
Write-Host " Target Package: $PackageName" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

Write-Host "`n[1/4] Debug APK をビルド中 (./gradlew.bat assembleDebug)..." -ForegroundColor Yellow
& ./gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle ビルドに失敗しました (exit code: $LASTEXITCODE)"
}

if (-not (Test-Path $ApkPath)) {
    throw "APK が見つかりません: $ApkPath"
}

Write-Host "`n[2/4] 端末へ APK をインストール中 (adb install -r)..." -ForegroundColor Yellow
& adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install に失敗しました。USBデバッグ接続と端末承認を確認してください (exit code: $LASTEXITCODE)"
}

Write-Host "`n[3/4] WRITE_SECURE_SETTINGS 権限を付与中..." -ForegroundColor Yellow
& adb shell pm grant $PackageName android.permission.WRITE_SECURE_SETTINGS
if ($LASTEXITCODE -ne 0) {
    throw "WRITE_SECURE_SETTINGS の付与に失敗しました (exit code: $LASTEXITCODE)"
}

Write-Host "`n[4/4] 使用状況へのアクセス (GET_USAGE_STATS) を許可中..." -ForegroundColor Yellow
& adb shell appops set $PackageName GET_USAGE_STATS allow
if ($LASTEXITCODE -ne 0) {
    throw "GET_USAGE_STATS の設定に失敗しました (exit code: $LASTEXITCODE)"
}

Write-Host "`n[確認] 権限付与ステータスをチェック中..." -ForegroundColor Cyan
$writeSecureCheck = & adb shell dumpsys package $PackageName | Select-String "android.permission.WRITE_SECURE_SETTINGS"
$usageStatsCheck = & adb shell appops get $PackageName GET_USAGE_STATS

Write-Host "  WRITE_SECURE_SETTINGS : $writeSecureCheck" -ForegroundColor Green
Write-Host "  GET_USAGE_STATS       : $usageStatsCheck" -ForegroundColor Green

Write-Host "`n============================================================" -ForegroundColor Green
Write-Host " セットアップ完了！必要な権限がすべて付与されました。" -ForegroundColor Green
Write-Host " スマホで「Netflix Ad Shield」を開き、" -ForegroundColor Green
Write-Host " 「自動切替（PayPay両立モード）を開始」をタップしてください。" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
