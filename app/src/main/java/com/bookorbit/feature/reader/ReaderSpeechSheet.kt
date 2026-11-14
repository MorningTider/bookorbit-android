package com.bookorbit.feature.reader

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bookorbit.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSpeechSheet(
    settings: ReaderSettings,
    player: ReaderSpeechPlayer,
    state: SpeechState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ReaderPanel(settings, onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.speech_title), style = MaterialTheme.typography.titleLarge)
            Text(state.chapter.ifBlank { state.title }, style = MaterialTheme.typography.bodyMedium)
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (state.preparing) Text(stringResource(R.string.speech_preparing))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (state.playing) player.pause() else player.start() },
                    enabled = state.ready,
                    modifier = Modifier.testTag("speech-play"),
                ) {
                    Text(
                        stringResource(
                            if (state.playing) R.string.speech_pause else R.string.speech_resume
                        )
                    )
                }
                TextButton(onClick = player::stop, enabled = state.active) {
                    Text(stringResource(R.string.speech_stop))
                }
            }
            Text(stringResource(R.string.speech_rate, state.rate))
            var rate by remember(state.rate) { mutableStateOf(state.rate) }
            Slider(
                value = rate,
                onValueChange = { rate = it },
                onValueChangeFinished = { player.setRate(rate) },
                valueRange = .5f..2f,
                steps = 14,
            )
            HorizontalDivider()
            var voicesOpen by remember { mutableStateOf(false) }
            TextButton(onClick = { voicesOpen = !voicesOpen }, enabled = state.ready) {
                Text(
                    state.voices.firstOrNull { it.name == state.voice }?.label
                        ?: stringResource(R.string.speech_voice_default)
                )
            }
            if (voicesOpen) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.speech_voice_default)) },
                    onClick = {
                        player.setVoice("")
                        voicesOpen = false
                    },
                )
                state.voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                voice.label +
                                    if (voice.network)
                                        " · " + stringResource(R.string.speech_voice_network)
                                    else " · " + stringResource(R.string.speech_voice_offline)
                            )
                        },
                        onClick = {
                            player.setVoice(voice.name)
                            voicesOpen = false
                        },
                    )
                }
            }
            Text(stringResource(R.string.speech_sleep), style = MaterialTheme.typography.titleSmall)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60).forEach { minutes ->
                    TextButton(
                        onClick = { player.setSleep(minutes) },
                        modifier = Modifier.testTag("speech-sleep-$minutes"),
                    ) {
                        Text(
                            (if (state.sleepMinutes == minutes) "✓ " else "") +
                                if (minutes == 0) stringResource(R.string.speech_sleep_off)
                                else stringResource(R.string.speech_sleep_minutes, minutes)
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.speech_scope_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    runCatching {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        }
                        .onFailure {
                            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        }
                }
            ) {
                Text(stringResource(R.string.speech_system_settings))
            }
            if ((!state.ready || state.error != null) && !state.preparing)
                TextButton(onClick = player::reload) { Text(stringResource(R.string.retry)) }
        }
    }
}
