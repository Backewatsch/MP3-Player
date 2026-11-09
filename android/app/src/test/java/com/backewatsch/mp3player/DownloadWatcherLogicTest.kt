package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.DownloadWatcher
import org.junit.Assert.assertFalse
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
}
