package com.licht.nuvx.data.lyrics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Android's TTML parsing goes through nuvx-core; this pins it to core/contracts/lyrics-ttml.json.
class LyricTtmlContractTest {
    @Test
    fun ttmlAgreesWithTheSharedCases() {
        val file = File("../../core/contracts/lyrics-ttml.json").takeIf { it.isFile } ?: File("core/contracts/lyrics-ttml.json")
        for (case in Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val got = TtmlLyrics.parse(c["ttml"]!!.jsonPrimitive.content).map { l ->
                listOf(
                    l.timeMs,
                    l.text,
                    if (l.alignment == LyricAlignment.End) "end" else "start",
                    l.words.map { listOf(it.text, it.startMs, it.endMs, it.syllables.size.toLong()) },
                    l.background?.words.orEmpty().map { listOf(it.text, it.startMs, it.endMs) },
                    l.sungUntilMs,
                )
            }
            val want = c["lines"]!!.jsonArray.map { it.jsonObject }.map { l ->
                listOf(
                    l["start"]!!.jsonPrimitive.long,
                    l["text"]!!.jsonPrimitive.content,
                    l["align"]!!.jsonPrimitive.content,
                    l["words"]!!.jsonArray.map { w -> w.jsonArray.let { listOf(it[0].jsonPrimitive.content, it[1].jsonPrimitive.long, it[2].jsonPrimitive.long, it[3].jsonPrimitive.long) } },
                    l["background"]!!.jsonArray.map { w -> w.jsonArray.let { listOf(it[0].jsonPrimitive.content, it[1].jsonPrimitive.long, it[2].jsonPrimitive.long) } },
                    l["sungUntil"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.long,
                )
            }
            assertEquals(want, got, c["name"]!!.jsonPrimitive.content)
        }
    }
}
