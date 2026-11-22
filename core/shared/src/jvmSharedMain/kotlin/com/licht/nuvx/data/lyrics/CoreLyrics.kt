package com.licht.nuvx.data.lyrics

import com.licht.nuvx.core.CoreLine
import com.licht.nuvx.core.CoreWord

// nuvx-core's lyrics (through nuvx-ffi) in Android's model.

internal fun CoreWord.toLyricWord() = LyricWord(
    startMs,
    endMs,
    text,
    syllables.map { LyricSyllable(it.startMs, it.endMs, it.startChar, it.endChar) },
)

internal fun CoreLine.toLyricLine(): LyricLine {
    val backing = background.map { it.toLyricWord() }.takeIf { it.isNotEmpty() }?.let {
        LyricLine(timeMs = it.first().startMs, text = it.joinToString(" ") { word -> word.text }, words = it)
    }
    return LyricLine(
        timeMs = startMs,
        text = text,
        words = words.map { it.toLyricWord() },
        background = backing,
        alignment = if (right) LyricAlignment.End else LyricAlignment.Start,
    )
}
