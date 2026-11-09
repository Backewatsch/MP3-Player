package com.backewatsch.mp3player.data

/** Genre-Angaben vereinheitlichen – ohne Android, getestet. */
object Genres {
    /** ID3v1-Genres (alte MP3s speichern oft nur „(17)“ oder „17“) */
    private val id3v1 = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    /** „(17)“, „17“, „(17)Rock“, „hip hop“ → „Rock“ bzw. „Hip-Hop“; leer wenn unbekannt */
    fun normalize(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        Regex("^\\((\\d+)\\)(.*)$").matchEntire(s)?.let { m ->
            val rest = m.groupValues[2].trim()
            s = rest.ifEmpty { id3v1.getOrNull(m.groupValues[1].toInt()) ?: "" }
        }
        s.toIntOrNull()?.let { s = id3v1.getOrNull(it) ?: "" }
        // Mehrere Genres („Pop; Dance“) → das erste
        s = s.split(';', '/', ',').first().trim()
        if (s.isEmpty()) return ""
        val lower = s.lowercase()
        // Häufige Schreibweisen zusammenfassen
        return when (lower.replace(Regex("[\\s_-]+"), "")) {
            "hiphop", "hiphoprap" -> "Hip-Hop"
            "rnb", "r&b", "rhythmandblues" -> "R&B"
            "electronic", "electronica", "electro", "edm" -> "Electronic"
            "deutschrap", "germanrap" -> "Deutschrap"
            else -> s.split(' ').joinToString(" ") { w -> if (w.length <= 3 && w.all { it.isUpperCase() || !it.isLetter() }) w else w.replaceFirstChar { it.uppercase() } }
        }
    }
}
