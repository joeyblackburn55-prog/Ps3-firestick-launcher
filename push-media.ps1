# Copies every file under .\media (audio, video, images) to files/media on the Fire TV stick.
# Usage:  .\push-media.ps1 -StickIp 192.168.1.50 [-Adb "C:\...\platform-tools\adb.exe"]
param(
    [Parameter(Mandatory = $true)][string]$StickIp,
    [string]$Adb = "adb"
)
$ErrorActionPreference = "Stop"
$pkg = "com.xmb.launcher"
& $Adb connect "${StickIp}:5555" | Out-Host
& $Adb shell run-as $pkg mkdir -p files/media
$files = Get-ChildItem -Path (Join-Path $PSScriptRoot "media") -Recurse -File |
    Where-Object { $_.Name -ne ".gitkeep" -and $_.Extension -ne ".md" }
foreach ($f in $files) {
    Write-Host "-> $($f.Name)"
    & $Adb push $f.FullName "/data/local/tmp/$($f.Name)" | Out-Null
    & $Adb shell chmod 644 "/data/local/tmp/$($f.Name)"
    & $Adb shell run-as $pkg cp "/data/local/tmp/$($f.Name)" "files/media/$($f.Name)"
    & $Adb shell rm "/data/local/tmp/$($f.Name)"
}
Write-Host "`nOn the stick now:"
& $Adb shell run-as $pkg ls -l files/media
Write-Host "Restart XMB to load the new files."
