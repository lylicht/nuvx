//! Which side each lyric line sits on: Android's rule (core/contracts/lyrics-ttml.json). A change of singer flips
//! sides, the first singer starts on the left unless it is an "other" voice ("v2000"), a group ("v1000") sings on
//! the left, and a song that would sit 85 % or more on the right is mirrored.

use super::model::Line;

const GROUP: &str = "v1000";
const OTHER: &str = "v2000";
const MOSTLY_RIGHT: f32 = 0.85;

pub fn assign_sides(lines: &mut [Line]) {
    let mut left = true;
    let mut last: Option<String> = None;
    let (mut placed, mut rightward) = (0usize, 0usize);
    for l in lines.iter_mut() {
        l.right = false;
        let Some(singer) = l.agent.clone().filter(|s| !s.is_empty()) else {
            continue;
        };
        placed += 1;
        if singer == GROUP {
            continue;
        }
        match &last {
            None => left = singer != OTHER,
            Some(prev) if *prev != singer => left = !left,
            _ => {}
        }
        last = Some(singer);
        if !left {
            rightward += 1;
            l.right = true;
        }
    }
    if placed > 0 && rightward as f32 / placed as f32 >= MOSTLY_RIGHT {
        for l in lines.iter_mut() {
            l.right = !l.right;
        }
    }
}
