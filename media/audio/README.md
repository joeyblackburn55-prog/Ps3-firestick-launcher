# Audio

Menu sounds for the XMB. On the stick these all go into the app's `files/media` folder
(`push-media.ps1` copies them there for you).

The app finds a sound by its name **before the dot**, so `.mp3`, `.ogg` and `.wav` all work.

| File | Plays when | Required? |
|------|-----------|-----------|
| `move.mp3` | fallback for any cursor move | optional |
| `move_cat.mp3` | moving left/right between categories | optional (falls back to `move`) |
| `move_item.mp3` | moving up/down between items | optional (falls back to `move`) |
| `select.mp3` | selecting an item | optional |
| `startup.mp3` | the launcher starts | optional |
| `trophy.mp3` | NHL goal alert | optional |

Volume is set in XMB Settings → menu sound volume.
Only commit audio you made yourself or have the rights to.
