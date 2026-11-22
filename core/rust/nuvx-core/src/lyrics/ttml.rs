use std::collections::HashMap;

use super::credits::dedup;
use super::model::{Line, Part};
use super::timeline::insert_instrumentals;

pub struct Ttml {
    pub lines: Vec<Line>,
    pub word_synced: bool,
    pub language: Option<String>,
    pub songwriters: Vec<String>,
}

pub fn ttml_time(t: &str) -> i32 {
    let t = t.trim();
    if t.is_empty() {
        return 0;
    }
    for (suffix, mul) in [
        ("ms", 1.0),
        ("h", 3_600_000.0),
        ("m", 60_000.0),
        ("s", 1000.0),
    ] {
        if let Some(n) = t.strip_suffix(suffix)
            && !n.is_empty()
            && n.bytes().all(|b| b.is_ascii_digit() || b == b'.')
        {
            return (n.parse::<f64>().unwrap_or(0.0) * mul).round() as i32;
        }
    }
    let s: Vec<f64> = t
        .split(':')
        .map(|p| {
            p.chars()
                .filter(|c| c.is_ascii_digit() || *c == '.')
                .collect::<String>()
                .parse::<f64>()
                .unwrap_or(0.0)
        })
        .collect();
    let ms = match s.len() {
        1 => s[0] * 1000.0,
        2 => s[0].trunc() * 60_000.0 + s[1] * 1000.0,
        3 => s[0].trunc() * 3_600_000.0 + s[1].trunc() * 60_000.0 + s[2] * 1000.0,
        _ => 0.0,
    };
    ms.round() as i32
}

fn bind_prefixes(t: &str) -> String {
    let Some(start) = t.find("<tt") else {
        return t.to_owned();
    };
    let Some(len) = t[start..].find('>') else {
        return t.to_owned();
    };
    let tag = &t[start..start + len + 1];
    let mut declared: Vec<String> = vec!["xml".into(), "xmlns".into()];
    for (i, _) in tag.match_indices("xmlns:") {
        let name: String = tag[i + 6..]
            .chars()
            .take_while(|c| c.is_alphanumeric() || matches!(c, '_' | '.' | '-'))
            .collect();
        declared.push(name);
    }
    let mut used: Vec<String> = Vec::new();
    let bytes: Vec<char> = t.chars().collect();
    let mut i = 0;
    while i < bytes.len() {
        let at_tag = bytes[i] == '<' || (bytes[i] == '/' && i > 0 && bytes[i - 1] == '<');
        let at_attr = bytes[i].is_whitespace();
        if at_tag || at_attr {
            let mut j = i + 1;
            let mut name = String::new();
            while j < bytes.len()
                && (bytes[j].is_alphanumeric() || matches!(bytes[j], '_' | '.' | '-'))
            {
                name.push(bytes[j]);
                j += 1;
            }
            if !name.is_empty()
                && name.starts_with(|c: char| c.is_ascii_alphabetic())
                && j < bytes.len()
                && bytes[j] == ':'
            {
                let ok = if at_attr {
                    let mut k = j + 1;
                    while k < bytes.len()
                        && (bytes[k].is_alphanumeric() || matches!(bytes[k], '_' | '.' | '-'))
                    {
                        k += 1;
                    }
                    while k < bytes.len() && bytes[k].is_whitespace() {
                        k += 1;
                    }
                    k < bytes.len() && bytes[k] == '='
                } else {
                    true
                };
                if ok && !used.contains(&name) {
                    used.push(name);
                }
            }
        }
        i += 1;
    }
    let missing: Vec<&String> = used.iter().filter(|u| !declared.contains(u)).collect();
    if missing.is_empty() {
        return t.to_owned();
    }
    let extra: String = missing
        .iter()
        .map(|m| format!(" xmlns:{m}=\"urn:braccato:unbound:{m}\""))
        .collect();
    let new_tag = format!("{}{}>", &tag[..tag.len() - 1], extra);
    t.replacen(tag, &new_tag, 1)
}

fn attr<'a>(n: roxmltree::Node<'a, '_>, name: &str) -> Option<&'a str> {
    n.attributes().find(|a| a.name() == name).map(|a| a.value())
}

fn span_text(n: roxmltree::Node) -> String {
    let mut s = String::new();
    for c in n.children() {
        if let Some(t) = c.text().filter(|_| c.is_text()) {
            s.push_str(t);
        } else if c.is_element() && c.tag_name().name() == "span" {
            s.push_str(&span_text(c));
        }
    }
    s
}

