@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.knobnext.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.knobnext.Prefs
import com.knobnext.R
import com.knobnext.Settings
import com.knobnext.Speaker
import com.knobnext.Speech

/** How track names are read aloud: foreign words, title clean-up, engine, speed, voices, a sample. */
@Composable
fun SpeechCard(s: Settings, prefs: Prefs) {
    val context = LocalContext.current
    val speaker = remember { Speaker(context.applicationContext, prefs) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    val engines = remember { Speech.engines(context) }
    var choosingEngine by remember { mutableStateOf(false) }
    val sample = Speech.announcement(SAMPLE_TITLE, SAMPLE_ARTIST, s.cleanTitles) + ". " +
        Speech.announcement(SAMPLE_TITLE_RU, SAMPLE_ARTIST_RU, s.cleanTitles)

    Column {
        SectionTitle(stringResource(R.string.speech_title))
        Group(6) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ControlRow(shape(0), stringResource(R.string.speech_foreign), caption = stringResource(R.string.speech_foreign_hint)) {
                    ConnectedChoice(
                        options = listOf(true to R.string.speech_foreign_auto, false to R.string.speech_foreign_one),
                        selected = s.ttsMultilang,
                        onSelect = { prefs.ttsMultilang = it },
                    )
                }
                SwitchRow(
                    shape(1), stringResource(R.string.speech_clean), s.cleanTitles, { prefs.cleanTitles = it },
                    subtitle = stringResource(R.string.speech_clean_hint),
                )
                ListRow(
                    shape(2), stringResource(R.string.speech_engine),
                    subtitle = engines.firstOrNull { it.first == s.ttsEngine }?.second ?: stringResource(R.string.speech_engine_default),
                    onClick = { choosingEngine = true },
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
                ControlRow(shape(3), stringResource(R.string.speech_rate)) {
                    ConnectedChoice(
                        options = listOf(
                            0.8f to R.string.speech_rate_slow,
                            1.0f to R.string.speech_rate_normal,
                            1.2f to R.string.speech_rate_faster,
                            1.4f to R.string.speech_rate_fast,
                        ),
                        selected = s.ttsRate,
                        onSelect = { prefs.ttsRate = it },
                    )
                }
                ListRow(
                    shape(4), stringResource(R.string.speech_voices),
                    subtitle = stringResource(R.string.speech_voices_hint),
                    onClick = {
                        try {
                            context.startActivity(Speech.settingsIntent())
                        } catch (_: Exception) {
                        }
                    },
                    trailing = { Icon(KnobIcons.ChevronRight, contentDescription = null) },
                )
                ListRow(
                    shape(5), stringResource(R.string.speech_try),
                    subtitle = "«$sample»",
                    onClick = { speaker.say(sample) },
                    trailing = { Icon(KnobIcons.Play, contentDescription = null) },
                )
            }
        }
    }

    if (choosingEngine) {
        val options = listOf<Pair<String?, String>>(null to stringResource(R.string.speech_engine_default)) + engines
        AlertDialog(
            onDismissRequest = { choosingEngine = false },
            title = { Text(stringResource(R.string.speech_engine)) },
            text = {
                Column {
                    options.forEach { (pkg, label) ->
                        val pick = {
                            prefs.ttsEngine = pkg
                            choosingEngine = false
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = pick)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pkg == s.ttsEngine, onClick = pick)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        stringResource(R.string.speech_engine_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { choosingEngine = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// A title the way players and YouTube often send it, to show the clean-up and the language switch.
private const val SAMPLE_TITLE = "Bohemian Rhapsody (Remastered 2011) [Official Video]"
private const val SAMPLE_ARTIST = "Queen - Topic"
private const val SAMPLE_TITLE_RU = "Группа крови"
private const val SAMPLE_ARTIST_RU = "Кино"
