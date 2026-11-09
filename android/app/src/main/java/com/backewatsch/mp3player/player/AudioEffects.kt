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
        /** Alle Lieder ähnlich laut abspielen */
        val normalize: Boolean = true,
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
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    /** Verstärkung des laufenden Titels in dB (aus der Lautheits-Messung) */
    private var trackGainDb = 0f

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
            enhancer = runCatching { android.media.audiofx.LoudnessEnhancer(audioSessionId) }.getOrNull()
            _info.value = Info(centers, range[0].toInt(), range[1].toInt(), bb?.strengthSupported == true)
        } catch (e: Throwable) {
            // Gerät ohne Equalizer – Wiedergabe läuft trotzdem
            release()
            enhancer = runCatching { android.media.audiofx.LoudnessEnhancer(audioSessionId) }.getOrNull()
        }
        apply()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { enhancer?.release() }
        equalizer = null
        bassBoost = null
        enhancer = null
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

    fun setNormalize(on: Boolean) = change { it.copy(normalize = on) }

    /** Verstärkung für den laufenden Titel (Anheben über den LoudnessEnhancer, Absenken über die Lautstärke) */
    fun setTrackGain(gainDb: Float) {
        trackGainDb = gainDb
        applyEnhancer()
    }

    /** Lautstärke-Faktor fürs Absenken lauter Titel */
    fun attenuation(): Float =
        if (_settings.value.normalize) com.backewatsch.mp3player.data.LoudnessMeter.attenuation(trackGainDb) else 1f

    private fun applyEnhancer() {
        enhancer?.let { e ->
            runCatching {
                val boost = if (_settings.value.normalize && trackGainDb > 0f) (trackGainDb * 100).toInt() else 0
                e.setTargetGain(boost)
                e.setEnabled(boost > 0)
            }
        }
    }

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
        applyEnhancer()
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
        normalize = prefs.getBoolean("normalize", true),
    )

    private fun save() {
        val s = _settings.value
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("preset", s.preset)
            .putString("custom", s.custom.joinToString(","))
            .putInt("bass", s.bass)
            .putInt("crossfade", s.crossfadeSec)
            .putBoolean("normalize", s.normalize)
            .apply()
    }
}
