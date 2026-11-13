package com.ipdial.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Log

@Composable
fun CallScreen(vm: SipViewModel, session: CallSession) {
    val liveCallSession by vm.callSession.collectAsState()
    val isEndingCall by vm.isEndingCall.collectAsState()
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
    val fullScreenPhotoEnabled by vm.fullScreenContactPhoto.collectAsState()

    val account = accounts.firstOrNull { it.id == activeSession.accountId }
    val simLabel = account?.displayName ?: ""

    // Contact matching logic using pre-computed index with contacts fallback
    val contact = remember(activeSession.remoteUri, contacts) {
        val cleanNumber = vm.cleanUri(activeSession.remoteUri)
        val cleanedSessionUriDigits = cleanNumber.filter { it.isDigit() }
        if (cleanedSessionUriDigits.length < 3) { // Only attempt contact match for numbers with at least 3 digits
            null
        } else {
            vm.findContactByNumber(cleanNumber, contacts)
        }
    }
    val displayName = contact?.name ?: vm.cleanDisplayName(activeSession.remoteDisplayName, activeSession.remoteUri)

    // Resolve the contact's hi-res photo for full-screen mode off the main
    // thread. Full-screen is used only when the setting is ON and a photo
    // exists; otherwise we keep the normal avatar display.
    val context = LocalContext.current
    var fullScreenPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(contact?.id, contact?.photoUri, fullScreenPhotoEnabled) {
        fullScreenPhotoUri = if (contact != null && fullScreenPhotoEnabled) {
            withContext(Dispatchers.IO) {
                com.ipdial.util.ContactPhotoUtil.resolveFullScreenUri(context, contact.id, contact.photoUri)
            }
        } else null
    }
    val showFullScreenPhoto = fullScreenPhotoEnabled && fullScreenPhotoUri != null

    var showDialpad by remember { mutableStateOf(false) }
    var elapsedSeconds by remember(activeSession.callId) { mutableLongStateOf(0L) }

    val isActive = activeSession.state == CallState.CONFIRMED

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Check for Bluetooth devices when call is active
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(isActive) {
        if (isActive) {
            vm.updateBluetoothAvailability()
            // Haptic feedback on call connect
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    val textColor = if (showFullScreenPhoto) Color.White else MaterialTheme.colorScheme.onBackground
    val subtitleColor = if (showFullScreenPhoto) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant

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
        modifier = Modifier.fillMaxSize()
    ) {
        // Background: full-screen hi-res contact photo when enabled, else the
        // theme background. Includes gentle breathing zoom and contrast gradient scrim.
        if (showFullScreenPhoto) {
            val photoTransition = rememberInfiniteTransition(label = "fullscreenPhotoZoom")
            val photoScale by photoTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 5000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "photoScale"
            )

            coil.compose.AsyncImage(
                model = fullScreenPhotoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = photoScale, scaleY = photoScale)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.82f)
                            )
                        )
                    )
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
        if (isLandscape) {
            // ── Landscape: two-pane layout. Header left, DTMF keypad / call
            // controls + end-call right, so the keypad stays usable on short,
            // wide screens and tablets.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, end = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
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
                        subtitleColor = subtitleColor,
                        photoMode = showFullScreenPhoto,
                        compact = true
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .padding(start = 4.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.weight(1f))
                    AnimatedContent(targetState = showDialpad, label = "dialpad_toggle_landscape") { showDp ->
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
                                audioDeviceMode = audioDeviceMode,
                                photoMode = showFullScreenPhoto
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Pill-shaped end-call action remains prominent without dominating the screen.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(160.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(EndRed)
                            .clickableNoRipple {
                                if (isEndingCall) vm.forceEndCall() else vm.hangup()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isEndingCall) "Ending…" else "End call", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
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
                subtitleColor = subtitleColor,
                photoMode = showFullScreenPhoto
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
                        audioDeviceMode = audioDeviceMode,
                        photoMode = showFullScreenPhoto
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
                    .clickableNoRipple {
                        if (isEndingCall) vm.forceEndCall() else vm.hangup()
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isEndingCall) "Ending…" else "End call", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
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
    subtitleColor: Color,
    photoMode: Boolean = false,
    compact: Boolean = false
) {
    val avatarSize = if (compact) 84.dp else 116.dp
    val ringFrameSize = if (compact) 112.dp else 152.dp
    val glowRingSize = if (compact) 96.dp else 132.dp
    val glowRingWidth = if (compact) 8.dp else 10.dp

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

    // In full-screen photo mode, the circular avatar is hidden to avoid redundancy
    // with the full-screen photo background. Text shadow ensures legibility on any image.
    val textShadow = if (photoMode) Shadow(
        color = Color.Black.copy(alpha = 0.85f),
        offset = Offset(0f, 2f),
        blurRadius = 6f
    ) else null

    val headerContent: @Composable () -> Unit = {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (simLabel.isNotBlank()) {
                Text(
                    simLabel,
                    style = MaterialTheme.typography.labelLarge.copy(shadow = textShadow),
                    color = subtitleColor
                )
                Spacer(Modifier.height(4.dp))
            }

            if (!photoMode) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringFrameSize)) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(glowRingSize)
                                .scale(glowScale)
                                .border(glowRingWidth, Color(0xFF35B978).copy(alpha = 0.18f), CircleShape)
                        )
                        AudioWaveform(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF35B978).copy(alpha = 0.65f),
                            waveHeight = if (compact) 28.dp else 42.dp
                        )
                    } else {
                        // Animated circular ripple around avatar while dialing (before call is received).
                        RippleRings(ringFrameSize = ringFrameSize, innerSize = avatarSize)
                    }
                    ContactAvatar(
                        name = avatarName,
                        photoUri = contactPhotoUri,
                        size = avatarSize,
                        backgroundColor = avatarColor,
                        contentColor = Color.White,
                        modifier = Modifier.border(if (compact) 2.dp else 3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    )
                }
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            } else {
                Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    shadow = textShadow
                ),
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (number.isNotBlank() && displayName != number) {
                Text(
                    number,
                    style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow),
                    color = subtitleColor
                )
            }
            Spacer(Modifier.height(8.dp))
            if (isActive) {
                Text(
                    formatDuration(elapsedSeconds),
                    style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).copy(
                        fontWeight = FontWeight.Bold,
                        shadow = textShadow
                    ),
                    color = textColor
                )
                Text(
                    "Connected  •  $routeLabel",
                    style = MaterialTheme.typography.labelMedium.copy(shadow = textShadow),
                    color = subtitleColor
                )
            } else {
                PulsingStateLabel(
                    state = state,
                    showShadow = photoMode,
                    textColor = if (photoMode) Color.White else null
                )
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
        color = if (photoMode) Color.Black.copy(alpha = 0.48f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = if (photoMode) BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)) else null,
        tonalElevation = if (photoMode) 0.dp else 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = if (compact) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            headerContent()
        }
    }
}

/**
 * Animated concentric circular ripples that expand outward from the avatar
 * while the call is in the dialing / ringing phase.
 */
@Composable
private fun RippleRings(ringFrameSize: androidx.compose.ui.unit.Dp = 152.dp, innerSize: androidx.compose.ui.unit.Dp = 116.dp) {
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
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringFrameSize)) {
        RippleRing(scale = ring1Scale, alpha = ring1Alpha, ringColor = Color(0xFF35B978), size = innerSize)
        RippleRing(scale = ring2Scale, alpha = ring2Alpha, ringColor = Color(0xFF35B978), size = innerSize)
    }
}

@Composable
private fun RippleRing(scale: Float, alpha: Float, ringColor: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .border(3.dp, ringColor.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun AudioWaveform(modifier: Modifier = Modifier, color: Color, waveHeight: androidx.compose.ui.unit.Dp = 42.dp) {
    val transition = rememberInfiniteTransition(label = "audio_waveform")
    Row(
        modifier = modifier.height(waveHeight),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(9) { index ->
            val fraction by transition.animateFloat(
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
                    .height((waveHeight.value * fraction).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
