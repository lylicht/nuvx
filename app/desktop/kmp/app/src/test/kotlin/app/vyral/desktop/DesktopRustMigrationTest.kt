package app.vyral.desktop

import app.vyral.night.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRustMigrationTest {
    @Test fun `portable export preserves order metadata and excludes unplayable local references`() {
        val original = listOf(DesktopPlaylist(
            id = "fixture-one", title = "آهنگ‌های من",
            songs = listOf(
                Song(videoId = "abcdefghijk", title = "First", artist = "night", thumbnailUrl = null, fromAutoplay = false, durationText = "3:12"),
                Song(videoId = "local:one", title = "Local", artist = "", thumbnailUrl = null, fromAutoplay = false, localPath = "C:\\Music\\private.flac"),
                Song(videoId = "zyxwvutsrqp", title = "Second", artist = "night", thumbnailUrl = null, fromAutoplay = false, isVideo = true),
            ),
        ))
        val result = DesktopRustMigration.encode(original, "2026-11-27T00:00:00Z")
        assertEquals(1, result.playlists)
        assertEquals(2, result.songs)
        assertEquals(1, result.unsupported)
        assertEquals(3, original.single().songs.size)
        assertFalse(result.text.contains("private.flac"))
        val json = Json.parseToJsonElement(result.text).jsonObject
        val fixture = Json.parseToJsonElement(Files.readString(
            java.nio.file.Path.of(System.getProperty("vyral.desktop.contracts"), "kmp-library-v1.json"),
        )).jsonObject
        assertEquals(fixture - "versionName", json - "versionName")
        assertEquals("vyral", json.getValue("app").jsonPrimitive.content)
        val playlist = json.getValue("playlists").jsonArray.single().jsonObject
        assertEquals("vyral-kmp:fixture-one", playlist.getValue("id").jsonPrimitive.content)
        assertEquals(listOf("abcdefghijk", "zyxwvutsrqp"), playlist.getValue("songs").jsonArray.map {
            it.jsonObject.getValue("videoId").jsonPrimitive.content
        })
    }

    @Test fun `export never replaces an existing backup`() {
        val directory = Files.createTempDirectory("vyral-export-test")
        val file = directory.resolve("backup.json")
        try {
            Files.writeString(file, "keep this")
            val export = DesktopRustMigration.encode(emptyList(), "2026-11-27T00:00:00Z")
            assertFails { DesktopRustMigration.writeNew(file, export) }
            assertEquals("keep this", Files.readString(file))
            val fresh = directory.resolve("fresh.json")
            DesktopRustMigration.writeNew(fresh, export)
            assertEquals(export.text, Files.readString(fresh))
            Files.delete(fresh)
            assertTrue(Files.list(directory).use { it.count() == 1L })
        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }
}
