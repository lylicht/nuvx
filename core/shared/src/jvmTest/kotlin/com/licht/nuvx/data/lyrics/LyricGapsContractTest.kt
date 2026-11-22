package com.licht.nuvx.data.lyrics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The gap rule nuvx-core follows too (core/contracts/lyrics-gaps.json).
class LyricGapsContractTest {
    private val contract: JsonObject = Json.parseToJsonElement(
        (File("../../core/contracts/lyrics-gaps.json").takeIf { it.isFile } ?: File("core/contracts/lyrics-gaps.json")).readText(),
    ).jsonObject

    @Test
    fun timedLinesAgreeWithTheSharedCases() {
        for (case in contract["timed"]!!.jsonArray) {
            val c = case.jsonObject
            val lines = c["lines"]!!.jsonArray.map { it.jsonObject }.map { l ->
                val start = l["start"]!!.jsonPrimitive.long
                LyricLine(
                    timeMs = start,
                    text = l["text"]!!.jsonPrimitive.content,
                    sungUntilMs = l["end"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.long,
                    background = l["backgroundEnd"]?.jsonPrimitive?.long?.let { LyricLine(start, "", sungUntilMs = it) },
                )
            }
            val gaps = lines.withInstrumentalGaps().filter { it.isGap }.map { it.timeMs }
            assertEquals(c["gaps"]!!.jsonArray.map { it.jsonPrimitive.long }, gaps, c["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun lineSyncedLrcAgreesWithTheSharedCases() {
        for (case in contract["lrc"]!!.jsonArray) {
            val c = case.jsonObject
            val want = c["lines"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.int.toLong() to it.jsonArray[1].jsonPrimitive.content }
            val got = LrcLib.parseLrc(c["lrc"]!!.jsonPrimitive.content).map { it.timeMs to it.text }
            assertEquals(want, got, c["name"]!!.jsonPrimitive.content)
        }
    }
}
