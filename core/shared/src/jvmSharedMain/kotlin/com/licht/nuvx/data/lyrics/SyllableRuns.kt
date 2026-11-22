package com.licht.nuvx.data.lyrics

import com.licht.nuvx.core.CoreRun
import com.licht.nuvx.core.wordsFromRuns as coreWordsFromRuns

internal class TimedRun(val startMs: Long, val endMs: Long, val raw: String)

// Android's word model, computed by nuvx-core (core/rust/nuvx-core/src/lyrics/words.rs) and pinned by
// core/contracts/lyrics-words.json, so every app splits lyrics into words the same way.
internal fun wordsFromRuns(runs: List<TimedRun>, spacingIsExplicit: Boolean = false): List<LyricWord> =
    coreWordsFromRuns(runs.map { CoreRun(it.startMs, it.endMs, it.raw) }, spacingIsExplicit).map { word ->
        LyricWord(
            word.startMs,
            word.endMs,
            word.text,
            word.syllables.map { LyricSyllable(it.startMs, it.endMs, it.startChar, it.endChar) },
        )
    }
