package com.ipdial.ui.screens.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
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
    photoMode: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
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
            CallControlButton(Icons.Default.Dialpad, "Keypad", modifier = Modifier.weight(1f), photoMode = photoMode, onClick = onKeypad)
            CallControlButton(
                icon = if (session.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (session.isMuted) "Unmute" else "Mute",
                active = session.isMuted,
                activeColor = Color(0xFF35B978),
                modifier = Modifier.weight(1f),
                photoMode = photoMode,
                onClick = onMute
            )
            CallControlButton(
                icon = audioIcon,
                label = audioLabel,
                active = audioDeviceMode != AudioDeviceMode.EARPIECE,
                activeColor = Color(0xFF35B978),
                modifier = Modifier.weight(1f),
                photoMode = photoMode,
                onClick = onSpeaker
            )
            CallControlButton(
                icon = Icons.Default.RadioButtonChecked,
                label = when {
                    session.isRecording -> "Recording"
                    session.isRecordingPending -> "Will Record"
                    else -> "Record"
                },
                active = session.isRecording || session.isRecordingPending,
                activeColor = Color(0xFFE05252),
                enabled = true,
                modifier = Modifier.weight(1f),
                photoMode = photoMode,
                onClick = onRecord
            )
        }
    }
}

@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    photoMode: Boolean = false,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "control_pulse_$label")
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "control_alpha"
    )
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val applyGlass = isGlass && photoMode
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .then(if (enabled) Modifier.clickableNoRipple { onClick() } else Modifier)
        ) {
            if (applyGlass) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = 0.25f))
                        .blur(16.dp) // Apple-style Glassmorphism (Translucency + Blur)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(com.ipdial.ui.theme.LocalGlassMode.current.let { mode ->
                            if (mode == com.ipdial.ui.theme.GlassMode.Quartz) Color.White.copy(alpha = 0.35f) else Color(0xFF1C1C1E).copy(alpha = 0.35f)
                        })
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            when {
                                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                active   -> activeColor.copy(alpha = if (label == "Recording") pulse else 0.18f)
                                else     -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    applyGlass -> if (active) activeColor else Color.White
                    !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    active   -> activeColor
                    else     -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    }
}
