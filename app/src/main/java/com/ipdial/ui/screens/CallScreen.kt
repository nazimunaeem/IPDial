package com.ipdial.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.screens.call.CallControls
import com.ipdial.ui.screens.call.InCallDialpad
import com.ipdial.ui.screens.call.PulsingStateLabel
import com.ipdial.ui.screens.call.formatDuration
import com.ipdial.ui.theme.EndRed
import kotlinx.coroutines.delay

@Composable
fun CallScreen(vm: SipViewModel, session: CallSession) {
    val liveCallSession by vm.callSession.collectAsState()
    val activeSession = liveCallSession ?: session

    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val audioDeviceMode by vm.audioDeviceMode.collectAsState()

    val account = accounts.firstOrNull { it.id == activeSession.accountId }
    val simLabel = account?.displayName ?: ""

    // Contact matching logic using pre-computed index
    val contact = remember(activeSession.remoteUri, contacts) {
        val cleanedSessionUriDigits = vm.cleanUri(activeSession.remoteUri).filter { it.isDigit() }
        if (cleanedSessionUriDigits.length < 3) { // Only attempt contact match for numbers with at least 3 digits
            null
        } else {
            vm.findContactByNumber(activeSession.remoteUri)
        }
    }
    val displayName = contact?.name ?: vm.cleanDisplayName(activeSession.remoteDisplayName, activeSession.remoteUri)

    var showDialpad by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    val isActive = activeSession.state == CallState.CONFIRMED

    // Check for Bluetooth devices when call is active
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(isActive) {
        if (isActive) {
            vm.updateBluetoothAvailability()
            // Haptic feedback on call connect
            val activity = context as? android.app.Activity
            activity?.window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    val callsCardsEnabled by vm.callingCardsEnabled.collectAsState()
    val isFullScreenPhoto = callsCardsEnabled && contact?.photoUri != null
    val textColor = if (isFullScreenPhoto) Color.White else MaterialTheme.colorScheme.onBackground
    val subtitleColor = if (isFullScreenPhoto) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant

    // Call timer — uses live session from ViewModel so it stops when remote hangs up
    LaunchedEffect(activeSession) {
        if (isActive) {
            while (liveCallSession?.state == CallState.CONFIRMED) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isFullScreenPhoto) {
            AsyncImage(
                model = contact.photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(12.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.9f))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp)) // Increased from 48

            // Via label
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = simLabel,
                    style = MaterialTheme.typography.bodyLarge.copy( // Increased from bodyMedium
                        shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
                    ),
                    color = subtitleColor,
                )
            }

            Spacer(Modifier.height(16.dp)) // Increased from 8

            // Caller name / number
            Text(
                text = displayName,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.SemiBold, // Increased weight
                    fontSize = if (displayName.length > 12) 30.sp else 42.sp,
                    shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(2f, 2f), 8f) else null
                ),
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 1
            )

            if (displayName != vm.cleanUri(activeSession.remoteUri)) {
                Spacer(Modifier.height(8.dp)) // Increased from 4
                Text(
                    text = vm.cleanUri(activeSession.remoteUri),
                    style = MaterialTheme.typography.titleMedium.copy( // Increased from bodyMedium
                        shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
                    ),
                    color = subtitleColor,
                )
            }

            // State label (ringing / connecting) / Duration
            Spacer(Modifier.height(16.dp)) // Increased from 8
            if (isActive) {
                Text(
                    text = formatDuration(elapsedSeconds),
                    style = MaterialTheme.typography.headlineMedium.copy( // Increased from bodyLarge
                        fontWeight = FontWeight.Bold,
                        shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
                    ),
                    color = textColor
                )
                // Show negotiated codec
                if (activeSession.negotiatedCodec != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = activeSession.negotiatedCodec!!.split("/").first().uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = subtitleColor
                    )
                }
            } else {
                PulsingStateLabel(activeSession.state)
            }

            // Avatar circle
            if (!isFullScreenPhoto && contact?.photoUri != null) {
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = contact.photoUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

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

            // End call button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(bottom = 80.dp)
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
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "End Call",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
