// Quran-first content preference ("Worship mode").
//
// YouTube Music builds the Home feed and autoplay radio from the signed-in
// account's watch history, and the innertube API offers no server-side way to
// ask for worship-only content. Worship mode therefore filters on the client:
// explicit tracks are dropped and only items that look like Quran recitation,
// dua, adhkar or Islamic talks are kept. The same keyword lists are mirrored
// on every platform (Android/iOS Kotlin, desktop Rust, web TypeScript).

import type { Shelf, Track } from './types';

const ARABIC_TERMS = [
  'قرآن', 'قران', 'سورة', 'سوره', 'تلاوة', 'تلاوه', 'تجويد', 'ترتيل',
  'دعاء', 'أدعية', 'ادعيه', 'أذكار', 'اذكار', 'تسبيح', 'رقية',
  'حديث', 'تفسير', 'محاضرة', 'محاضره', 'خطبة', 'خطب', 'دروس', 'سيرة',
  'فتوى', 'فتاوى', 'أذان', 'اذان', 'رمضان', 'الشيخ', 'قارئ',
];

const LATIN_TERMS = /\b(quran|qur'an|qoran|koran|surah|surat|juz|ayah|aya|recitation|reciter|tilawat|tajweed|tarteel|sheikh|shaykh|sheik|islam|islamic|muslim|dua|du'a|adhkar|azkar|dhikr|zikr|tasbih|ruqyah|hadith|tafsir|tafseer|khutbah|khutba|dars|lecture|seerah|sira|adhan|azan|ramadan|fatwa)\b/i;

export function isWorship(title: string, artist: string): boolean {
  const text = `${title} ${artist}`;
  return LATIN_TERMS.test(text) || ARABIC_TERMS.some((term) => text.includes(term));
}

/** In worship mode: drop explicit tracks and keep worship items only. Off: pass through. */
export function keepTrack(explicit: boolean, worshipOn: boolean, title: string, artist: string): boolean {
  return !worshipOn || (!explicit && isWorship(title, artist));
}

const artistNames = (artists: { name: string }[]) => artists.map((a) => a.name).join(', ');

export function filterTracks(tracks: Track[], worshipOn: boolean): Track[] {
  if (!worshipOn) return tracks;
  return tracks.filter((t) => keepTrack(t.explicit, true, t.title, artistNames(t.artists)));
}

/** In worship mode: drop filtered items and any shelf left empty. Off: pass through. */
export function filterShelves(shelves: Shelf[], worshipOn: boolean): Shelf[] {
  if (!worshipOn) return shelves;
  return shelves
    .map((shelf) => ({
      ...shelf,
      items: shelf.items.filter((item) => keepTrack(item.explicit, true, item.title, artistNames(item.artists))),
    }))
    .filter((shelf) => shelf.items.length > 0);
}

/** Search query used to rebuild the queue when a radio mix has no worship items. */
export function fallbackQuery(seedTitle?: string, seedArtist?: string): string {
  return seedArtist && isWorship(seedTitle ?? '', seedArtist) ? seedArtist : 'قرآن كريم';
}