fn ttml_parts(node: roxmltree::Node, line_start: i32) -> (Vec<Part>, String, bool) {
    let mut s = String::new();
    let mut e: Vec<Part> = Vec::new();
    let mut timed = false;
    for u in node.children() {
        let bg = u.is_element() && attr(u, "role") == Some("x-bg");
        let group: Vec<roxmltree::Node> = if bg { u.children().collect() } else { vec![u] };
        for m in group {
            if m.is_text() {
                let t = m.text().unwrap_or_default();
                if t.is_empty() {
                    continue;
                }
                if !bg {
                    s.push_str(t);
                }
                let start = e.last().map_or(line_start, |p| p.start + p.dur);
                e.push(Part {
                    start,
                    dur: 0,
                    text: t.to_owned(),
                    bg,
                });
            } else if m.is_element() && m.tag_name().name() == "span" {
                // Inline translations and romanizations are not sung text (Android skips them too).
                if matches!(attr(m, "role"), Some("x-translation" | "x-roman")) {
                    continue;
                }
                let p = span_text(m);
                if p.is_empty() {
                    continue;
                }
                let b = ttml_time(attr(m, "begin").unwrap_or_default());
                let h = ttml_time(attr(m, "end").unwrap_or_default());
                e.push(Part {
                    start: b,
                    dur: h - b,
                    text: p.clone(),
                    bg,
                });
                if !bg {
                    s.push_str(&p);
                }
                timed = true;
            }
        }
    }
    if !timed {
        e.clear();
    }
    (e, s, timed)
}

/// Until when a TTML line without timed words is sung (its end), for apps that track line ends.
pub fn sung_until(l: &Line) -> Option<i32> {
    (!l.instrumental && !l.parts.iter().any(|p| !p.bg) && l.dur > 0).then(|| l.end())
}

