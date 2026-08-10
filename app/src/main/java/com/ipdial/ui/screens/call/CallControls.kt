package com.ipdial.ui.screens.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallSession
import com.ipdial.ui.screens.clickableNoRipple

@Composable
fun CallControls(
    session: CallSession,
    isActive: Boolean,
    onKeypad: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onRecord: () -> Unit,
    audioDeviceMode: AudioDeviceMode = AudioDeviceMode.EARPIECE,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
    ) {
        CallControlButton(
            icon = Icons.Default.Dialpad,
            label = "Keypad",
            onClick = onKeypad
        )
        CallControlButton(
            icon = if (session.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = "Mute",
            active = session.isMuted,
            enabled = isActive,
            onClick = onMute
        )

        // Audio Device Button
        val audioIcon = when (audioDeviceMode) {
            AudioDeviceMode.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
            AudioDeviceMode.BLUETOOTH -> Icons.Default.Bluetooth
            else -> Icons.Default.PhoneInTalk
        }
        val audioLabel = when (audioDeviceMode) {
            AudioDeviceMode.SPEAKER -> "Speaker"
            AudioDeviceMode.BLUETOOTH -> "Bluetooth"
            else -> "Earpiece"
        }

        CallControlButton(
            icon = audioIcon,
            label = audioLabel,
            active = audioDeviceMode != AudioDeviceMode.EARPIECE,
            enabled = true,
            onClick = onSpeaker
        )

        CallControlButton(
            icon = Icons.Default.RadioButtonChecked,
            label = if (session.isRecording) "Recording" else "Record",
            active = session.isRecording,
            enabled = isActive,
            onClick = onRecord
        )
    }
}

@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(if (enabled) Modifier.clickableNoRipple { onClick() } else Modifier)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.primary
                       else if (!enabled) MaterialTheme.colorScheme.outline
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
