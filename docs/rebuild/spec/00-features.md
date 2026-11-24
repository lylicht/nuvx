# Spec 00: feature inventory

Every feature the rebuilt nuvx must have, from two references (owner request 2026-11-22):

- **nuvx** as it is today (`main`): Android phone/tablet, TV, Auto, Wear OS, iOS, desktop, web, extension, services.
- **BitChord's `multiplatform-rebase` branch** (owner-supplied archive): the newest BitChord features, including its
  **PC app** (Windows, Linux, macOS). BitChord `main` (also supplied) is identical to the version already audited.

Both are behaviour references only (`DECISIONS.md`, R1). Nothing of their source code carries over, and nothing of
**BitChord's backend** (its self-written API: the Go Listen Together party server and its `bitchord.kushagrasingh.in`
invite links) is used, copied or contacted. Features that BitChord serves from that backend are rebuilt on nuvx's own
services (§12).

Columns: **Platforms** P = phone (Android, iOS), C = PC (Windows, Linux, macOS), W = web, T = TV, A = car, R = watch.
**From**: N = nuvx today, B = BitChord rebase (new for nuvx), N+B = both. **Step** = plan step that delivers it.

## 1. Playback

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| Play, pause, seek, next, previous | Standard transport, from the app, media keys, lock screen and notifications | all | N+B | 6 |
| Queue | Up Next list: add, play next, reorder, remove, clear; autoplay continues with related songs when it ends | all | N+B | 6 |
| Shuffle and repeat | Shuffle on/off; repeat off / all / one | all | N+B | 6 |
| Gapless and crossfade | No gap between tracks; crossfade adjustable 0–12 s | P C W | N+B | 6 |
| AutoMix (beta) | DJ-style transitions with beat matching and tempo stretching. Its analysis models are **downloaded on demand**, not bundled (R4): turning AutoMix on offers the download with its size; progress, resume and checksum verification; AutoMix stays off until the download is verified; models can be removed in settings | P C W | N+B | 10 |
| Playback speed, skip silence | Speed 0.5×–2.0×; optional silence skipping | P C | N+B | 6 |
| Loudness normalisation | Even volume across tracks, target loudness setting | all | N+B | 6 |
| Equalizer | Built-in equalizer with presets and per-output profiles; on Android also the system equalizer | P C | N+B | 10 |
| Audio output choice | Pick the output device on PC; per-network quality ceilings (Wi-Fi vs mobile data) on phones | P C | N+B | 6 |
| Lossless audio | FLAC/ALAC from a configured module source, YouTube Music as fallback; "prefer lossless" switch | P C | N+B | 10 |
| Direct USB / bit-perfect output | Send audio to a USB DAC bypassing Android's mixer, where supported | P (Android) | N+B | 12 |
| Dolby Atmos shortcut | Open the device's Dolby Atmos panel when the phone has one; tells the user when it does not | P (Android) | B | 12 |
| Sleep timer | Fixed durations or "stop after this track" | all | N+B | 10 |
| Background playback | Keeps playing with the app in the background; optional "closing from recents stops music" | P C | N+B | 12 |
| Stats for nerds | Codec, bit depth, sample rate, source and loudness on the now-playing screen | P C W | N+B | 10 |
| Original-version alignment | Matches a song's original album cut to the video version and aligns them | P C | B | 10 |

## 2. Music sources and accounts

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| YouTube Music search, browse, play | Everything on YouTube Music, through InnerTubeX (R2) | all | N+B | 7–8 |
| Google sign-in | Personal home, library, likes, playlists | all | N+B | 7–8 |
| Accounts and channels | Several accounts; switch YouTube brand channels ("Choose a channel", "Use this channel") | P C | B | 7–8 |
| PC sign-in from the browser | Sign in by reading YouTube cookies from the user's installed browser, **only after explicit consent each time**; tokens kept in the OS secret store | C | B | 8 |
| Module sources | Add, edit, test and health-check third-party source modules (e.g. lossless) | P C | N+B | 10 |
| JioSaavn | Additional catalogue source | P C | N+B | 10 |
| Spotify | Connect, import playlists, match tracks to YouTube Music; Spotify canvases | P C W | N+B | 10 |
| Local music | Play files from device folders; library watches folders for changes; search inside a folder | P C | N+B | 10 |
| Likes and dislikes | Like, dislike, undo dislike | all | N+B | 10 |

## 3. Library, playlists, discovery

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| Home and mood shelves | Personal home; shelves such as New albums & singles, Commute, Workout, Energy boosters, Romance, Sad, 100 % on repeat | all | N+B | 10 |
| New, Radio, Podcasts, Explore | The corresponding YouTube Music sections | all | N+B | 10 |
| Library | Recently played, artists, albums, songs, made for you, all playlists; grid or list view | all | N+B | 10 |
| Playlists | Create, rename (with undo), edit, reorder, delete; YouTube Music or "nuvx only" local playlists; earlier versions with restore | all | N | 10 |
| Import and export | M3U and CSV playlists | P C | N | 10 |
| Song and browse action sheets | Long-press / right-click menus for songs, albums, artists, playlists | all | N+B | 10 |
| Listening stats ("Replay") | Minutes listened, top songs/artists, "when you listen most", counted per device | P C | N+B | 10 |
| Song recognition | Identify a song playing nearby | P | N | 12 |

