//! Volume normalization: how much a track is turned up or down so songs play at a similar loudness.
//! Shared cases: `core/contracts/loudness-gain.json` (also checked by Android's PlaybackLoudnessTest).

/// YouTube reports a track's `loudnessDb` relative to its own -14 LUFS reference.
pub const DEFAULT_TARGET_LUFS: i32 = -14;

/// Gain in millibels (hundredths of a dB) for a track measured `loudness_db` above YouTube's reference, for a
/// target in LUFS. Turning down is allowed up to 15 dB, turning up only up to 3 dB (no clipping surprises).
/// Rounds half up in f64, exactly as Android did (`roundToInt`), so every app gets the same value. NaN gives 0.
pub fn norm_gain_mb(target_lufs: i32, loudness_db: f64) -> i32 {
    let mb = ((target_lufs - DEFAULT_TARGET_LUFS) as f64 - loudness_db) * 100.0;
    if mb.is_nan() {
        return 0;
    }
    round_half_up(mb).clamp(-1500.0, 300.0) as i32
}

/// Java's `Math.round`, exactly: `(x + 0.5).floor()` is off for the value just below 0.5.
fn round_half_up(x: f64) -> f64 {
    let down = x.floor();
    if x - down >= 0.5 { down + 1.0 } else { down }
}

/// [`norm_gain_mb`] in dB.
pub fn norm_gain_db(target_lufs: i32, loudness_db: f32) -> f32 {
    norm_gain_mb(target_lufs, loudness_db as f64) as f32 / 100.0
}

#[cfg(test)]
mod tests {
    #[test]
    fn agrees_with_the_shared_loudness_gain_cases() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../contracts/loudness-gain.json")).unwrap();
        for c in doc["cases"].as_array().unwrap() {
            let got = super::norm_gain_mb(
                c["targetLufs"].as_i64().unwrap() as i32,
                c["loudnessDb"].as_f64().unwrap(),
            );
            let want = (c["gainDb"].as_f64().unwrap() * 100.0).round() as i32;
            assert_eq!(got, want, "{}", c["name"]);
        }
    }

    #[test]
    fn rounds_half_up_like_android_and_survives_nan() {
        assert_eq!(super::norm_gain_mb(-14, 0.005), 0); // -0.5 mb rounds up to 0, not away from zero
        assert_eq!(super::round_half_up(0.5f64.next_down()), 0.0);
        assert_eq!(super::round_half_up(-2.5), -2.0);
        assert_eq!(super::norm_gain_mb(-14, f64::NAN), 0);
        assert_eq!(super::norm_gain_mb(-14, f64::NEG_INFINITY), 300);
    }
}
