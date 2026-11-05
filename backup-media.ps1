# Copies files/media from the Fire TV stick back into .\media\audio, \video and \images.
# Usage:  .\backup-media.ps1 -StickIp 192.168.1.50 [-Adb "C:\...\platform-tools\adb.exe"]
param(
    [Parameter(Mandatory = $true)][string]$StickIp,
    [string]$Adb = "adb"
)
$ErrorActionPreference = "Stop"
$pkg = "com.xmb.launcher"
& $Adb connect "${StickIp}:5555" | Out-Host
$names = (& $Adb shell run-as $pkg ls files/media) -split "\s+" | Where-Object { $_ }
foreach ($n in $names) {
    $ext = [IO.Path]::GetExtension($n).ToLower()
    $sub = switch ($ext) {
        { $_ -in ".mp3", ".ogg", ".wav", ".m4a" } { "audio" }
        { $_ -in ".mp4", ".mkv", ".webm" }        { "video" }
        default                                    { "images" }
    }
    $dest = Join-Path $PSScriptRoot "media\$sub"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Write-Host "<- $n  ($sub)"
    & $Adb shell run-as $pkg cp "files/media/$n" "/data/local/tmp/$n"
    & $Adb shell chmod 644 "/data/local/tmp/$n"
    & $Adb pull "/data/local/tmp/$n" (Join-Path $dest $n) | Out-Null
    & $Adb shell rm "/data/local/tmp/$n"
}
