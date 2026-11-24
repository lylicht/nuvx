//! Android's word model (core/contracts/lyrics-words.json): how the timed runs of a line become words, and the
//! syllables inside a word. Runs touching with no whitespace between join into one word only when the line uses
//! spaces at all (or spacing is explicit) and neither touching character is from a script written without spaces.

#[derive(Clone, Debug, PartialEq)]
pub struct Syllable {
    pub start: i32,
    pub end: i32,
    /// UTF-16 offsets into the word's text, like Android's strings.
    pub start_char: usize,
    pub end_char: usize,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Word {
    pub start: i32,
    pub end: i32,
    pub text: String,
    /// Empty unless the word has two or more timed runs.
    pub syllables: Vec<Syllable>,
}

/// CJK, kana, CJK punctuation, full-width forms and Thai: written without spaces, so never joined into words.
pub fn unspaced_script(c: char) -> bool {
    matches!(c, '\u{3400}'..='\u{9FFF}' | '\u{3040}'..='\u{30FF}' | '\u{3000}'..='\u{303F}' | '\u{FF00}'..='\u{FFEF}' | '\u{0E00}'..='\u{0E7F}')
}

/// Whether a run touching the previous one (no whitespace between) continues its word. `glue` is true when the
/// line uses spaces at all or spacing is explicit.
pub fn continues_word(glue: bool, prev: char, next: char) -> bool {
    glue && !unspaced_script(prev) && !unspaced_script(next)
}

fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

fn syllables(mut runs: Vec<Syllable>, len: usize) -> Vec<Syllable> {
    runs.retain(|s| s.end_char > s.start_char && s.start_char < len);
    runs.sort_by_key(|s| s.start_char);
    if runs.len() < 2 {
        return Vec::new();
    }
    let ends: Vec<usize> = runs
        .iter()
        .skip(1)
        .map(|s| s.start_char)
        .chain([len])
        .collect();
    runs.into_iter()
        .zip(ends)
        .enumerate()
        .map(|(i, (s, end_char))| Syllable {
            start: s.start,
            end: s.end.max(s.start),
            start_char: if i == 0 { 0 } else { s.start_char },
            end_char,
        })
        .collect()
}

/// Words from `(start_ms, end_ms, raw text)` runs.
pub fn words_from_runs(runs: &[(i32, i32, &str)], explicit_spacing: bool) -> Vec<Word> {
    let glue = explicit_spacing || runs.iter().any(|r| r.2.chars().any(char::is_whitespace));
    let mut words = Vec::new();
    let mut text = String::new();
    let mut pieces: Vec<Syllable> = Vec::new();
    let (mut start, mut end) = (0, 0);
    let mut flush = |text: &mut String, pieces: &mut Vec<Syllable>, start: i32, end: i32| {
        if !text.is_empty() {
            let len = utf16_len(text);
            words.push(Word {
                start,
                end: end.max(start),
                text: std::mem::take(text),
                syllables: syllables(std::mem::take(pieces), len),
            });
        }
        text.clear();
        pieces.clear();
    };
    for &(run_start, run_end, raw) in runs {
        let content = raw.trim();
        let Some(first) = content.chars().next() else {
            flush(&mut text, &mut pieces, start, end);
            continue;
        };
        let joins = text.chars().next_back().is_some_and(|prev| {
            !raw.starts_with(char::is_whitespace) && continues_word(glue, prev, first)
        });
        if !joins {
            flush(&mut text, &mut pieces, start, end);
        }
        if text.is_empty() {
            (start, end) = (run_start, run_start);
        }
        let from = utf16_len(&text);
        text.push_str(content);
        let run_end = run_end.max(run_start);
        pieces.push(Syllable {
            start: run_start,
            end: run_end,
            start_char: from,
            end_char: utf16_len(&text),
        });
        end = end.max(run_end);
        if !glue || raw.ends_with(char::is_whitespace) {
            flush(&mut text, &mut pieces, start, end);
        }
    }
    flush(&mut text, &mut pieces, start, end);
    words
}

/// A line's sung words (`background` false) or background-vocal words (`background` true), from its timed parts.
pub fn line_words(line: &super::model::Line, background: bool) -> Vec<Word> {
    let runs: Vec<(i32, i32, &str)> = line
        .parts
        .iter()
        .filter(|p| p.bg == background)
        .map(|p| (p.start, p.start + p.dur, p.text.as_str()))
        .collect();
    words_from_runs(&runs, line.explicit_spacing)
}

#[cfg(test)]
mod tests {
    #[test]
    fn agrees_with_the_shared_lyrics_word_cases() {
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../../contracts/lyrics-words.json")).unwrap();
        for c in doc["cases"].as_array().unwrap() {
            let runs: Vec<(i32, i32, &str)> = c["runs"]
                .as_array()
                .unwrap()
                .iter()
                .map(|r| {
                    (
                        r[0].as_i64().unwrap() as i32,
                        r[1].as_i64().unwrap() as i32,
                        r[2].as_str().unwrap(),
                    )
                })
                .collect();
            let got =
                super::words_from_runs(&runs, c["explicitSpacing"].as_bool().unwrap_or(false));
            let want: Vec<super::Word> = c["words"]
                .as_array()
                .unwrap()
                .iter()
                .map(|w| super::Word {
                    start: w["start"].as_i64().unwrap() as i32,
                    end: w["end"].as_i64().unwrap() as i32,
                    text: w["text"].as_str().unwrap().into(),
                    syllables: w["syllables"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|s| super::Syllable {
                            start: s[0].as_i64().unwrap() as i32,
                            end: s[1].as_i64().unwrap() as i32,
                            start_char: s[2].as_u64().unwrap() as usize,
                            end_char: s[3].as_u64().unwrap() as usize,
                        })
                        .collect(),
                })
                .collect();
            assert_eq!(got, want, "{}", c["name"]);
        }
    }
}
