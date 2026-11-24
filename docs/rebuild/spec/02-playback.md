# Spec 02: local library, playback, queue, saved settings

Desktop milestone 2 (`PLAN.md`). Behaviour only; written from the current apps' behaviour and the owner's prompts.

## 1. Local library

- The user adds one or more **music folders** in Settings (native folder picker) and can remove them.
- The app scans each folder recursively for audio files: **FLAC, MP3, WAV, OGG Vorbis, M4A (AAC/ALAC), AIFF**
  (Opus once a decoder is available; files that cannot be decoded are skipped, not listed).
- For each file: title, artist, album, album artist, track number, disc number, duration, from the file's tags;
  without a title tag the file name (without extension) is the title; without artist/album: "Unknown Artist" /
  "Unknown Album".
- **Songs** page lists every song: title, artist, album, duration (`m:ss`); sorted by artist, album, disc, track,
  title (case-insensitive). With no folders: an empty state with an "Add a music folder" button. While scanning: a
  "Scanning…" line with the count found so far. Rescanning happens at start and when folders change.
- Scanning never blocks the window.

## 2. Playing

- Clicking a song row (double-click on PC) plays it and makes the **queue** the whole list it was in, starting there.
- States: **idle** (nothing loaded), **loading**, **playing**, **paused**, **error** (with a short message).
- **Play/Pause** toggles; Space does the same (spec 01 §6).
- **Seek**: dragging or clicking the seek line jumps to that point.
- **Previous**: if more than **3 s** into the track, restarts it; otherwise goes to the previous queue item (at the
  first item, restarts it).
- **Next**: next queue item. **End of track**: repeat one → same track again; otherwise next item; at the end of the
  queue, repeat all → first item, repeat off → stop (idle position at the end, the last track stays shown).
- **Shuffle up next** (spec 01 §2.3): one-shot, randomly reorders the items after the current one.
- An unreadable or undecodable file shows an error for that track and skips to the next.
- If the audio output device disappears, playback pauses with a message and continues on the new default output when
  the user presses Play.
- Position updates at least 4 times a second; the bar shows title, artists, elapsed and remaining time.
- Volume 0–100 and mute apply immediately without gaps or clicks; the gain follows a perceptual curve (volume² of
  full scale). Playback continues while the window is minimised.
- Output: the system default device, at the device's sample rate (resampled as needed), stereo.

## 3. Queue (Up Next panel)

- The queue is a list with a current position. Up Next shows the current track and the upcoming ones.
- Actions: **play next** (insert after the current), **add to queue** (append), **remove**, **move** (reorder),
  **clear upcoming**. Removing the current track plays the next one (or stops at the end).
- Clicking an upcoming item plays it (the queue stays, the position moves there).

## 4. Saved settings and session

- Saved in the user's config folder under **`nuvx-rebuild`** (separate from the current nuvx app's data, which is
  never read or changed): volume, mute, repeat mode, music folders, and the **last session** (queue as file paths,
  current position, playback position).
- On start the last session is restored **paused** at the saved position; missing files are dropped from the queue.
- Writes are atomic (write a temporary file, then rename), so a crash never leaves a half-written file; an unreadable
  settings file is kept as `settings.json.bad` and defaults are used.

## 5. Acceptance tests

1. A folder with FLAC, MP3, WAV and OGG files and one broken file lists the four playable files with tag data, sorted
   as §1; the broken file is not listed.
2. Queue: play from item 3 of 5; Next → 4; Previous within 3 s → 3; Previous after 3 s → restart 3; play next inserts
   after current; remove current advances; repeat off stops after the last; repeat all wraps; repeat one repeats.
3. Shuffle up next keeps the current item and the same set of upcoming items.
4. A real file plays audibly on the default output, pauses, resumes, seeks, and reaches the next track at its end.
5. Volume and mute change loudness; settings and the session survive a restart; a corrupt settings file is set aside.
