package com.backewatsch.mp3player.data

import java.util.Locale

/** Reine Hilfsfunktionen rund um Dateinamen und Formate (ohne Android-Abhängigkeiten, getestet). */
object FileNames {
    /** Audioformate, die Android dekodieren kann */
    val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus")
    /** Videos, deren Tonspur in MP3 umgewandelt wird */
    val videoExtensions = setOf("mp4", "m4v", "mov", "3gp", "webm", "mkv")

    private val mimeExtensions = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mpeg3" to "mp3", "audio/x-mpeg" to "mp3", "audio/x-mp3" to "mp3",
        "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a", "audio/aac" to "aac", "audio/aacp" to "aac",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
        "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/webm" to "webm",
        "video/mp4" to "mp4", "video/quicktime" to "mov", "video/x-m4v" to "m4v", "video/3gpp" to "3gp",
        "video/webm" to "webm", "video/x-matroska" to "mkv",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun baseName(name: String): String = if (name.contains('.')) name.substringBeforeLast('.') else name

    fun isAudio(name: String) = extension(name) in audioExtensions
    fun isVideo(name: String) = extension(name) in videoExtensions
    fun isImportable(name: String) = isAudio(name) || isVideo(name) || extension(name) == "webm"
    fun isMp3(name: String) = extension(name) == "mp3"

    /** Entfernt verbotene Zeichen, versteckte Namen (.xyz) und kürzt zu lange Namen. */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:?%*|\"<>\\p{Cntrl}]"), "_").trim()
        val visible = cleaned.trimStart('.')
        if (visible.isEmpty()) return "Titel.mp3"
        if (visible.length <= 180) return visible
        val ext = extension(visible)
        val base = baseName(visible).take(170)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * Liefert einen passenden Dateinamen mit Audio-/Video-Endung – oder `null`, wenn es keine Mediendatei ist.
     * @param suggested Name aus Content-Disposition o. Ä.
     */
    fun mediaFileName(suggested: String?, mime: String?, urlPath: String?): String? {
        var name = suggested?.trim().orEmpty()
        if (name.isEmpty()) name = urlPath?.substringAfterLast('/')?.substringBefore('?').orEmpty()
        if (name.isEmpty() || name == "/") name = "Download"
        name = sanitize(name)
        if (isImportable(name)) return name

        val lowerMime = mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
        val base = baseName(name).ifEmpty { "Download" }
        mimeExtensions[lowerMime]?.let { return "$base.$it" }
        val urlExt = extension(urlPath?.substringBefore('?').orEmpty())
        if (isImportable("x.$urlExt") && lowerMime?.startsWith("text/") != true) return "$base.$urlExt"
        return null
    }

    fun notMediaMessage(mime: String?): String {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        return when {
            m.startsWith("audio/") || m.startsWith("video/") -> "Das Format $mime wird nicht unterstützt."
            m.startsWith("text/html") -> "Der Link führt zu einer Webseite, nicht direkt zu einer Audio- oder Videodatei."
            m.isEmpty() -> "Keine Audio- oder Videodatei."
            else -> "Keine Audio- oder Videodatei ($mime)."
        }
    }

    /** Adresszeile: URL, Domain oder Suchbegriff (DuckDuckGo) */
    fun urlFromInput(text: String): String {
        val t = text.trim()
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20")
    }

    fun isYouTube(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == "youtu.be" || h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com")
    }
}
