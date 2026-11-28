//! Instrumental gaps, Android's rule (core/contracts/lyrics-gaps.json). Timed lines: a gap at 0 when the first
//! line starts 4 s in or later, and at a line's sung end when that end is known and 4 s or more pass before the
//! next line; none at the end of the song. Line-synced LRC has its own rule in `lrc::parse_lrc`.

use super::model::Line;

pub const MIN_GAP_MS: i32 = 4000;

pub(crate) fn gap(start: i32, dur: i32) -> Line {
    Line {
        start,
        dur,
        instrumental: true,
        ..Default::default()
    }
}

/// Where a line stops being sung, if known: its last sung word (or its own end when it has no words),
/// extended by background vocals. Unknown for a line with neither words nor a duration.
fn sung_end(l: &Line) -> Option<i32> {
    let lead = match l.parts.iter().rfind(|p| !p.bg && !p.text.trim().is_empty()) {
        Some(p) => p.start + p.dur,
        None if l.dur > 0 => l.end(),
        None => return None,
    };
    let background = l
        .parts
        .iter()
        .filter(|p| p.bg)
        .map(|p| p.start + p.dur)
        .max();
    Some(background.map_or(lead, |b| b.max(lead)))
}

pub fn insert_instrumentals(lines: Vec<Line>) -> Vec<Line> {
    let Some(first) = lines.first() else {
        return lines;
    };
    let mut out = Vec::with_capacity(lines.len() + 4);
    if first.start >= MIN_GAP_MS {
        out.push(gap(0, first.start));
    }
    for (i, l) in lines.iter().enumerate() {
        out.push(l.clone());
        let (Some(next), Some(end)) = (lines.get(i + 1), sung_end(l)) else {
            continue;
        };
        if end > l.start && next.start - end >= MIN_GAP_MS {
            out.push(gap(end, next.start - end));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::super::model::{Line, Part};

    #[test]
    fn agrees_with_the_shared_lyrics_gap_cases() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../../contracts/lyrics-gaps.json")).unwrap();
        for c in doc["timed"].as_array().unwrap() {
            let lines = c["lines"]
                .as_array()
                .unwrap()
                .iter()
                .map(|l| {
                    let start = l["start"].as_i64().unwrap() as i32;
                    let end = l["end"].as_i64().map(|e| e as i32);
                    let parts = l["backgroundEnd"]
                        .as_i64()
                        .map(|b| Part {
                            start,
                            dur: b as i32 - start,
                            text: "(bg)".into(),
                            bg: true,
                        })
                        .into_iter()
                        .collect();
                    Line {
                        start,
                        dur: end.map_or(0, |e| e - start),
                        text: l["text"].as_str().unwrap().into(),
                        parts,
                        ..Default::default()
                    }
                })
                .collect();
            let gaps: Vec<i64> = super::insert_instrumentals(lines)
                .iter()
                .filter(|l| l.instrumental)
                .map(|l| l.start as i64)
                .collect();
            let want: Vec<i64> = c["gaps"]
                .as_array()
                .unwrap()
                .iter()
                .map(|g| g.as_i64().unwrap())
                .collect();
            assert_eq!(gaps, want, "{}", c["name"]);
        }
        for c in doc["lrc"].as_array().unwrap() {
            let got: Vec<(i64, String)> =
                super::super::lrc::parse_lrc(c["lrc"].as_str().unwrap(), 300_000)
                    .into_iter()
                    .map(|l| (l.start as i64, l.text))
                    .collect();
            let want: Vec<(i64, String)> = c["lines"]
                .as_array()
                .unwrap()
                .iter()
                .map(|l| (l[0].as_i64().unwrap(), l[1].as_str().unwrap().to_owned()))
                .collect();
            assert_eq!(got, want, "{}", c["name"]);
        }
    }
}
