# Media

Files the launcher loads from the stick at runtime (not packaged in the APK).
Everything here is copied **flat** into `files/media` inside the app.

- `audio/`  – menu sounds (see `audio/README.md`)
- `video/`  – `intro.mp4` (start-up video) and `bg.mp4` (looping 60fps ribbon background)
- `images/` – `logo.png`

Push everything to the stick (PowerShell, from the repo root):

    .\push-media.ps1 -StickIp 192.168.1.50

Back up what is on the stick into this folder:

    .\backup-media.ps1 -StickIp 192.168.1.50
