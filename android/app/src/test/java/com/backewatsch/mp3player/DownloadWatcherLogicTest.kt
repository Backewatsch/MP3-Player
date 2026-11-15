package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.DownloadWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWatcherLogicTest {
    @Test fun recognisesDownloadFolder() {
        assertTrue(DownloadWatcher.isInDownloads("Download/", "/storage/emulated/0/Download/Lied.mp3"))
        assertTrue(DownloadWatcher.isInDownloads("Download/Musik/", ""))
        assertTrue(DownloadWatcher.isInDownloads("", "/storage/emulated/0/Download/Lied.mp3"))
        assertTrue(DownloadWatcher.isInDownloads("download/", ""))
    }

    @Test fun ignoresOtherFolders() {
        assertFalse(DownloadWatcher.isInDownloads("Music/", "/storage/emulated/0/Music/Lied.mp3"))
        assertFalse(DownloadWatcher.isInDownloads("Recordings/", "/storage/emulated/0/Recordings/Downloader.mp3"))
        assertFalse(DownloadWatcher.isInDownloads("", "/storage/emulated/0/Music/Downloads-Mix.mp3"))
    }

    @Test fun widerAreasAcceptMoreFolders() {
        val music = "/storage/emulated/0/Music/Lied.mp3"
        val other = "/storage/emulated/0/Bluetooth/Lied.mp3"
        // Downloads zählen immer – und kommen in „Heruntergeladen“
        for (area in DownloadWatcher.Area.entries) {
            assertEquals(true, DownloadWatcher.accepts(area, "Download/", "/storage/emulated/0/Download/Lied.mp3", true, 200_000))
        }
        assertNull(DownloadWatcher.accepts(DownloadWatcher.Area.DOWNLOADS, "Music/", music, true, 200_000))
        assertEquals(false, DownloadWatcher.accepts(DownloadWatcher.Area.MUSIC, "Music/", music, true, 200_000))
        assertNull(DownloadWatcher.accepts(DownloadWatcher.Area.MUSIC, "Bluetooth/", other, true, 200_000))
        assertEquals(false, DownloadWatcher.accepts(DownloadWatcher.Area.ALL, "Bluetooth/", other, true, 200_000))
        assertEquals("Länge unbekannt zählt als Lied", false, DownloadWatcher.accepts(DownloadWatcher.Area.ALL, "Bluetooth/", other, true, 0))
    }

    @Test fun wholePhoneSkipsSoundsThatAreNoSongs() {
        val all = DownloadWatcher.Area.ALL
        assertNull("Klingelton", DownloadWatcher.accepts(all, "Ringtones/", "/storage/emulated/0/Ringtones/Ton.mp3", false, 200_000))
        assertNull("Aufnahme", DownloadWatcher.accepts(all, "Recordings/", "/storage/emulated/0/Recordings/Memo.m4a", true, 200_000))
        assertNull("Sprachnachricht", DownloadWatcher.accepts(all, "", "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes/x.opus", true, 200_000))
        assertNull("kurzer Ton", DownloadWatcher.accepts(all, "Music/", "/storage/emulated/0/Music/Pieps.mp3", true, 5_000))
        assertEquals("Ordner „Calle 13“ ist kein Anruf-Ordner", false,
            DownloadWatcher.accepts(all, "Music/Calle 13/", "/storage/emulated/0/Music/Calle 13/Lied.mp3", true, 200_000))
    }
}
