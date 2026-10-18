package com.project.lol.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.project.lol.R
import com.project.lol.timer.SleepTimerAction
import com.project.lol.timer.SleepTimerMode
import com.project.lol.timer.SleepTimerState

@Composable
fun SleepTimerDialog(
    state: SleepTimerState,
    inputText: String,
    onInputChange: (String) -> Unit,
    onStartCountdown: (Int) -> Unit,
    onStartEndOfSong: () -> Unit,
    onActionChange: (SleepTimerAction) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                stringResource(R.string.main_sleep_timer_title),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            if (state.active) {
                SleepTimerActiveContent(state = state)
            } else {
                SleepTimerSetupContent(
                    state = state,
                    inputText = inputText,
                    onInputChange = onInputChange,
                    onStartCountdown = onStartCountdown,
                    onStartEndOfSong = onStartEndOfSong,
                    onActionChange = onActionChange
                )
            }
        },
        confirmButton = {
            if (state.active) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.main_close))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onCancelTimer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.main_cancel_timer))
                    }
                }
            } else {
                val minutes = inputText.toIntOrNull() ?: 0
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.main_cancel))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { onStartCountdown(minutes) },
                        enabled = minutes > 0
                    ) {
                        Text(stringResource(R.string.main_set_timer))
                    }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun SleepTimerActiveContent(state: SleepTimerState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.main_sleep_timer_emoji),
            style = MaterialTheme.typography.displaySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.main_timer_active),
            fontWeight = FontWeight.SemiBold
        )
        if (state.mode == SleepTimerMode.END_OF_SONG) {
            Text(
                text = stringResource(R.string.timer_waiting_end_of_song),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            val remainingSecs = state.remainingMs / 1000
            Text(
                text = stringResource(
                    R.string.main_timer_remaining, remainingSecs / 60, remainingSecs % 60
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                if (state.action == SleepTimerAction.QUIT) R.string.timer_action_summary_quit
                else R.string.timer_action_summary_pause
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SleepTimerSetupContent(
    state: SleepTimerState,
    inputText: String,
    onInputChange: (String) -> Unit,
    onStartCountdown: (Int) -> Unit,
    onStartEndOfSong: () -> Unit,
    onActionChange: (SleepTimerAction) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.timer_quick_select),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimerPresetButton(
                label = stringResource(R.string.timer_preset_minutes, 15),
                modifier = Modifier.weight(1f)
            ) { onStartCountdown(15) }
            TimerPresetButton(
                label = stringResource(R.string.timer_preset_minutes, 30),
                modifier = Modifier.weight(1f)
            ) { onStartCountdown(30) }
            TimerPresetButton(
                label = stringResource(R.string.timer_preset_minutes, 60),
                modifier = Modifier.weight(1f)
            ) { onStartCountdown(60) }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = onStartEndOfSong,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.timer_end_of_song),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.timer_end_of_song_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.main_set_minutes),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = inputText,
            onValueChange = { new ->
                if (new.length <= 5 && new.all { it.isDigit() }) {
                    onInputChange(new)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.main_timer_minutes_hint)) },
            trailingIcon = {
                Text(
                    stringResource(R.string.main_minutes_suffix),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.timer_when_timer_ends),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        TimerActionOption(
            label = stringResource(R.string.timer_action_pause),
            selected = state.action == SleepTimerAction.PAUSE,
            onClick = { onActionChange(SleepTimerAction.PAUSE) }
        )
        TimerActionOption(
            label = stringResource(R.string.timer_action_quit),
            selected = state.action == SleepTimerAction.QUIT,
            onClick = { onActionChange(SleepTimerAction.QUIT) }
        )
    }
}

@Composable
private fun TimerPresetButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TimerActionOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
