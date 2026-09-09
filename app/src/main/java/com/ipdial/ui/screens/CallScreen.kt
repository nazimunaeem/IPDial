package com.ipdial.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.screens.call.CallControls
import com.ipdial.ui.screens.call.InCallDialpad
import com.ipdial.ui.screens.call.PulsingStateLabel
import com.ipdial.ui.screens.call.formatDuration
import com.ipdial.ui.theme.EndRed
import kotlinx.coroutines.delay
import android.util.Log

@Composable
fun CallScreen(vm: SipViewModel, session: CallSession) {
    val liveCallSession by vm.callSession.collectAsState()
    // The navigation argument is only the initial snapshot. Once the engine
    // clears its live session after a remote BYE, never render that stale copy.
    val activeSession = liveCallSession ?: return
    
    // Bail out only when the call has fully ended (session null). We intentionally
    // do NOT compare callIds — vm.callSession IS SipEngine.callSession, so a
    // strict id check would blank-flash the screen during CALLING/INCOMING →
    // CONFIRMED transitions.
    if (activeSession.state == CallState.DISCONNECTED || activeSession.state == CallState.IDLE) {
        Log.d("CallScreen", "Call ended, not rendering for callId=${activeSession.callId}")
        return
    }

    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val audioDeviceMode by vm.audioDeviceMode.collectAsState()

    val account = accounts.firstOrNull { it.id == activeSession.accountId }
    val simLabel = account?.displayName ?: ""

    // Contact matching logic using pre-computed index
    val contact = remember(activeSession.remoteUri, contacts) {
        val cleanNumber = vm.cleanUri(activeSession.remoteUri)
        val cleanedSessionUriDigits = cleanNumber.filter { it.isDigit() }
        if (cleanedSessionUriDigits.length < 3) { // Only attempt contact match for numbers with at least 3 digits
            null
        } else {
            vm.findContactByNumber(cleanNumber)
        }
    }
    val displayName = contact?.name ?: vm.cleanDisplayName(activeSession.remoteDisplayName, activeSession.remoteUri)

    var showDialpad by remember { mutableStateOf(false) }
    var elapsedSeconds by remember(activeSession.callId) { mutableLongStateOf(0L) }

    val isActive = activeSession.state == CallState.CONFIRMED

    // Check for Bluetooth devices when call is active
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(isActive) {
        if (isActive) {
            vm.updateBluetoothAvailability()
            // Haptic feedback on call connect
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    val textColor = MaterialTheme.colorScheme.onBackground
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Call timer — ticks every second while the live session is CONFIRMED.
    // Keyed on callId so elapsedSeconds resets to 0 for each new call.
    LaunchedEffect(activeSession.callId) {
        // Wait for the call to become active before ticking
        while (liveCallSession?.state != CallState.CONFIRMED) {
            delay(200)
            if (liveCallSession == null) return@LaunchedEffect
        }
        while (liveCallSession?.state == CallState.CONFIRMED) {
            delay(1000)
            elapsedSeconds++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            CallHeader(
                displayName = displayName,
                number = vm.cleanUri(activeSession.remoteUri),
                simLabel = simLabel,
                isActive = isActive,
                state = activeSession.state,
                elapsedSeconds = elapsedSeconds,
                audioDeviceMode = audioDeviceMode,
                contactPhotoUri = contact?.photoUri,
                avatarName = displayName,
                textColor = textColor,
                subtitleColor = subtitleColor
            )

            Spacer(Modifier.weight(1f))

            // ── Call controls ─────────────────────────────────────────────
            AnimatedContent(targetState = showDialpad, label = "dialpad_toggle") { showDp ->
                if (showDp) {
                    InCallDialpad(vm = vm) {
                        showDialpad = false
                    }
                } else {
                    CallControls(
                        session = activeSession,
                        isActive = isActive,
                        onKeypad = { showDialpad = true },
                        onMute = { vm.toggleMute() },
                        onSpeaker = { vm.cycleAudioDevice() },
                        onRecord = { vm.toggleRecording() },
                        audioDeviceMode = audioDeviceMode
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Pill-shaped end-call action remains prominent without dominating the screen.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .width(160.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(EndRed)
                    .then(Modifier.clickableNoRipple { vm.hangup() })
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("End call", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CallHeader(
    displayName: String,
    number: String,
    simLabel: String,
    isActive: Boolean,
    state: CallState,
    elapsedSeconds: Long,
    audioDeviceMode: AudioDeviceMode,
    contactPhotoUri: android.net.Uri?,
    avatarName: String,
    textColor: Color,
    subtitleColor: Color
) {
    val transition = rememberInfiniteTransition(label = "avatar_glow")
    val glowScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "glow_scale"
    )
    val avatarColors = listOf(
        Color(0xFF1E6B3C), Color(0xFF1769AA), Color(0xFF8E4A9B), Color(0xFFB05A2B), Color(0xFF4E5D8A)
    )
    val avatarColor = avatarColors[(avatarName.hashCode() and Int.MAX_VALUE) % avatarColors.size]
    val routeLabel = when (audioDeviceMode) {
        AudioDeviceMode.SPEAKER -> "Speaker"
        AudioDeviceMode.BLUETOOTH -> "Bluetooth"
        AudioDeviceMode.EARPIECE -> "Earpiece"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (simLabel.isNotBlank()) {
                Text(simLabel, style = MaterialTheme.typography.labelLarge, color = subtitleColor)
                Spacer(Modifier.height(4.dp))
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(152.dp)) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .scale(glowScale)
                            .border(10.dp, Color(0xFF35B978).copy(alpha = 0.18f), CircleShape)
                    )
                    AudioWaveform(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF35B978).copy(alpha = 0.65f)
                    )
                } else {
                    // Animated circular ripple around avatar while dialing (before call is received).
                    RippleRings()
                }
                ContactAvatar(
                    name = avatarName,
                    photoUri = contactPhotoUri,
                    size = 116.dp,
                    backgroundColor = avatarColor,
                    contentColor = Color.White,
                    modifier = Modifier.border(3.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (number.isNotBlank() && displayName != number) {
                Text(number, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
            }
            Spacer(Modifier.height(8.dp))
            if (isActive) {
                Text(formatDuration(elapsedSeconds), style = MaterialTheme.typography.headlineMedium, color = textColor, fontWeight = FontWeight.Bold)
                Text("Connected  •  $routeLabel", style = MaterialTheme.typography.labelMedium, color = subtitleColor)
            } else {
                PulsingStateLabel(state)
            }
        }
    }
}

/**
 * Animated concentric circular ripples that expand outward from the avatar
 * while the call is in the dialing / ringing phase.
 */
@Composable
private fun RippleRings() {
    val transition = rememberInfiniteTransition(label = "dialing_ripple")

    // Ring 1 — expands & fades, immediately followed by ring 2.
    val ring1Scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "ring1_scale"
    )
    val ring1Alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "ring1_alpha"
    )
    val ring2Scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(tween(1600, delayMillis = 800), RepeatMode.Restart),
        label = "ring2_scale"
    )
    val ring2Alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1600, delayMillis = 800), RepeatMode.Restart),
        label = "ring2_alpha"
    )

    // Two concentric rings expand outward from the avatar with a staggered delay
    // to create a continuous "ping" ripple effect.
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(152.dp)) {
        RippleRing(scale = ring1Scale, alpha = ring1Alpha, ringColor = Color(0xFF35B978))
        RippleRing(scale = ring2Scale, alpha = ring2Alpha, ringColor = Color(0xFF35B978))
    }
}

@Composable
private fun RippleRing(scale: Float, alpha: Float, ringColor: Color) {
    Box(
        modifier = Modifier
            .size(116.dp)
            .scale(scale)
            .border(3.dp, ringColor.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun AudioWaveform(modifier: Modifier = Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "audio_waveform")
    Row(
        modifier = modifier.height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(9) { index ->
            val height by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(700, delayMillis = index * 70),
                    RepeatMode.Reverse
                ),
                label = "wave_$index"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((42 * height).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
