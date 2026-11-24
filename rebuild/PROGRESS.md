# Rebuild progress and handoff

Resume point for the desktop rebuild. Plan: `docs/rebuild/PLAN.md`; decisions: `docs/rebuild/DECISIONS.md`;
specs: `docs/rebuild/spec/` (00 features, 01 app shell, 02 playback). Desktop first (Windows, Linux, macOS); Android,
iOS and web only after the desktop milestones, with the owner's go-ahead.

**Branch:** work continues on `rebuild/desktop` (created 2026-11-22). `main` has everything up to M1
(`6b7793c1`). Owner preferences: everything in the main session, **no subagents**; keep SF Pro and SF Symbols;
YouTube Music through InnerTubeX (R2).

## Done

| Date | Milestone | What | Verified |
|---|---|---|---|
| 2026-11-22 | M1 foundation | Dioxus 0.7.10 PC shell: title-bar player (window buttons, drag, double-click maximise), sidebar, placeholder pages, side panel, full-screen, themes, keyboard, SF Symbols from SFStudio, nuvx mark, CI (`rebuild-checks.yml`) | fmt, clippy -D warnings, 23 tests, release build (5.4 MB) on Windows 11; window checked by hand (sizes, navigation, Lyrics panel + Escape, maximise/restore) |
| 2026-11-22 | M2 part | `crates/nuvx-playback`: queue and repeat rules of spec 02 §2–§3 (next, previous with 3 s restart, end of track by repeat mode, jump, play next, add, remove, move, clear upcoming, shuffle up next) | 8 unit tests pass, clippy clean |
| 2026-11-22 | Docs | Spec 02 (local library, playback, queue, saved settings); plan rewritten as desktop milestones M2–M8 | — |

## In progress: M2 real playback (spec 02)

`crates/nuvx-audio` exists only as a stub (`pub fn _x() {}`) with its dependencies pinned:
`symphonia =0.6.1` (feature `all`), `cpal =0.18.2`, `rubato =5.1.0`, `ringbuf =0.6.0`. It builds.

API facts already checked in the downloaded crates (so they need not be looked up again):

- **Symphonia 0.6:** `symphonia::default::{get_probe, get_codecs}`; `Probe::probe(&Hint, MediaSourceStream, FormatOptions,
  MetadataOptions) -> Result<Box<dyn FormatReader>>` (no `ProbeResult` any more); `MediaSourceStream::new(Box<dyn
  MediaSource>, MediaSourceStreamOptions)`; `Hint::new().with_extension(..)`; `reader.default_track(TrackType::Audio)`;
  `track.codec_params: Option<CodecParameters>` → `.audio()` gives `AudioCodecParameters { codec, sample_rate, .. }`;
  `track.time_base: Option<TimeBase>` (`calc_time(ts) -> Option<Time>`, `Time::as_secs_f64`,
  `Time::try_from_secs_f64`), `track.num_frames`; `get_codecs().make_audio_decoder(&params, &AudioDecoderOptions)`;
  `reader.next_packet() -> Result<Option<Packet>>` (`None` = end of stream); `decoder.decode(&packet) ->
  GenericAudioBufferRef` with `spec().rate()`, `spec().channels().count()`, `frames()`,
  `copy_to_vec_interleaved::<f32>(&mut Vec)`; `reader.seek(SeekMode::Accurate, SeekTo::Time { time, track_id })` then
  `decoder.reset()`; errors `Error::{IoError, DecodeError (skip packet), ResetRequired, Unsupported, ..}`.
- **cpal 0.18:** `cpal::default_host().default_output_device()`, `device.default_output_config()` →
  `sample_format()`, `config()`; `StreamConfig { channels, sample_rate: u32, buffer_size }`;
  `device.build_output_stream::<T, _, _>(config, data_cb, err_cb, Option<Duration>)`. Build per sample format (F32,
  I16, U16). The stream may be `!Send`: create and keep it on the engine thread.
- **rubato 5:** `rubato::Fft::new(rate_in, rate_out, chunk, channels, FixedSync::Input)`, used through
  `rubato::audioadapter_buffers::direct::InterleavedSlice` (input) and `Resampler::process_into_buffer`; or
  `process(..)` returning `InterleavedOwned` (`take_data()`).
- **ringbuf 0.6:** `HeapRb::<f32>::new(cap).split()` → producer `push_slice`, consumer `pop_slice`, `clear`.

Planned design (not written yet): an engine thread owns the format reader, decoder, resampler and the cpal stream;
commands over `std::sync::mpsc` (load path at position, play, pause, seek, gain); a lock-free ring buffer feeds the
audio callback; volume as an atomic gain (volume² curve, spec 02 §2); position from frames played since the last
load/seek; events back (loaded with duration/rate/channels/codec, ended, error).

## Next executable tasks

1. Write `nuvx-audio` per the design above; test decode on generated WAV fixtures (unit) and play a real file by ear.
2. `nuvx-library`: scan music folders (`walkdir`), tags with `lofty =0.25.5`, sort as spec 02 §1; tests with fixtures.
3. Settings and session in `%APPDATA%`-style config dir under **`nuvx-rebuild`** (`directories =6`), atomic write,
   corrupt file kept as `.bad` (spec 02 §4); native folder picker `rfd =0.18`.
4. Wire into `nuvx-ui`: Songs page list, player bar (title, artists, times, seek), Up Next panel from the queue,
   Settings folders; replace `shell::Repeat` with `nuvx_playback::Repeat`.
5. Run, check by hand, update this file, merge `rebuild/desktop` into `main` when M2 passes spec 02 §5.

## Known gaps and risks

- macOS never built or run (no machine); Linux built only in CI (`rebuild-checks.yml`, first run pending at handoff).
- Provenance: since step 4 the code is written by the same session that read the old nuvx and BitChord sources (R1
  update); not a two-team implementation.
- During API lookups one search accidentally listed function names from the old app folders (`app/desktop`,
  `app/ios/native-core`); no file was opened.
