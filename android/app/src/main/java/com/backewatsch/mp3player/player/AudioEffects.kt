package com.backewatsch.mp3player.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Equalizer, Bass-Verstärkung und Überblenden – Einstellungen bleiben gespeichert. */
class AudioEffects(context: Context) {
    data class Settings(
        val enabled: Boolean = false,
        val preset: String = "Normal",
        /** Eigene Bandpegel in Millibel (nur bei Voreinstellung „Eigene“) */
        val custom: List<Int> = emptyList(),
        /** Bass-Verstärkung 0…1000 */
        val bass: Int = 0,
        /** Ein-/Ausblenden zwischen Liedern in Sekunden (0 = aus) */
        val crossfadeSec: Int = 0,
    )

    /** Was der Equalizer des Geräts kann (null = nicht verfügbar / noch nicht verbunden) */
    data class Info(val centerHz: List<Int>, val minMb: Int, val maxMb: Int, val bassSupported: Boolean)

    private val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /** Mit der Audio-Sitzung des Players verbinden (vom Wiedergabedienst aufgerufen) */
    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        try {
            val eq = Equalizer(0, audioSessionId)
            val range = eq.bandLevelRange
            val centers = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 }
            equalizer = eq
            val bb = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
            bassBoost = bb
            _info.value = Info(centers, range[0].toInt(), range[1].toInt(), bb?.strengthSupported == true)
        } catch (e: Throwable) {
            // Gerät ohne Equalizer – Wiedergabe läuft trotzdem
            release()
        }
        apply()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        _info.value = null
    }

    fun setEnabled(on: Boolean) = change { it.copy(enabled = on) }

    fun setPreset(name: String) = change { s ->
        // Beim Wechsel auf „Eigene“ mit den Werten der bisherigen Voreinstellung beginnen
        if (name == SoundPresets.CUSTOM && s.preset != SoundPresets.CUSTOM) s.copy(preset = name, custom = currentLevels())
        else s.copy(preset = name)
    }

    fun setBand(index: Int, levelMb: Int) = change { s ->
        val levels = (if (s.preset == SoundPresets.CUSTOM) s.custom else currentLevels()).toMutableList()
        while (levels.size <= index) levels += 0
        levels[index] = levelMb
        s.copy(preset = SoundPresets.CUSTOM, custom = levels, enabled = true)
    }

    fun setBass(strength: Int) = change { it.copy(bass = strength.coerceIn(0, 1000), enabled = true) }

    fun setCrossfade(seconds: Int) = change { it.copy(crossfadeSec = seconds.coerceIn(0, 12)) }

    /** Pegel, die gerade gelten (je Band, Millibel) */
    fun currentLevels(): List<Int> {
        val info = _info.value ?: return emptyList()
        val s = _settings.value
        if (s.preset == SoundPresets.CUSTOM) {
            return info.centerHz.indices.map { (s.custom.getOrNull(it) ?: 0).coerceIn(info.minMb, info.maxMb) }
        }
        val preset = SoundPresets.byName(s.preset) ?: SoundPresets.all.first()
        return SoundPresets.levelsFor(preset, info.centerHz, info.minMb, info.maxMb)
    }

    /** Für Tests: Pegel, die der Equalizer tatsächlich hat */
    fun appliedLevels(): List<Int>? = equalizer?.let { eq -> (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() } }
    fun isEqualizerActive(): Boolean = equalizer?.getEnabled() == true

    private fun change(update: (Settings) -> Settings) {
        _settings.value = update(_settings.value)
        save()
        apply()
    }

    private fun apply() {
        val s = _settings.value
        equalizer?.let { eq ->
            runCatching {
                currentLevels().forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level.toShort()) }
                eq.setEnabled(s.enabled)
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                if (bb.strengthSupported) bb.setStrength(s.bass.toShort())
                bb.setEnabled(s.enabled && s.bass > 0)
            }
        }
    }

    private fun load() = Settings(
        enabled = prefs.getBoolean("enabled", false),
        preset = prefs.getString("preset", "Normal") ?: "Normal",
        custom = prefs.getString("custom", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
        bass = prefs.getInt("bass", 0),
        crossfadeSec = prefs.getInt("crossfade", 0),
    )

    private fun save() {
        val s = _settings.value
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("preset", s.preset)
            .putString("custom", s.custom.joinToString(","))
            .putInt("bass", s.bass)
            .putInt("crossfade", s.crossfadeSec)
            .apply()
    }
}
