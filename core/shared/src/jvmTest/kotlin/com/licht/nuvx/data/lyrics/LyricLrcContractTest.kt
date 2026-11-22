package com.licht.nuvx.data.lyrics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Android's LRC parsing goes through nuvx-core; this pins it to core/contracts/lyrics-lrc.json.
class LyricLrcContractTest {
    @Test
    fun lrcAgreesWithTheSharedCases() {
        val file = File("../../core/contracts/lyrics-lrc.json").takeIf { it.isFile } ?: File("core/contracts/lyrics-lrc.json")
        for (case in Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val got = LrcLib.parseLrc(c["lrc"]!!.jsonPrimitive.content).map { l ->
                listOf(
                    l.timeMs,
                    l.text,
                    if (l.alignment == LyricAlignment.End) "end" else "start",
                    l.words.map { listOf(it.text, it.startMs, it.endMs) },
                )
            }
            val want = c["lines"]!!.jsonArray.map { line ->
                val a = line.jsonArray
                listOf(
                    a[0].jsonPrimitive.long,
                    a[1].jsonPrimitive.content,
                    a[2].jsonPrimitive.content,
                    a[3].jsonArray.map { w -> w.jsonArray.let { listOf(it[0].jsonPrimitive.content, it[1].jsonPrimitive.long, it[2].jsonPrimitive.long) } },
                )
            }
            assertEquals(want, got, c["name"]!!.jsonPrimitive.content)
        }
    }
}
