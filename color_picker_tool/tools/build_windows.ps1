$ErrorActionPreference = "Stop"

$now = Get-Date
$buildDate = $now.ToString("yyyy-MM-dd HH:mm:ss")

Write-Host ("BUILD_DATE = " + $buildDate)

Set-Location $PSScriptRoot
Set-Location ".."

flutter pub get
flutter build windows --release --dart-define=BUILD_DATE=$buildDate

