$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $projectRoot 'dist\手机活动记录-v0.4-debug.apk'
$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    'C:\Users\zerui\AppData\Local\Android\Sdk'
}
$adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'

if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "找不到 ADB：$adbPath"
}
if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "找不到 APK：$apkPath。请先构建项目。"
}

& $adbPath start-server | Out-Null
$deviceLines = @(& $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
if ($deviceLines.Count -eq 0) {
    & $adbPath devices -l
    throw '没有已授权的 Android 设备。请连接手机、开启 USB 调试，并在手机上确认调试授权。'
}
if ($deviceLines.Count -gt 1) {
    & $adbPath devices -l
    throw '检测到多台设备。请暂时只保留 Redmi K80 Pro，或手动使用 adb -s <序列号> install。'
}

& $adbPath install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "ADB 安装失败，退出码 $LASTEXITCODE"
}

Write-Host '安装完成。请打开“手机活动记录”，配置 HTTPS 服务器和上传密钥，授权使用情况访问并启用采集。'
