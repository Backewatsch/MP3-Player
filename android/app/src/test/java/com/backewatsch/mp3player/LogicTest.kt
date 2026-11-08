package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.FileNames
import com.backewatsch.mp3player.model.formatTime
import com.backewatsch.mp3player.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine Logik – läuft ohne Gerät */
class LogicTest {
    @Test fun keepsMediaNames() {
        assertEquals("Lied.mp3", FileNames.mediaFileName("Lied.mp3", "audio/mpeg", null))
        assertEquals("Song.M4A", FileNames.mediaFileName("Song.M4A", null, null))
        assertEquals("clip.webm", FileNames.mediaFileName("clip.webm", "video/webm", null))
    }

    @Test fun usesMimeType() {
        assertEquals("download.mp3", FileNames.mediaFileName("download.php", "audio/mpeg", null))
        assertEquals("videoplayback.mp4", FileNames.mediaFileName("videoplayback", "video/mp4", null))
        assertEquals("get.wav", FileNames.mediaFileName(null, "audio/x-wav; charset=binary", "/get"))
        assertEquals("stream.ogg", FileNames.mediaFileName("stream", "audio/ogg", null))
    }

    @Test fun usesUrlExtension() {
        assertEquals("file.flac", FileNames.mediaFileName("file", "application/octet-stream", "/a/b/track.flac"))
    }

    @Test fun rejectsNonMedia() {
        assertNull(FileNames.mediaFileName("index.html", "text/html", "/"))
        assertNull(FileNames.mediaFileName("bild.jpg", "image/jpeg", null))
        assertNull(FileNames.mediaFileName("x.html", "text/html", "/x.mp3"))
        assertTrue(FileNames.notMediaMessage("text/html").contains("Webseite"))
    }

    @Test fun sanitize() {
        assertEquals("AC_DC_ Live_.mp3", FileNames.sanitize("AC/DC: Live?.mp3"))
        assertEquals("versteckt.mp3", FileNames.sanitize("  .versteckt.mp3 "))
        assertEquals("Titel.mp3", FileNames.sanitize(""))
        val long = FileNames.sanitize("a".repeat(300) + ".mp3")
        assertTrue(long.endsWith(".mp3"))
        assertTrue(long.length <= 180)
    }

    @Test fun formats() {
        assertTrue(FileNames.isMp3("x.MP3"))
        assertFalse(FileNames.isMp3("x.m4a"))
        assertTrue(FileNames.isImportable("film.mkv"))
        assertEquals("0:00", formatTime(0))
        assertEquals("3:07", formatTime(187_900))
        assertEquals("1:02:03", formatTime(3_723_000))
    }

    @Test fun addressInput() {
        assertEquals("https://archive.org", FileNames.urlFromInput("https://archive.org"))
        assertEquals("https://archive.org/details/audio", FileNames.urlFromInput("archive.org/details/audio"))
        assertEquals("https://duckduckgo.com/?q=freie%20musik", FileNames.urlFromInput("freie musik"))
        assertTrue(FileNames.isYouTube("m.youtube.com"))
        assertFalse(FileNames.isYouTube("archive.org"))
    }

    @Test fun parsesRelease() {
        val json = """
            {"tag_name":"v1.0.42","html_url":"https://github.com/x/releases/tag/v1.0.42","body":"Neu",
             "assets":[{"name":"MP3Player.ipa","browser_download_url":"https://x/MP3Player.ipa"},
                       {"name":"MP3Player.apk","browser_download_url":"https://x/MP3Player.apk"}]}
        """.trimIndent()
        val release = UpdateChecker.parseRelease(json)!!
        assertEquals(42, release.build)
        assertEquals("1.0.42", release.version)
        assertEquals("https://x/MP3Player.apk", release.apkUrl)
        assertEquals("Neu", release.notes)
        assertNull(UpdateChecker.parseRelease("{}"))
        assertNull(UpdateChecker.parseRelease("kein json"))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"beta"}"""))
    }
}

/** Schlafmodus: Eingabe, Ausblenden, Anzeige */
class SleepTimerLogicTest {
    @Test fun parsesMinutes() {
        org.junit.Assert.assertEquals(30, com.backewatsch.mp3player.player.SleepTimer.parseMinutes("30"))
        org.junit.Assert.assertEquals(5, com.backewatsch.mp3player.player.SleepTimer.parseMinutes(" 5 "))
        org.junit.Assert.assertEquals(1440, com.backewatsch.mp3player.player.SleepTimer.parseMinutes("1440"))
        org.junit.Assert.assertNull(com.backewatsch.mp3player.player.SleepTimer.parseMinutes("0"))
        org.junit.Assert.assertNull(com.backewatsch.mp3player.player.SleepTimer.parseMinutes("1441"))
        org.junit.Assert.assertNull(com.backewatsch.mp3player.player.SleepTimer.parseMinutes(""))
        org.junit.Assert.assertNull(com.backewatsch.mp3player.player.SleepTimer.parseMinutes("abc"))
    }

    @Test fun fadesOutAtTheEnd() {
        val t = com.backewatsch.mp3player.player.SleepTimer
        org.junit.Assert.assertEquals(1f, t.volumeFor(60_000), 0f)
        org.junit.Assert.assertEquals(0.5f, t.volumeFor(5_000), 0.001f)
        org.junit.Assert.assertEquals(0.05f, t.volumeFor(0), 0.001f)
    }

    @Test fun formatsRemaining() {
        val t = com.backewatsch.mp3player.player.SleepTimer
        org.junit.Assert.assertEquals("30:00", t.formatRemaining(30 * 60_000L))
        org.junit.Assert.assertEquals("0:01", t.formatRemaining(400))
        org.junit.Assert.assertEquals("1:02:30", t.formatRemaining((3600 + 150) * 1000L))
    }
}
