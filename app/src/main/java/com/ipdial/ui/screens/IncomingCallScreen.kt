package com.ipdial.ui.screens

import androidx.compose.animation.core.*
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.IncomingCallMode
import com.ipdial.data.model.ThemeMode
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.EndRed
import com.ipdial.ui.theme.ForestGreen
import kotlin.math.roundToInt

@Composable
fun IncomingCallScreen(vm: SipViewModel, session: CallSession) {
    if (session.state == CallState.DISCONNECTED || session.state == CallState.IDLE) return
    // Safety: also check the live ViewModel session — bail out if already gone.
    // (vm.callSession IS SipEngine.callSession, so this covers engine staleness.)
    val liveSession by vm.callSession.collectAsState()
    if (liveSession == null || liveSession?.state == CallState.DISCONNECTED) {
        return
    }
    Log.d("IncomingCallScreen", "Rendering IncomingCallScreen for ${session.remoteUri}")
    val accounts by vm.accounts.collectAsState()
    
    val account = accounts.firstOrNull { it.id == session.accountId }
    val viaLine  = account?.label?.ifBlank { account.domain } ?: "SIP"
    
    val contact = remember(session.remoteUri) {
        vm.findContactByNumber(session.remoteUri)
    }
    val displayName = contact?.name ?: vm.cleanDisplayName(session.remoteDisplayName, session.remoteUri)

    val callsCardsEnabled by vm.callingCardsEnabled.collectAsState()
    val incomingCallMode by vm.incomingCallMode.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val isDarkOrObsidian = themeMode == ThemeMode.Dark || themeMode == ThemeMode.Obsidian
    val isFullScreenPhoto = callsCardsEnabled && contact?.photoUri != null
    val textColor = if (isFullScreenPhoto) Color.White else MaterialTheme.colorScheme.onBackground
    val subtitleColor = if (isFullScreenPhoto) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxSize()) {
        if (isFullScreenPhoto) {
            AsyncImage(
                model = contact!!.photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(4.dp)
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
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(80.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Incoming Call via $viaLine",
                    style = MaterialTheme.typography.titleMedium.copy(
                        shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
                    ),
                    color = subtitleColor,
                )
            }

            Spacer(Modifier.height(40.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (displayName.length > 12) 30.sp else 40.sp,
                    shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(2f, 2f), 8f) else null
                ),
                textAlign = TextAlign.Center,
                color = textColor,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            if (displayName != vm.cleanUri(session.remoteUri)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = vm.cleanUri(session.remoteUri),
                    style = MaterialTheme.typography.titleLarge.copy(
                        shadow = if (isFullScreenPhoto) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
                    ),
                    color = subtitleColor
                )
            }

            if (!isFullScreenPhoto && contact != null) {
                Spacer(Modifier.height(48.dp))
                com.ipdial.ui.components.ContactAvatar(
                    name = displayName,
                    photoUri = contact.photoUri,
                    size = 160.dp,
                    onClick = null
                )
            }
        }

        if (incomingCallMode == IncomingCallMode.Slider) {
            var offsetX by remember { mutableFloatStateOf(0f) }
            val density = LocalDensity.current
            val dragRange = with(density) { 110.dp.toPx() }
            val swipeThreshold = dragRange * 0.5f

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(
                            if (isFullScreenPhoto)
                                Color.White.copy(alpha = 0.3f)
                            else
                                Color.White.copy(alpha = 0.18f)
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(40.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "iconScale")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "iconScale"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = null,
                            tint = if (offsetX < -40) EndRed else EndRed.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp).let { if (offsetX <= 0) it.graphicsLayer(scaleX = scale, scaleY = scale) else it }
                        )
                        Icon(
                            Icons.Default.Call,
                            contentDescription = null,
                            tint = if (offsetX > 40) ForestGreen else ForestGreen.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp).let { if (offsetX >= 0) it.graphicsLayer(scaleX = scale, scaleY = scale) else it }
                        )
                    }

                    val infiniteRipple = rememberInfiniteTransition(label = "ripple")
                    val rippleScale by infiniteRipple.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rippleScale"
                    )
                    val rippleAlpha by infiniteRipple.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rippleAlpha"
                    )

                    Box(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }, contentAlignment = Alignment.Center) {
                        if (offsetX == 0f) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .graphicsLayer(scaleX = rippleScale, scaleY = rippleScale, alpha = rippleAlpha)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .shadow(elevation = 12.dp, shape = CircleShape)
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        offsetX < -40 -> EndRed
                                        offsetX > 40 -> ForestGreen
                                        isDarkOrObsidian -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                                        isFullScreenPhoto -> Color.White.copy(alpha = 0.3f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .border(2.dp, if (isDarkOrObsidian) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.5f), CircleShape)
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state = rememberDraggableState { delta ->
                                        offsetX = (offsetX + delta).coerceIn(-dragRange, dragRange)
                                    },
                                    onDragStopped = {
                                        when {
                                            offsetX >= swipeThreshold -> vm.answerCall()
                                            offsetX <= -swipeThreshold -> vm.hangup()
                                        }
                                        offsetX = 0f
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    offsetX < -40 -> Icons.Default.CallEnd
                                    else -> Icons.Default.Call
                                },
                                contentDescription = null,
                                tint = if (isDarkOrObsidian) Color.White else when {
                                    offsetX < -40 -> EndRed
                                    else -> ForestGreen
                                },
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }
        }

        if (incomingCallMode == IncomingCallMode.Buttons) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { vm.hangup() },
                            modifier = Modifier
                                .shadow(8.dp, CircleShape)
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(EndRed)
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Decline",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isFullScreenPhoto) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { vm.answerCall() },
                            modifier = Modifier
                                .shadow(8.dp, CircleShape)
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(ForestGreen)
                        ) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = "Answer",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Answer",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isFullScreenPhoto) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
