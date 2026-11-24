package com.licht.nuvx.data.lyrics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The word model nuvx-core follows too (core/contracts/lyrics-words.json).
class LyricWordsContractTest {
    @Test
    fun wordsAgreeWithTheSharedCases() {
        val file = File("../../core/contracts/lyrics-words.json").takeIf { it.isFile } ?: File("core/contracts/lyrics-words.json")
        for (case in Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val runs = c["runs"]!!.jsonArray.map { r ->
                val a = r.jsonArray
                TimedRun(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content)
            }
            val got = wordsFromRuns(runs, c["explicitSpacing"]?.jsonPrimitive?.boolean ?: false).map { w ->
                listOf(w.startMs, w.endMs, w.text, w.syllables.map { listOf(it.startMs, it.endMs, it.startChar.toLong(), it.endChar.toLong()) })
            }
            val want = c["words"]!!.jsonArray.map { it.jsonObject }.map { w ->
                listOf(
                    w["start"]!!.jsonPrimitive.long,
                    w["end"]!!.jsonPrimitive.long,
                    w["text"]!!.jsonPrimitive.content,
                    w["syllables"]!!.jsonArray.map { s -> s.jsonArray.map { it.jsonPrimitive.long } },
                )
            }
            assertEquals(want, got, c["name"]!!.jsonPrimitive.content)
        }
    }
}
