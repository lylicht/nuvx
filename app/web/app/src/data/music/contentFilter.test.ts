import { describe, expect, it } from 'vitest';

import {
  buildProfile,
  EMPTY_PROFILE,
  fallbackQuery,
  filterShelves,
  filterTracks,
  keepTrack,
  matchesProfile,
  tokensOf,
} from './contentFilter';
import type { Shelf, Track } from './types';

const track = (videoId: string, title: string, artist: string, explicit = false): Track => ({
  videoId,
  title,
  artists: [{ name: artist, id: null }],
  album: null,
  durationSec: 180,
  explicit,
  thumbnail: null,
  index: null,
  plays: null,
});

const shelf = (title: string, tracks: Track[]): Shelf => ({
  title,
  strapline: null,
  layout: 'tracks',
  items: tracks.map((t) => ({
    kind: 'song' as const,
    id: t.videoId,
    title: t.title,
    subtitle: t.artists[0]?.name ?? '',
    artists: t.artists,
    album: null,
    thumbnail: null,
    wide: false,
    durationSec: 180,
    explicit: t.explicit,
  })),
  seeAll: null,
});

// A Quran listener's recent plays: several reciters and surah titles.
const quranPlays = [
  { title: 'سورة البقرة', artist: 'مشاري العفاسي' },
  { title: 'سورة الكهف', artist: 'عبد الباسط عبد الصمد' },
  { title: 'Surah Ar-Rahman', artist: 'Mishary Rashid Alafasy' },
  { title: 'أذكار الصباح', artist: 'مشاري العفاسي' },
];

describe('tokensOf', () => {
  it('keeps meaningful words and drops short and generic ones', () => {
    expect(tokensOf('Surah Al-Baqarah (Official Video)')).toEqual(new Set(['surah', 'baqarah']));
    expect(tokensOf('أغنية سورة الناس')).toEqual(new Set(['سورة', 'الناس']));
  });
});

describe('buildProfile + matchesProfile', () => {
  const profile = buildProfile(quranPlays);

  it('keeps the same topic across reciters via shared title vocabulary', () => {
    expect(matchesProfile('سورة يس', 'أحمد العجمي', profile)).toBe(true);
  });

  it('keeps the same artist across romanizations', () => {
    expect(matchesProfile('Beautiful Names of Allah', 'Mishary Rashid Alafasy', profile)).toBe(true);
  });

  it('drops unrelated music', () => {
    expect(matchesProfile('Blinding Lights', 'The Weeknd', profile)).toBe(false);
  });

  it('matches a podcast by its show name as artist', () => {
    const podcasts = buildProfile([{ title: 'Episode 500: Investing', artist: 'The Tim Ferriss Show' }]);
    expect(matchesProfile('Episode 501: Habits', 'The Tim Ferriss Show', podcasts)).toBe(true);
    expect(matchesProfile('Episode 1: Start', 'Some Other Podcast', podcasts)).toBe(false);
  });
});

describe('keepTrack', () => {
  const profile = buildProfile(quranPlays);

  it('drops explicit tracks only when hide-explicit is on', () => {
    expect(keepTrack(true, true, false, 'Anything', 'Anyone', EMPTY_PROFILE)).toBe(false);
    expect(keepTrack(true, false, false, 'Anything', 'Anyone', EMPTY_PROFILE)).toBe(true);
  });

  it('applies the topical filter only when focused and the profile has signal', () => {
    expect(keepTrack(false, false, true, 'Blinding Lights', 'The Weeknd', profile)).toBe(false);
    expect(keepTrack(false, false, true, 'سورة الملك', 'إدريس أبكر', profile)).toBe(true);
    expect(keepTrack(false, false, true, 'Blinding Lights', 'The Weeknd', EMPTY_PROFILE)).toBe(true);
    expect(keepTrack(false, false, false, 'Blinding Lights', 'The Weeknd', profile)).toBe(true);
  });
});

describe('filterTracks / filterShelves', () => {
  const profile = buildProfile(quranPlays);

  it('passes through unchanged when both filters are off', () => {
    const tracks = [track('a', 'Blinding Lights', 'The Weeknd', true)];
    expect(filterTracks(tracks, false, false, profile)).toBe(tracks);
  });

  it('drops emptied shelves', () => {
    const shelves = [
      shelf('Top hits', [track('a', 'Blinding Lights', 'The Weeknd', true)]),
      shelf('Recitations', [track('b', 'سورة الفاتحة', 'ماهر المعيقلي')]),
    ];
    const filtered = filterShelves(shelves, true, true, profile);
    expect(filtered.map((s) => s.title)).toEqual(['Recitations']);
    expect(filtered[0]?.items.map((i) => i.title)).toEqual(['سورة الفاتحة']);
  });
});

describe('fallbackQuery', () => {
  it('searches for more like the seed', () => {
    expect(fallbackQuery('سورة البقرة', 'مشاري العفاسي')).toBe('مشاري العفاسي سورة البقرة');
    expect(fallbackQuery('Song', undefined)).toBe('Song');
    expect(fallbackQuery(undefined, 'Artist')).toBe('Artist');
    expect(fallbackQuery('  ', ' ')).toBeNull();
    expect(fallbackQuery()).toBeNull();
  });
});
