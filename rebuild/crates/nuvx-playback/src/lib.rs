//! Queue and playback rules from spec 02 §2–§3. Pure logic: what plays next, never how it sounds.

/// Repeat mode (spec 01 §2.3, spec 02 §2).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Repeat {
    #[default]
    Off,
    All,
    One,
}

impl Repeat {
    /// Off → all → one → off.
    pub fn next(self) -> Self {
        match self {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        }
    }
}

/// Going back this far into a track restarts it instead of going to the previous one (spec 02 §2).
pub const RESTART_AFTER_MS: u64 = 3_000;

/// What the player should do after a queue move.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    /// Start the item at this position from the beginning.
    Play(usize),
    /// Restart the current item.
    Restart,
    /// Stop: the end of the queue was reached (or it is empty).
    Stop,
    /// Nothing changes for what is playing.
    Continue,
}

/// The play queue: items and the current position.
#[derive(Clone, Debug, PartialEq)]
pub struct Queue<T> {
    items: Vec<T>,
    current: Option<usize>,
}

impl<T> Default for Queue<T> {
    fn default() -> Self {
        Queue {
            items: Vec::new(),
            current: None,
        }
    }
}

impl<T> Queue<T> {
    /// A queue of `items` positioned at `start` (clamped); empty when `items` is.
    pub fn new(items: Vec<T>, start: usize) -> Self {
        let current = (!items.is_empty()).then(|| start.min(items.len() - 1));
        Queue { items, current }
    }

