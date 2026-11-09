package com.backewatsch.mp3player.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.player.SoundPresets

/** Klang: Equalizer mit Voreinstellungen, Bass-Verstärkung, Ein-/Ausblenden zwischen Liedern */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val settings by app.sound.settings.collectAsState()
    val info by app.sound.info.collectAsState()
    val sliderColors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = Accent, inactiveTrackColor = CyberLine)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CyberPanel) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Klang", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", fontWeight = FontWeight.SemiBold)
                    Text(if (info == null) "Startet, sobald Musik läuft" else "Bass und Höhen anpassen",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.enabled, onCheckedChange = app.sound::setEnabled, modifier = Modifier.testTag("eqSwitch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Accent))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (SoundPresets.all.map { it.name } + SoundPresets.CUSTOM).forEach { name ->
                    FilterChip(
                        selected = settings.preset == name,
                        onClick = { app.sound.setPreset(name); if (!settings.enabled) app.sound.setEnabled(true) },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.22f), selectedLabelColor = Accent),
                        modifier = Modifier.testTag("preset-$name"),
                    )
                }
            }

            val eq = info
            if (eq != null) {
                Spacer(Modifier.height(8.dp))
                val levels = app.sound.currentLevels()
                eq.centerHz.forEachIndexed { i, hz ->
                    val level = levels.getOrElse(i) { 0 }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(64.dp))
                        Slider(
                            value = level.toFloat(),
                            onValueChange = { app.sound.setBand(i, it.toInt()) },
                            valueRange = eq.minMb.toFloat()..eq.maxMb.toFloat(),
                            enabled = settings.enabled,
                            colors = sliderColors,
                            modifier = Modifier.weight(1f).testTag("band-$i"),
                        )
                        Text("%+.0f dB".format(level / 100f), style = MaterialTheme.typography.labelMedium,
                            color = NeonCyan, modifier = Modifier.width(52.dp))
                    }
                }
                if (eq.bassSupported) {
                    Spacer(Modifier.height(6.dp))
                    Text("Bass-Verstärkung", fontWeight = FontWeight.SemiBold)
                    Slider(settings.bass.toFloat(), onValueChange = { app.sound.setBass(it.toInt()) }, valueRange = 0f..1000f,
                        enabled = settings.enabled, colors = sliderColors, modifier = Modifier.testTag("bassSlider"))
                }
            } else {
                Text("Hinweis: Spiele ein Lied ab, dann erscheinen hier die Regler deines Handys.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp))
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lautstärke angleichen", fontWeight = FontWeight.SemiBold)
                    Text("Alle Lieder klingen ähnlich laut – kein ständiges Nachregeln",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.normalize, onCheckedChange = app.sound::setNormalize, modifier = Modifier.testTag("normalizeSwitch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Accent))
            }

            Spacer(Modifier.height(20.dp))
            Text("Überblenden", fontWeight = FontWeight.SemiBold)
            Text(
                if (settings.crossfadeSec == 0) "Aus – Lieder beginnen und enden normal"
                else "Lieder werden am Ende ${settings.crossfadeSec} s sanft aus- und am Anfang eingeblendet",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("crossfadeText"),
            )
            Slider(settings.crossfadeSec.toFloat(), onValueChange = { app.sound.setCrossfade(Math.round(it)) },
                valueRange = 0f..12f, steps = 11, colors = sliderColors, modifier = Modifier.fillMaxWidth().testTag("crossfadeSlider"))
            Spacer(Modifier.height(28.dp))
        }
    }
}
