package com.backewatsch.mp3player.player

/** Reine Logik des Schlafmodus (ohne Android-Abhängigkeiten, getestet). */
object SleepTimer {
    /** Höchstens 24 Stunden */
    const val MAX_MINUTES = 24 * 60

    /** In den letzten Sekunden wird die Musik sanft leiser, statt abrupt aufzuhören */
    const val FADE_MS = 10_000L

    /** Liest die eingegebene Minutenzahl; null, wenn sie keine ganze Zahl von 1 bis [MAX_MINUTES] ist. */
    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }

    /** Lautstärke während des Ausblendens: 1 = normal, sinkt in den letzten [FADE_MS] auf fast 0. */
    fun volumeFor(remainingMs: Long): Float =
        if (remainingMs >= FADE_MS) 1f else (remainingMs.toFloat() / FADE_MS).coerceIn(0.05f, 1f)

    /** Restzeit zum Anzeigen, z. B. „12:05“ oder „1:02:30“ */
    fun formatRemaining(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