    pub fn items(&self) -> &[T] {
        &self.items
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn position(&self) -> Option<usize> {
        self.current
    }

    pub fn current(&self) -> Option<&T> {
        self.current.map(|i| &self.items[i])
    }

    /// The items after the current one.
    pub fn upcoming(&self) -> &[T] {
        match self.current {
            Some(i) => &self.items[i + 1..],
            None => &self.items,
        }
    }

    /// Next button: the next item, wrapping only with repeat all (repeat one does not hold a manual skip).
    pub fn next(&mut self, repeat: Repeat) -> Step {
        let Some(i) = self.current else {
            return Step::Stop;
        };
        if i + 1 < self.items.len() {
            self.current = Some(i + 1);
            Step::Play(i + 1)
        } else if repeat == Repeat::All {
            self.current = Some(0);
            Step::Play(0)
        } else {
            Step::Stop
        }
    }

    /// End of the current track.
    pub fn finished(&mut self, repeat: Repeat) -> Step {
        if repeat == Repeat::One && self.current.is_some() {
            Step::Restart
        } else {
            self.next(repeat)
        }
    }

    /// Previous button, `elapsed_ms` into the current track.
    pub fn previous(&mut self, elapsed_ms: u64) -> Step {
        match self.current {
            None => Step::Stop,
            Some(i) if elapsed_ms > RESTART_AFTER_MS || i == 0 => Step::Restart,
            Some(i) => {
                self.current = Some(i - 1);
                Step::Play(i - 1)
            }
        }
    }

    /// Plays the item at `index` (an Up Next click); the queue stays as it is.
    pub fn jump(&mut self, index: usize) -> Step {
        if index < self.items.len() {
            self.current = Some(index);
            Step::Play(index)
        } else {
            Step::Stop
        }
    }

    /// Inserts `item` right after the current one (at the front when nothing is current).
    pub fn play_next(&mut self, item: T) {
        let at = self.current.map_or(0, |i| i + 1);
        self.items.insert(at, item);
    }

    /// Appends `item`.
    pub fn add(&mut self, item: T) {
        self.items.push(item);
    }

    /// Removes the item at `index`. Removing the current item moves on to the one after it.
    pub fn remove(&mut self, index: usize) -> Step {
        if index >= self.items.len() {
            return Step::Continue;
        }
        self.items.remove(index);
        let Some(i) = self.current else {
            return Step::Continue;
        };
        if index < i {
            self.current = Some(i - 1);
            Step::Continue
        } else if index > i {
            Step::Continue
        } else if index < self.items.len() {
            Step::Play(index)
        } else {
            self.current = self.items.len().checked_sub(1);
            Step::Stop
        }
    }

    /// Moves the item at `from` to `to`, keeping the current item current.
    pub fn move_item(&mut self, from: usize, to: usize) {
        if from >= self.items.len() || to >= self.items.len() || from == to {
            return;
        }
        let item = self.items.remove(from);
        self.items.insert(to, item);
        if let Some(i) = self.current {
            self.current = Some(if i == from {
                to
            } else if from < i && to >= i {
                i - 1
            } else if from > i && to <= i {
                i + 1
            } else {
                i
            });
        }
    }

    /// Removes every item after the current one.
    pub fn clear_upcoming(&mut self) {
        match self.current {
            Some(i) => self.items.truncate(i + 1),
            None => self.items.clear(),
        }
    }

    /// Shuffle up next: reorders the items after the current one; `seed` drives the order.
    pub fn shuffle_upcoming(&mut self, seed: u64) {
        let start = self.current.map_or(0, |i| i + 1);
        let tail = &mut self.items[start..];
        let mut state = seed | 1;
        for i in (1..tail.len()).rev() {
            // xorshift64: enough for shuffling a play queue; not for anything that needs real randomness
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            tail.swap(i, (state % (i as u64 + 1)) as usize);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn q(start: usize) -> Queue<u32> {
        Queue::new(vec![1, 2, 3, 4, 5], start)
    }

    #[test]
    fn next_and_previous_follow_spec_02() {
        let mut q = q(2);
        assert_eq!(q.current(), Some(&3));
        assert_eq!(q.next(Repeat::Off), Step::Play(3));
        assert_eq!(q.previous(1_000), Step::Play(2));
        assert_eq!(q.previous(3_001), Step::Restart);
        assert_eq!(q.position(), Some(2));
        let mut first = Queue::new(vec![1, 2], 0);
        assert_eq!(first.previous(0), Step::Restart);
    }

    #[test]
    fn end_of_queue_by_repeat_mode() {
        let mut off = q(4);
        assert_eq!(off.finished(Repeat::Off), Step::Stop);
        assert_eq!(off.position(), Some(4), "the last track stays shown");
        let mut all = q(4);
        assert_eq!(all.finished(Repeat::All), Step::Play(0));
        let mut one = q(4);
        assert_eq!(one.finished(Repeat::One), Step::Restart);
        assert_eq!(
            one.next(Repeat::One),
            Step::Stop,
            "a manual skip is not held by repeat one"
        );
    }

    #[test]
    fn play_next_add_and_clear() {
        let mut q = q(1);
        q.play_next(9);
        assert_eq!(q.items(), [1, 2, 9, 3, 4, 5]);
        q.add(8);
        assert_eq!(q.upcoming(), [9, 3, 4, 5, 8]);
        q.clear_upcoming();
        assert_eq!(q.items(), [1, 2]);
    }

    #[test]
    fn removing_items() {
        let mut q = q(2);
        assert_eq!(q.remove(0), Step::Continue);
        assert_eq!((q.items(), q.current()), (&[2, 3, 4, 5][..], Some(&3)));
        assert_eq!(
            q.remove(1),
            Step::Play(1),
            "removing the current item plays the next"
        );
        assert_eq!(q.current(), Some(&4));
        let mut last = Queue::new(vec![1, 2], 1);
        assert_eq!(last.remove(1), Step::Stop);
        assert_eq!(last.current(), Some(&1));
    }

    #[test]
    fn moving_keeps_the_current_item() {
        let mut q = q(2);
        q.move_item(0, 4);
        assert_eq!((q.items(), q.current()), (&[2, 3, 4, 5, 1][..], Some(&3)));
        q.move_item(4, 0);
        assert_eq!(q.current(), Some(&3));
        q.move_item(2, 0);
        assert_eq!((q.position(), q.current()), (Some(0), Some(&3)));
    }

    #[test]
    fn shuffle_up_next_keeps_current_and_the_same_items() {
        let mut q = Queue::new((0..20).collect::<Vec<u32>>(), 5);
        q.shuffle_upcoming(42);
        assert_eq!(q.current(), Some(&5));
        assert_eq!(&q.items()[..6], &[0, 1, 2, 3, 4, 5]);
        let mut rest = q.upcoming().to_vec();
        assert_ne!(rest, (6..20).collect::<Vec<_>>(), "order changed");
        rest.sort();
        assert_eq!(rest, (6..20).collect::<Vec<_>>());
    }

    #[test]
    fn empty_queue_does_nothing() {
        let mut q: Queue<u32> = Queue::new(vec![], 3);
        assert_eq!(q.current(), None);
        assert_eq!(q.next(Repeat::All), Step::Stop);
        assert_eq!(q.previous(0), Step::Stop);
    }

    #[test]
    fn repeat_cycles() {
        assert_eq!(Repeat::Off.next().next().next(), Repeat::Off);
    }
}
