import { artistLine, extendStation } from '@/playback/queue/builder';
import { asEntry, type QueueEntry, type QueueSource } from '@/playback/queue/timeline';
import { trackLog } from '@/playback/trackLog';
import { buildProfile, EMPTY_PROFILE, fallbackQuery, keepTrack, type TasteProfile } from '@/data/music/contentFilter';
import type { MusicItem, Track } from '@/data/music/types';
import type { RepeatMode } from '@/state/playerSettings';

// Local copy of components/music/play.ts's toTrack, kept here to avoid a state/player import cycle.
function itemToTrack(item: MusicItem): Track {
  return {
    videoId: item.id,
    title: item.title,
    artists: item.artists,
    album: item.album,
    durationSec: item.durationSec,
    explicit: item.explicit,
    thumbnail: item.thumbnail,
    index: null,
    plays: null,
  };
}

interface PlayerAutoplayState {
  autoplay: boolean;
  hideExplicit: boolean;
  focusedFeed: boolean;
  repeat: RepeatMode;
  queue: QueueEntry[];
  index: number;
  status: string;
}

const MAX_QUEUED_AUTOPLAY = 10;
const RECENT_PROFILE_LIMIT = 50;

// Recent plays from the user's YouTube Music history, for the topical taste
// profile. Empty when unavailable, which leaves the profile anchored on the
// seed track alone.
async function recentPlays(): Promise<{ title: string; artist: string }[]> {
  try {
    const { fetchLibrary } = await import('@/data/music/api');
    const page = await fetchLibrary('recent');
    return page.data.tracks
      .slice(0, RECENT_PROFILE_LIMIT)
      .map((t) => ({ title: t.title, artist: t.artists.map((a) => a.name).join(', ') }));
  } catch {
    return [];
  }
}
const EMPTY_RETRY_MS = 15_000;

export class PlayerAutoplayController {
  private refilling: Promise<void> | null = null;
  private autoplaySeed: string | null = null;
  private readonly exhaustedSeeds = new Set<string>();
  private lastEmptyRetry = 0;

  constructor(
    private readonly getState: () => PlayerAutoplayState,
    private readonly setQueue: (queue: QueueEntry[]) => void,
    private readonly inParty: () => boolean,
  ) {}

  refill(force = false, anySeed = false): Promise<void> {
    const { autoplay, repeat, queue, index } = this.getState();
    if (!autoplay || repeat !== 'off' || index < 0) return Promise.resolve();
    if (this.inParty()) return Promise.resolve();
    const needed = MAX_QUEUED_AUTOPLAY - queue.slice(index + 1).filter((e) => e.tier === 'autoplay').length;
    if (needed <= 0) return Promise.resolve();
    const playing = queue[index]?.track;
    if (playing?.streamUrl) return Promise.resolve();
    let seed = playing;
    if (seed && this.exhaustedSeeds.has(seed.videoId) && anySeed) {
      seed = queue
        .slice(0, index)
        .reverse()
        .find((e) => !this.exhaustedSeeds.has(e.track.videoId))?.track;
    }
    if (!playing || !seed || this.exhaustedSeeds.has(seed.videoId)) return Promise.resolve();
    if (!force && this.autoplaySeed === seed.videoId) return Promise.resolve();
    this.autoplaySeed = seed.videoId;
    this.refilling ??= (async () => {
      try {
        const { fetchRadio } = await import('@/data/music/api');
        let { candidates, seedIsVideo } = await fetchRadio(seed.videoId);
        const { hideExplicit, focusedFeed } = this.getState();
        if (hideExplicit || focusedFeed) {
          const profile: TasteProfile = focusedFeed
            ? buildProfile([{ title: seed.title, artist: artistLine(seed) }, ...(await recentPlays())])
            : EMPTY_PROFILE;
          candidates = candidates.filter((c) =>
            keepTrack(c.track.explicit, hideExplicit, focusedFeed, c.track.title, artistLine(c.track), profile));
          if (focusedFeed && !candidates.length) {
            // YouTube blended the account's wider history into the radio; rebuild from a search for more like the seed.
            const query = fallbackQuery(seed.title, artistLine(seed));
            if (query) {
              const { fetchSearch } = await import('@/data/music/api');
              const results = await fetchSearch(query);
              candidates = results.songs
                .filter((item) => keepTrack(item.explicit, hideExplicit, true, item.title, item.artists.map((a) => a.name).join(', '), profile))
                .map((item) => ({ track: itemToTrack(item), isVideo: false }));
            }
          }
        }
        const current = this.getState();
        const extra = extendStation(
          current.queue.map((e) => e.track),
          candidates,
          needed,
          seedIsVideo,
        );
        if (!extra.length) {
          this.exhaustedSeeds.add(seed.videoId);
          return;
        }
        const source: QueueSource = { title: `${seed.title} Radio`, type: 'radio', id: `RDAMVM${seed.videoId}` };
        if (current.queue[current.index]?.track.videoId !== playing.videoId || !current.autoplay) {
          this.autoplaySeed = null;
          return;
        }
        this.setQueue([...current.queue, ...extra.map((t) => asEntry(t, 'autoplay', source))]);
        trackLog.i(`AutoPlay added ${extra.length} tracks from ${seed.title}'s radio`, seed.videoId);
      } catch (error) {
        trackLog.w(`AutoPlay couldn't load the radio: ${String(error)}`, seed.videoId);
        this.autoplaySeed = null;
      } finally {
        this.refilling = null;
      }
    })();
    return this.refilling;
  }

  ensure() {
    const { autoplay, repeat, queue, index, status } = this.getState();
    if (!autoplay || repeat !== 'off' || index < 0 || status !== 'playing' || this.inParty()) return;
    if (index + 1 < queue.length || this.refilling) return;
    const now = Date.now();
    if (now - this.lastEmptyRetry < EMPTY_RETRY_MS) return;
    this.lastEmptyRetry = now;
    trackLog.i('AutoPlay is on but nothing is queued; asking for the radio again', queue[index]?.track.videoId ?? null);
    void this.refill(true, true);
  }
        }