pub fn parse_ttml(text: &str) -> Ttml {
    let empty = |language| Ttml {
        lines: vec![],
        word_synced: false,
        language,
        songwriters: vec![],
    };
    let src = bind_prefixes(&text.replace("\\\"", "\""));
    let Ok(doc) = roxmltree::Document::parse_with_options(
        &src,
        roxmltree::ParsingOptions {
            allow_dtd: true,
            ..Default::default()
        },
    ) else {
        return empty(None);
    };
    let tt = doc.root_element();
    if tt.tag_name().name() != "tt" {
        return empty(None);
    }
    let body = tt.children().find(|n| n.tag_name().name() == "body");
    let language = attr(tt, "lang")
        .or_else(|| body.and_then(|b| attr(b, "lang")))
        .map(str::to_owned);
    let Some(body) = body else {
        return empty(language);
    };
    let head_meta = tt
        .children()
        .find(|n| n.tag_name().name() == "head")
        .and_then(|h| h.children().find(|n| n.tag_name().name() == "metadata"));
    let mut agents: HashMap<String, String> = HashMap::new();
    if let Some(m) = head_meta {
        let mut k = 0;
        for a in m.children().filter(|n| n.tag_name().name() == "agent") {
            let Some(id) = attr(a, "id") else { continue };
            // Android's singer types: a group, another voice, or (any other type) a person; untyped keeps its id.
            match attr(a, "type").unwrap_or_default() {
                "" => {}
                "group" => {
                    agents.insert(id.to_owned(), "v1000".to_owned());
                }
                "other" => {
                    agents.insert(id.to_owned(), "v2000".to_owned());
                }
                _ => {
                    k += 1;
                    agents.insert(id.to_owned(), format!("v{k}"));
                }
            }
        }
    }
    let ps: Vec<roxmltree::Node> = body
        .children()
        .filter(|n| n.tag_name().name() == "div")
        .flat_map(|d| d.children().filter(|n| n.tag_name().name() == "p"))
        .collect();
    if ps.is_empty() {
        return empty(language);
    }
    let mut lines: Vec<Line> = Vec::new();
    let mut keys: HashMap<String, Vec<usize>> = HashMap::new();
    let mut word_synced = false;
    for p in ps {
        let Some(begin) = attr(p, "begin") else {
            continue;
        };
        let begin = ttml_time(begin);
        let end = ttml_time(attr(p, "end").unwrap_or_default());
        let (parts, text, timed) = ttml_parts(p, begin);
        let text = text.split_whitespace().collect::<Vec<_>>().join(" ");
        if text.trim().is_empty() && parts.is_empty() {
            continue;
        }
        let start = parts
            .iter()
            .filter(|p| !p.bg)
            .map(|p| p.start)
            .fold(begin, i32::min);
        word_synced |= timed;
        let agent =
            attr(p, "agent").map(|a| agents.get(a).cloned().unwrap_or_else(|| a.to_owned()));
        if let Some(k) = attr(p, "key") {
            keys.entry(k.to_owned()).or_default().push(lines.len());
        }
        lines.push(Line {
            start,
            dur: end - start,
            text,
            parts,
            agent,
            explicit_spacing: true,
            ..Default::default()
        });
    }
    let meta_root = head_meta
        .and_then(|m| {
            m.descendants()
                .find(|n| n.tag_name().name() == "iTunesMetadata")
        })
        .or(head_meta);
    if let Some(m) = meta_root {
        if let Some(ts) = m
            .descendants()
            .find(|n| n.tag_name().name() == "translations")
        {
            let default_lang = attr(ts, "lang");
            for t in ts
                .children()
                .filter(|n| n.tag_name().name() == "translation")
            {
                let lang = attr(t, "lang").or(default_lang);
                for item in t.children().filter(|n| n.is_element()) {
                    let Some(key) = attr(item, "for").or_else(|| attr(t, "for")) else {
                        continue;
                    };
                    let txt = span_text(item);
                    let (Some(lang), false) = (lang, txt.is_empty()) else {
                        continue;
                    };
                    for &i in keys.get(key).map(Vec::as_slice).unwrap_or_default() {
                        lines[i].translations.push((lang.to_owned(), txt.clone()));
                    }
                }
            }
        }
        if let Some(ts) = m
            .descendants()
            .find(|n| n.tag_name().name() == "transliterations")
        {
            for t in ts
                .children()
                .filter(|n| n.tag_name().name() == "transliteration")
            {
                for item in t.children().filter(|n| n.is_element()) {
                    let Some(key) = attr(item, "for") else {
                        continue;
                    };
                    for &i in keys.get(key).map(Vec::as_slice).unwrap_or_default() {
                        let (parts, text, _) = ttml_parts(item, lines[i].start);
                        if text.is_empty() {
                            continue;
                        }
                        lines[i].romanization = Some(text);
                        lines[i].timed_roman = parts;
                    }
                }
            }
        }
    }
    let songwriters = {
        let mut n = Vec::new();
        if let Some(m) = head_meta {
            if let Some(sw) = m
                .descendants()
                .find(|n| n.tag_name().name() == "songwriters")
            {
                for w in sw
                    .children()
                    .filter(|n| n.tag_name().name() == "songwriter")
                {
                    n.push(span_text(w));
                }
            }
            for meta in m.children().filter(|n| n.tag_name().name() == "meta") {
                if matches!(attr(meta, "key"), Some("songwriter" | "songwriters"))
                    && let Some(v) = attr(meta, "value")
                {
                    n.push(v.to_owned());
                }
            }
        }
        dedup(n)
    };
    lines.sort_by_key(|l| l.start);
    super::sides::assign_sides(&mut lines);
    Ttml {
        lines: insert_instrumentals(lines),
        word_synced,
        language,
        songwriters,
    }
}

#[cfg(test)]
mod ttml_contract_tests {
    #[test]
    fn agrees_with_the_shared_ttml_cases() {
        use super::super::words::line_words;
        let doc: serde_json::Value =
            serde_json::from_str(include_str!("../../../../contracts/lyrics-ttml.json")).unwrap();
        for c in doc["cases"].as_array().unwrap() {
            let got: Vec<serde_json::Value> = super::parse_ttml(c["ttml"].as_str().unwrap())
                .lines
                .iter()
                .map(|l| {
                    let words: Vec<_> = line_words(l, false)
                        .into_iter()
                        .map(|w| serde_json::json!([w.text, w.start, w.end, w.syllables.len()]))
                        .collect();
                    let bg: Vec<_> =
                        line_words(l, true).into_iter().map(|w| serde_json::json!([w.text, w.start, w.end])).collect();
                    serde_json::json!({
                        "start": l.start, "text": l.text, "align": if l.right { "end" } else { "start" },
                        "words": words, "background": bg, "sungUntil": super::sung_until(l),
                    })
                })
                .collect();
            assert_eq!(&serde_json::Value::Array(got), &c["lines"], "{}", c["name"]);
        }
    }
}