## 4. Lyrics

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| Synced and word-synced lyrics | Line and word/syllable highlighting, Apple-style animation, background vocals, singer sides | all | N+B | 10 |
| Several lyrics sources | Provider order, change provider per song, embedded lyrics from files | all | N+B | 10 |
| Translation and romanisation | Translated and romanised lines with a chosen language | all | N+B | 10 |
| Share lyrics | Lyrics share card | P | N | 10 |
| Lyrics debug log | Optional live view of provider queries in the lyrics panel | P C | B | 10 |

## 5. Look and feel

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| App shell | Sidebar shell, phone tab bar, narrow-web drawer (`spec/01-app-shell.md`) | all | N | 4 |
| Artwork-driven colours | Backdrop and accents from the current artwork; "legacy mesh gradient" option (drifting colour blobs) | all | N+B | 11 |
| Glass UI | Translucent bars and panels; reduce-blur option | all | N+B | 4, 11 |
| Motion artwork (canvas) | Animated artwork on now playing; community canvases | P C W | N+B | 10 |
| Full-screen player and side panel | Now playing with lyrics and Up Next | all | N+B | 4, 10 |
| Home-screen widget | Open nuvx / now playing widget | P | N+B | 12 |

## 6. Together and connected

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| Listen Together | Create a party with a six-character code, invite link, members control the music together; activity feed (played, paused, skipped, queued, reordered…); party profile; custom server option. **Runs on nuvx's own service**, never BitChord's backend (§12) | all | N+B | 10 |
| LAN Sync and Nuvx Connect | Pair devices on the local network, sync library and settings, control playback, copy files, Bluetooth | P C | N | 10 |
| Cast | Play on Chromecast devices | P | N | 12 |
| Discord Rich Presence | In-app Discord sign-in; track, artist, album and progress shown on Discord | P C W | N+B | 10 |
| Scrobbling | Last.fm and ListenBrainz | P C | N+B | 10 |
| Watch remote | Wear OS remote and standalone watch app | R | N | 13 |
| Cars | Android Auto browse tree and standalone Auto app; CarPlay to be evaluated | A | N | 13 |
| TV | TV interface and playback for Android TV / Google TV | T | N | 13 |

## 7. Downloads and storage

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| Offline downloads | Save tracks with metadata; resume; storage limit; progress notification ("songs being saved to your Music folder"); saved under Music/nuvx | P C | N+B | 10 |
| Download manager | Queue, pause, retry, remove, cleanup of partial files | P C | N+B | 10 |
| Media cache | Bounded cache of streamed audio and artwork | all | N+B | 6 |

## 8. Settings, backup, diagnostics

| Feature | What the user gets | Platforms | From | Step |
|---|---|---|---|---|
| Settings | Appearance, audio, lyrics, playback, downloads, network/privacy (proxy, Orbot), integrations, advanced & experimental | all | N+B | 10 |
| Backup and restore | Export/import settings and data; WebDAV server backup | P C | N+B | 10 |
| Diagnostics | Track log, copy log, debug page | all | N+B | 10 |
| Updates | In-app update check (stable, optional nightlies) | P C | N | 15 |
| First-run intro | Welcome flow | P C T | N | 11 |
| Licenses | In-app third-party notices | all | N+B | 15 |

## 9. PC app (Windows, Linux, macOS)

The PC app is a primary target (owner, 2026-11-22). Besides everything above:

| Feature | What the user gets | From | Step |
|---|---|---|---|
| Window chrome | Custom title bar and frame; translucent window backdrop where the OS offers it (Windows Mica/acrylic, macOS vibrancy) | N+B | 4 |
| Window modes | Normal, mini player in its own window, full screen; remembers size and position | N+B | 4, 11 |
| Tray | Tray icon with play/pause/next/previous and show/quit; close-to-tray option | N+B | 12 |
| System media controls | Windows media overlay (SMTC), Linux MPRIS, macOS Now Playing; media keys | N+B | 12 |
| Notifications | Track-change and status notifications | N+B | 12 |
| Packages | Windows installer (EXE and MSI), Linux DEB and AppImage, macOS DMG | N+B | 15 |
| Bundled Java runtime | Needed for InnerTubeX (R2); size and start-up measured | — | 8 |

## 10. Web and browser extension

| Feature | What the user gets | From | Step |
|---|---|---|---|
| Web player | The app in the browser (sidebar shell, narrow drawer); YouTube Music through the owner-run relay (R2) | N | 12 |
| Browser extension | Chrome and Firefox extension that connects the web player to YouTube Music and Discord | N | 12 |

## 11. iOS specifics

Background audio, lock screen and Control Center, interruptions, Keychain for credentials, widget (`spec/01-app-shell.md`
phone shell). From N; step 12.

## 12. Services (nuvx-owned only)

| Service | Purpose | Note |
|---|---|---|
| Listen Together service | Parties, codes, time sync | nuvx's own (today a Cloudflare worker). Its protocol is specified from nuvx's service, never from BitChord's backend. Invite links use a nuvx domain. |
| Sync server | Account sync between devices | nuvx's own (Oracle server) |
| YouTube Music relay | Web app access to YouTube Music (R2) | owner-run, JVM-capable host |
| Discord bot, website | Community and download pages | nuvx's own |

## Excluded

- **BitChord's backend** (Go party server, `bitchord-listen-together.onrender.com`, `bitchord.kushagrasingh.in` invite and
  download links): not used, copied or contacted.
- BitChord branding and strings ("Counted on this device with BitChord", "Saved to Music/BitChord", "Open BitChord"):
  the rebuild uses nuvx's names.
- "Sharing is coming soon" placeholder from BitChord: not a feature.

## Open points

- TV, Auto, Wear OS and CarPlay behaviour is specified in step 13.
- Module sources rely on third-party servers the user configures; which ones are offered by default is an owner decision.
