//! Foreground-only recognition from the default microphone.
//!
//! Raw samples stay in this process. Only the Shazam fingerprint is sent to the
//! recognition service, and only after the user accepts the disclosure.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use futures_util::StreamExt;
use serde_json::{Value, json};
use zeroize::Zeroize;

use crate::backend::Backend;

pub const MAX_CAPTURE: Duration = Duration::from_secs(10);
const MIN_CAPTURE: Duration = Duration::from_secs(3);
const MAX_INPUT_RATE: u32 = 96_000;
const RESPONSE_LIMIT: usize = 1024 * 1024;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum AudioSource {
    /// Music audible in the room, captured by the default microphone.
    #[default]
    Nearby,
    /// Audio being rendered by the current system output device.
    SystemPlayback,
}

/// True only when the active output can also be opened as a capture input.
pub fn system_playback_available() -> bool {
    let host = rodio::cpal::default_host();
    system_playback_device(&host).is_some()
}

#[cfg(target_os = "windows")]
fn system_playback_device(host: &rodio::cpal::Host) -> Option<rodio::cpal::Device> {
    use rodio::cpal::traits::HostTrait;
    // CPAL's WASAPI backend supports loopback capture on an output endpoint.
    host.default_output_device()
}

#[cfg(target_os = "linux")]
fn system_playback_device(host: &rodio::cpal::Host) -> Option<rodio::cpal::Device> {
    use rodio::cpal::traits::{DeviceTrait, HostTrait};

    // PipeWire exposes output sinks as duplex devices. Prefer that path when present.
    if let Some(sink) = host
        .default_output_device()
        .filter(|device| device.supports_input() && device.default_input_config().is_ok())
    {
        return Some(sink);
    }

    // PulseAudio exposes sink monitors as input sources. Use one only when the
    // sound server identifies it as a monitor; never guess from an arbitrary input.
    host.input_devices().ok()?.find(|device| {
        let id = device
            .id()
            .ok()
            .map(|id| id.id().to_ascii_lowercase())
            .unwrap_or_default();
        let described_as_monitor = device
            .description()
            .ok()
            .is_some_and(|description| description.name().to_ascii_lowercase().contains("monitor"));
        (id.ends_with(".monitor") || described_as_monitor) && device.default_input_config().is_ok()
    })
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
fn system_playback_device(_host: &rodio::cpal::Host) -> Option<rodio::cpal::Device> {
    None
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Status {
    Listening,
    Matching,
    Match { title: String, artist: String },
    NoMatch,
    Failed(String),
    Cancelled,
}

pub struct Session {
    status: Arc<Mutex<Status>>,
    cancel: Arc<AtomicBool>,
    started: Instant,
}

struct SensitiveSamples(Vec<i16>);

impl Drop for SensitiveSamples {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

struct SampleChunk(Vec<i16>);

impl Drop for SampleChunk {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

impl Session {
    pub fn start(backend: &Backend, source: AudioSource) -> Self {
        let status = Arc::new(Mutex::new(Status::Listening));
        let cancel = Arc::new(AtomicBool::new(false));
        let started = Instant::now();

        let task_status = status.clone();
        let task_cancel = cancel.clone();
        backend.spawn(async move {
            if task_cancel.load(Ordering::Relaxed) {
                set_status(&task_status, Status::Cancelled);
                return;
            }

            let capture_cancel = task_cancel.clone();
            let samples =
                match tokio::task::spawn_blocking(move || capture(&capture_cancel, source)).await {
                    Ok(Ok(samples)) => samples,
                    Ok(Err(error)) => {
                        if !task_cancel.load(Ordering::Relaxed) {
                            set_status(&task_status, Status::Failed(error));
                        }
                        return;
                    }
                    Err(_) => {
                        if !task_cancel.load(Ordering::Relaxed) {
                            set_status(
                                &task_status,
                                Status::Failed("Audio capture stopped unexpectedly.".into()),
                            );
                        }
                        return;
                    }
                };

            if task_cancel.load(Ordering::Relaxed) {
                set_status(&task_status, Status::Cancelled);
                return;
            }
            set_status(&task_status, Status::Matching);

            let signature = match tokio::task::spawn_blocking(move || {
                let samples = SensitiveSamples(samples);
                let usable = samples.0.len() / 128 * 128;
                if usable < (MIN_CAPTURE.as_secs() as usize * 16_000) {
                    return Err("Not enough audio was captured. Try again.".to_owned());
                }
                let signature =
                    songrec::SignatureGenerator::make_signature_from_buffer(&samples.0[..usable]);
                let peaks = signature
                    .frequency_band_to_sound_peaks
                    .values()
                    .map(Vec::len)
                    .sum::<usize>();
                Ok((signature, peaks))
            })
            .await
            {
                Ok(Ok((signature, peaks))) if peaks > 0 => signature,
                Ok(Ok(_)) => {
                    set_status(&task_status, Status::NoMatch);
                    return;
                }
                Ok(Err(error)) => {
                    set_status(&task_status, Status::Failed(error));
                    return;
                }
                Err(_) => {
                    set_status(
                        &task_status,
                        Status::Failed("Could not analyze the captured audio.".into()),
                    );
                    return;
                }
            };

            if task_cancel.load(Ordering::Relaxed) {
                set_status(&task_status, Status::Cancelled);
                return;
            }

            match lookup(signature, task_cancel.clone()).await {
                Ok(Some(found)) => set_status(&task_status, found),
                Ok(None) => set_status(&task_status, Status::NoMatch),
                Err(_) if task_cancel.load(Ordering::Relaxed) => {
                    set_status(&task_status, Status::Cancelled);
                }
                Err(error) => set_status(&task_status, Status::Failed(error)),
            }
        });

        Self {
            status,
            cancel,
            started,
        }
    }

    pub fn status(&self) -> Status {
        self.status
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    pub fn capturing(&self) -> bool {
        matches!(self.status(), Status::Listening)
    }

    pub fn elapsed(&self) -> Duration {
        self.started.elapsed().min(MAX_CAPTURE)
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
        set_status(&self.status, Status::Cancelled);
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

fn set_status(status: &Mutex<Status>, value: Status) {
    *status
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = value;
}

fn capture(cancel: &AtomicBool, source: AudioSource) -> Result<Vec<i16>, String> {
    use rodio::cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    use rodio::cpal::{self, SampleFormat, StreamConfig};

    let host = cpal::default_host();
    let (device, supported) = match source {
        AudioSource::Nearby => {
            let device = host
                .default_input_device()
                .ok_or_else(|| "No default microphone is available.".to_owned())?;
            let supported = device.default_input_config().map_err(|_| {
                "The default microphone is unavailable or permission was denied.".to_owned()
            })?;
            (device, supported)
        }
        AudioSource::SystemPlayback => {
            if !system_playback_available() {
                return Err("System playback capture is unavailable on this device.".into());
            }
            let device = system_playback_device(&host).ok_or_else(|| {
                "System playback capture is unavailable on this device.".to_owned()
            })?;
            #[cfg(target_os = "windows")]
            let supported = device.default_output_config();
            #[cfg(target_os = "linux")]
            let supported = device.default_input_config();
            #[cfg(not(any(target_os = "windows", target_os = "linux")))]
            return Err("System playback capture is unavailable on this device.".into());
            #[cfg(any(target_os = "windows", target_os = "linux"))]
            let supported = supported
                .map_err(|_| "System playback capture is unavailable on this device.".to_owned())?;
            (device, supported)
        }
    };
    let sample_rate = supported.sample_rate();
    let channels = usize::from(supported.channels());
    if !(8_000..=MAX_INPUT_RATE).contains(&sample_rate) || channels == 0 {
        return Err("The microphone uses an unsupported audio format.".into());
    }

    let stream_config: StreamConfig = supported.into();
    let (samples_tx, samples_rx) = crossbeam_channel::bounded(16);
    let overflow = Arc::new(AtomicBool::new(false));
    let callback_error = Arc::new(Mutex::new(None::<String>));
    let stream = match supported.sample_format() {
        SampleFormat::F32 => input_stream::<f32>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::F64 => input_stream::<f64>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::I8 => input_stream::<i8>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::I16 => input_stream::<i16>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::I24 => input_stream::<cpal::I24>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::I32 => input_stream::<i32>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::I64 => input_stream::<i64>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::U8 => input_stream::<u8>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::U16 => input_stream::<u16>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::U24 => input_stream::<cpal::U24>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::U32 => input_stream::<u32>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::U64 => input_stream::<u64>(
            &device,
            &stream_config,
            channels,
            samples_tx,
            overflow.clone(),
            callback_error.clone(),
        ),
        SampleFormat::DsdU8 | SampleFormat::DsdU16 | SampleFormat::DsdU32 => {
            return Err("The microphone uses an unsupported DSD audio format.".into());
        }
        _ => return Err("The microphone uses an unsupported audio format.".into()),
    }
    .map_err(|_| "Could not open the default microphone.".to_owned())?;
    stream
        .play()
        .map_err(|_| "Could not start the default microphone.".to_owned())?;

    let max_input_frames = sample_rate as usize * MAX_CAPTURE.as_secs() as usize;
    let mut mono = SensitiveSamples(Vec::with_capacity(max_input_frames));
    let started = Instant::now();
    let hard_deadline = MAX_CAPTURE + Duration::from_secs(2);

    while mono.0.len() < max_input_frames && started.elapsed() < hard_deadline {
        if cancel.load(Ordering::Relaxed) {
            drop(stream);
            return Err("Recognition cancelled.".into());
        }
        if overflow.load(Ordering::Relaxed) {
            drop(stream);
            return Err("Audio capture fell behind. Try again.".into());
        }
        if let Some(error) = callback_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
        {
            drop(stream);
            return Err(format!("Microphone capture failed: {error}"));
        }
        match samples_rx.recv_timeout(Duration::from_millis(60)) {
            Ok(chunk) => {
                let remaining = max_input_frames.saturating_sub(mono.0.len());
                mono.0.extend(chunk.0.iter().take(remaining).copied());
            }
            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                drop(stream);
                return Err("The microphone stopped unexpectedly.".into());
            }
        }
    }
    drop(stream);

    if cancel.load(Ordering::Relaxed) {
        return Err("Recognition cancelled.".into());
    }
    if mono.0.len() < sample_rate as usize * MIN_CAPTURE.as_secs() as usize {
        return Err("The microphone did not provide three seconds of audio.".into());
    }

    Ok(resample_mono_16khz(&mono.0, sample_rate))
}

fn input_stream<T>(
    device: &rodio::cpal::Device,
    config: &rodio::cpal::StreamConfig,
    channels: usize,
    samples_tx: crossbeam_channel::Sender<SampleChunk>,
    overflow: Arc<AtomicBool>,
    callback_error: Arc<Mutex<Option<String>>>,
) -> Result<rodio::cpal::Stream, rodio::cpal::Error>
where
    T: rodio::cpal::SizedSample + Copy + Send + 'static,
    f32: rodio::cpal::FromSample<T>,
{
    use rodio::cpal::Sample;
    use rodio::cpal::traits::DeviceTrait;

    device.build_input_stream(
        *config,
        move |data: &[T], _| {
            let mut mono = Vec::with_capacity(data.len() / channels);
            for frame in data.chunks_exact(channels) {
                let average = frame
                    .iter()
                    .map(|sample| f32::from_sample(*sample))
                    .sum::<f32>()
                    / channels as f32;
                mono.push((average.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16);
            }
            match samples_tx.try_send(SampleChunk(mono)) {
                Ok(()) => {}
                Err(crossbeam_channel::TrySendError::Full(_)) => {
                    overflow.store(true, Ordering::Relaxed);
                }
                Err(crossbeam_channel::TrySendError::Disconnected(_)) => {}
            }
        },
        move |error| {
            *callback_error
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(error.to_string());
        },
        None,
    )
}

fn resample_mono_16khz(input: &[i16], input_rate: u32) -> Vec<i16> {
    if input_rate == 16_000 {
        return input.to_vec();
    }
    if input.is_empty() || input_rate == 0 {
        return Vec::new();
    }

    let output_len = input.len().saturating_mul(16_000) / input_rate as usize;
    (0..output_len)
        .map(|index| {
            let position = index as f64 * input_rate as f64 / 16_000.0;
            let left = position.floor() as usize;
            let right = (left + 1).min(input.len() - 1);
            let fraction = (position - left as f64) as f32;
            let value = input[left] as f32 * (1.0 - fraction) + input[right] as f32 * fraction;
            value.round().clamp(i16::MIN as f32, i16::MAX as f32) as i16
        })
        .collect()
}

async fn lookup(
    signature: songrec::DecodedSignature,
    cancel: Arc<AtomicBool>,
) -> Result<Option<Status>, String> {
    let timestamp = unix_timestamp_seconds(SystemTime::now())?;
    let payload = fingerprint_payload(&signature, timestamp)?;
    let tag = uuid::Uuid::new_v4().simple().to_string().to_uppercase();
    let id = uuid::Uuid::new_v4().simple().to_string();
    let url = format!(
        "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/{tag}/{id}?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
    );
    let client = crate::proxy::http();
    let request = client
        .post(url)
        .header("Content-Language", "en_US")
        .header(
            "User-Agent",
            concat!("VYRAL-Rust/", env!("CARGO_PKG_VERSION")),
        )
        .timeout(Duration::from_secs(15))
        .json(&payload);

    let response = tokio::select! {
        _ = wait_cancelled(cancel.clone()) => return Err("Recognition cancelled.".into()),
        response = request.send() => response.map_err(|_| "Shazam could not be reached. Check your connection and try again.".to_owned())?,
    };
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    if !response.status().is_success() {
        return Err("Shazam could not identify this audio right now.".into());
    }
    if response
        .content_length()
        .is_some_and(|length| length > RESPONSE_LIMIT as u64)
    {
        return Err("The recognition service returned an oversized response.".into());
    }

    let mut body = Vec::new();
    let mut stream = response.bytes_stream();
    loop {
        let next = tokio::select! {
            _ = wait_cancelled(cancel.clone()) => return Err("Recognition cancelled.".into()),
            next = stream.next() => next,
        };
        match next {
            Some(Ok(chunk)) if body.len() + chunk.len() <= RESPONSE_LIMIT => {
                body.extend_from_slice(&chunk);
            }
            Some(Ok(_)) => {
                return Err("The recognition service returned an oversized response.".into());
            }
            Some(Err(_)) => return Err("The recognition response was interrupted.".into()),
            None => break,
        }
    }
    let response: Value = serde_json::from_slice(&body)
        .map_err(|_| "The recognition service returned an invalid response.".to_owned())?;
    Ok(parse_response(&response))
}

fn unix_timestamp_seconds(now: SystemTime) -> Result<u64, String> {
    now.duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|_| "The system clock is unavailable.".to_owned())
}

fn fingerprint_payload(
    signature: &songrec::DecodedSignature,
    timestamp_seconds: u64,
) -> Result<Value, String> {
    let uri = signature
        .encode_to_uri()
        .map_err(|_| "Could not create the audio fingerprint.".to_owned())?;
    Ok(json!({
        "geolocation": {},
        "signature": {
            "samplems": (signature.number_samples as f64 / signature.sample_rate_hz as f64 * 1000.0) as u64,
            "timestamp": timestamp_seconds,
            "uri": uri,
        },
        "timestamp": timestamp_seconds,
        "timezone": "UTC",
    }))
}

async fn wait_cancelled(cancel: Arc<AtomicBool>) {
    while !cancel.load(Ordering::Relaxed) {
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
}

fn parse_response(response: &Value) -> Option<Status> {
    let matches = response.get("matches")?.as_array()?;
    if matches.is_empty() {
        return None;
    }
    let track = response
        .get("track")
        .filter(|track| track.is_object())
        .or_else(|| matches.first()?.get("track"))?;
    let title = track.get("title")?.as_str()?.trim();
    let artist = track.get("subtitle")?.as_str()?.trim();
    if title.is_empty() || artist.is_empty() {
        return None;
    }
    Some(Status::Match {
        title: title.to_owned(),
        artist: artist.to_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resampling_produces_16khz_output_without_exceeding_duration() {
        let input = vec![12_000i16; 48_000 * 12];
        let output = resample_mono_16khz(&input[..48_000 * 10], 48_000);
        assert_eq!(output.len(), 16_000 * 10);
        assert!(output.iter().all(|sample| *sample == 12_000));
    }

    #[test]
    fn shazam_match_maps_to_existing_search_terms() {
        let parsed = parse_response(&json!({
            "matches": [{ "id": "opaque" }],
            "track": { "title": "Song", "subtitle": "Artist" }
        }));
        assert_eq!(
            parsed,
            Some(Status::Match {
                title: "Song".into(),
                artist: "Artist".into(),
            })
        );
    }

    #[test]
    fn empty_or_malformed_responses_are_no_match() {
        assert_eq!(parse_response(&json!({ "matches": [] })), None);
        assert_eq!(parse_response(&json!({ "matches": [{}] })), None);
        assert_eq!(
            parse_response(&json!({
                "matches": [{}],
                "track": { "title": " ", "subtitle": "Artist" }
            })),
            None
        );
    }

    #[test]
    fn fingerprint_request_contains_signature_not_pcm_or_location() {
        let samples = vec![0i16; 128 * 512];
        let signature = songrec::SignatureGenerator::make_signature_from_buffer(&samples);
        let payload = fingerprint_payload(&signature, 1_700_000_000).unwrap();
        assert!(payload.get("signature").is_some());
        assert!(payload.get("audio").is_none());
        assert!(payload.get("samples").is_none());
        assert_eq!(payload.get("geolocation"), Some(&json!({})));
        assert_eq!(payload["timestamp"], 1_700_000_000u64);
        assert_eq!(payload["signature"]["timestamp"], 1_700_000_000u64);
        assert!(
            payload["signature"]["uri"]
                .as_str()
                .is_some_and(|value| value.starts_with("data:audio/vnd.shazam.sig;base64,"))
        );
        assert_eq!(payload["signature"]["samplems"], 4096u64);
    }

    #[test]
    fn shazam_timestamps_use_checked_unix_seconds() {
        assert_eq!(
            unix_timestamp_seconds(UNIX_EPOCH + Duration::from_secs(1_700_000_000)),
            Ok(1_700_000_000)
        );
        assert!(unix_timestamp_seconds(UNIX_EPOCH - Duration::from_secs(1)).is_err());
    }

    #[test]
    fn capture_limit_is_short_and_foreground_only() {
        assert_eq!(MAX_CAPTURE, Duration::from_secs(10));
        assert_eq!(MIN_CAPTURE, Duration::from_secs(3));
        assert_eq!(AudioSource::default(), AudioSource::Nearby);
    }
}
