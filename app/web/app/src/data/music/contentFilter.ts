// Client-side content controls for Home and autoplay.
//
// YouTube Music builds the Home feed and autoplay radio from the signed-in
// account's watch history, and the innertube API offers no server-side way to
// steer recommendations. These filters therefore run on the client:
//
// - "Hide explicit content" drops anything carrying YouTube's explicit badge.
// - "Keep recommendations on-topic" keeps items that match a taste profile
//   built from the user's own recent plays (the artists they listen to and
//   the vocabulary in those titles) plus, for autoplay, the seed track. A
//   Quran listener keeps getting recitations, a podcast listener stays on
//   their shows, and so on. It is a heuristic over titles and artist names,
//   not a content classifier: it can keep an off-topic item with familiar
//   wording and drop an on-topic item phrased differently. With no listening
//   history the profile is empty and the topical filter is a no-op.
//
// The same rules are mirrored on every platform (Android/iOS Kotlin, desktop
// Rust, web TypeScript).

import type { Shelf, Track } from './types';

export interface TasteProfile {
  artists: ReadonlySet<string>;
  tokens: ReadonlySet<string>;
}

export const EMPTY_PROFILE: TasteProfile = { artists: new Set(), tokens: new Set() };

const MIN_TOKEN_LENGTH = 4;
const MAX_ARTISTS = 40;
const MAX_TOKENS = 120;

// Words too generic to say anything about a topic ("Song (Official Video)").
const STOP_TOKENS = new Set([
  'official', 'video', 'lyric', 'lyrics', 'audio', 'remix', 'cover', 'live',
  'feat', 'featuring', 'instrumental', 'karaoke', 'version', 'clip', 'full',
  'album', 'single', 'music', 'song', 'track', 'episode', 'فيديو', 'كليب', 'اغنية', 'أغنية',
]);

const normalize = (text: string): string => text.toLowerCase().trim().replace(/\s+/g, ' ');

export function tokensOf(text: string): Set<string> {
  const tokens = new Set<string>();
  for (const token of text.toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (token.length >= MIN_TOKEN_LENGTH && !STOP_TOKENS.has(token)) tokens.add(token);
  }
  return tokens;
}

const profileIsEmpty = (profile: TasteProfile): boolean => profile.artists.size === 0 && profile.tokens.size === 0;

/** Builds a taste profile from recent plays as `{ title, artist }` pairs. */
export function buildProfile(items: Iterable<{ title: string; artist: string }>): TasteProfile {
  const artists = new Set<string>();
  const tokens = new Set<string>();
  for (const { title, artist } of items) {
    const name = normalize(artist);
    if (name && artists.size < MAX_ARTISTS) artists.add(name);
    for (const token of tokensOf(`${title} ${artist}`)) {
      if (tokens.size >= MAX_TOKENS) break;
      tokens.add(token);
    }
  }
  return { artists, tokens };
}

/** True when an item shares the profile's artists or title vocabulary. */
export function matchesProfile(title: string, artist: string, profile: TasteProfile): boolean {
  const name = normalize(artist);
  if (name) {
    for (const known of profile.artists) {
      if (known === name) return true;
      if (known.length >= MIN_TOKEN_LENGTH && name.includes(known)) return true;
      if (name.length >= MIN_TOKEN_LENGTH && known.includes(name)) return true;
    }
  }
  for (const token of tokensOf(`${title} ${artist}`)) {
    if (profile.tokens.has(token)) return true;
  }
  return false;
}

/** Applies the two content controls. An empty profile disables topical filtering. */
export function keepTrack(
  explicit: boolean,
  hideExplicit: boolean,
  focused: boolean,
  title: string,
  artist: string,
  profile: TasteProfile,
): boolean {
  if (hideExplicit && explicit) return false;
  if (focused && !profileIsEmpty(profile) && !matchesProfile(title, artist, profile)) return false;
  return true;
}

const artistNames = (artists: { name: string }[]): string => artists.map((a) => a.name).join(', ');

export function filterTracks(tracks: Track[], hideExplicit: boolean, focused: boolean, profile: TasteProfile): Track[] {
  if (!hideExplicit && !focused) return tracks;
  return tracks.filter((t) => keepTrack(t.explicit, hideExplicit, focused, t.title, artistNames(t.artists), profile));
}

/** Drops filtered items and any shelf left empty. Both filters off: pass through. */
export function filterShelves(shelves: Shelf[], hideExplicit: boolean, focused: boolean, profile: TasteProfile): Shelf[] {
  if (!hideExplicit && !focused) return shelves;
  return shelves
    .map((shelf) => ({
      ...shelf,
      items: shelf.items.filter((item) => keepTrack(item.explicit, hideExplicit, focused, item.title, artistNames(item.artists), profile)),
    }))
    .filter((shelf) => shelf.items.length > 0);
}

/** Search query used to rebuild the queue when a radio mix has nothing on-topic: more like the seed itself. */
export function fallbackQuery(seedTitle?: string, seedArtist?: string): string | null {
  const query = [seedArtist, seedTitle].filter((part) => part && part.trim()).join(' ').trim();
  return query || null;
}
