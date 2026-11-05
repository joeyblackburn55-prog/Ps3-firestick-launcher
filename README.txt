XMB LAUNCHER - V1.1  (based on V1.0, adds Home button takeover)
PS3-style launcher for Fire TV Stick 4K Plus (Fire OS)

WHAT IS IN V1
- 9 categories: Settings, Photo, Music, TV, Streaming, Games, NHL, Network, All Apps
- PS3 menu layout (proportions measured from the real XMB), drawn icons, animated gold ribbon background
- Intro video that fades into the menu
- NHL category: Maple Leafs / your team, Live Game (shots, goals, channels), Today, Yesterday, Division,
  Wild Card, League Standings, Leaders - with team logos and TV channels, auto-refresh
- PS3-style time bar (top right) and weather bar (top left, Open-Meteo)
- XMB Settings: language EN/FR, clock 12/24h, date format, seconds, C/F, feels like, weather city,
  favorite NHL team, menu sound volume, idle mode

BUILD
1. Android Studio > File > Open > the folder that contains settings.gradle.kts
2. Gradle JDK must be 17 or 21 (not 25)
3. Build > Generate App Bundles or APKs > Generate APKs
   APK: app/build/outputs/apk/debug/app-debug.apk

CONNECT AND INSTALL (PowerShell in ...\Android\Sdk\platform-tools)
  ./adb connect STICK_IP:5555
  ./adb devices
  ./adb install -r "PATH\TO\app-debug.apk"
If you get INSTALL_FAILED_UPDATE_INCOMPATIBLE the app was signed on another computer:
  back up the media files first (see below), then ./adb uninstall com.xmb.launcher and install again.

FILES THAT LIVE ON THE STICK (kept in the repo under media/, see media/README.md)  ->  files/media inside the app
  intro.mp4   intro video (plays at start, fades into the menu)
  bg.mp4      looping 60fps ribbon background (replaces the live-drawn one, much smoother)
  move_cat.mp3  move_item.mp3  select.mp3  startup.mp3  logo.png   (optional sounds/logo)

PUSH ALL MEDIA AT ONCE:  .\push-media.ps1 -StickIp STICK_IP
BACK UP ALL MEDIA:       .\backup-media.ps1 -StickIp STICK_IP

PUSH A FILE BY HAND (repeat per file, change the name)
  ./adb shell run-as com.xmb.launcher mkdir -p files/media
  ./adb push "C:\PATH\intro.mp4" /data/local/tmp/
  ./adb shell chmod 644 /data/local/tmp/intro.mp4
  ./adb shell run-as com.xmb.launcher cp /data/local/tmp/intro.mp4 files/media/intro.mp4
CHECK:  ./adb shell run-as com.xmb.launcher ls -l files/media

BACK UP THE FILES FROM THE STICK TO THE PC
  ./adb shell run-as com.xmb.launcher cp files/media/intro.mp4 /data/local/tmp/intro.mp4
  ./adb shell chmod 644 /data/local/tmp/intro.mp4
  ./adb pull /data/local/tmp/intro.mp4 "C:\PATH\backup\intro.mp4"
  (same for bg.mp4 and the sound files)

KEEP THIS TOO
- Debug signing key: C:\Users\<you>\.android\debug.keystore  (copy it to a safe place and to any new
  computer, then installs will update without uninstalling)
- Your intro.mp4 and bg.mp4

FIRE TV TWEAKS
  ./adb shell settings put system screen_off_timeout 2147460000   (screen saver: never)
  ./adb shell settings put secure sleep_timeout 0                  (do not sleep)
  Not done yet: making XMB the default Home screen (Amazon's launcher is still the default).

KNOWN LIMITS OF V1
- NHL and weather data come from unofficial/free services and could change.
- Package names for some apps (Crave, IPTV Smarters, App Store...) are best guesses; every app is also under All Apps.

V1.1 ADDITIONS
- HomeKeyService (accessibility service): the Home button opens XMB instead of Amazon's launcher
- BootReceiver: XMB opens after the stick boots (open XMB once manually first)
- Settings category: "Amazon Home" entry opens Amazon's home screen (escape hatch)

ENABLE THE HOME BUTTON TAKEOVER (after installing V1.1 and opening XMB once)
  ./adb shell settings put secure enabled_accessibility_services com.xmb.launcher/com.xmb.launcher.HomeKeyService
  ./adb shell settings put secure accessibility_enabled 1
  ./adb shell appops set com.xmb.launcher SYSTEM_ALERT_WINDOW allow
UNDO (back to Amazon's Home button)
  ./adb shell settings put secure enabled_accessibility_services ""
  (or ./adb shell settings delete secure enabled_accessibility_services)
