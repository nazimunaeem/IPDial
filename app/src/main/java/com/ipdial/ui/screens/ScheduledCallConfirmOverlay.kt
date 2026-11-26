package com.ipdial.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.scheduler.ScheduledCall
import com.ipdial.data.scheduler.ScheduledCallSwipeAction
import com.ipdial.data.scheduler.resolveVerticalSlideAction
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.theme.EndRed
import com.ipdial.ui.theme.ForestGreen
import kotlin.math.roundToInt

/**
 * Full-screen confirmation overlay shown when a scheduled call fires.
 * User must slide UP to confirm & call, or slide DOWN to reject.
 */
@Composable
fun ScheduledCallConfirmOverlay(
    vm: SipViewModel,
    call: ScheduledCall,
    onConfirmCall: (ScheduledCall) -> Unit,
    onRejectCall: (ScheduledCall) -> Unit,
    onSnoozeCall: (ScheduledCall) -> Unit
) {
    // Prevent accidental system back dismiss while alert is actively ringing/vibrating
    BackHandler { /* Intentional: use vertical slider or Snooze */ }

    val contacts by vm.contacts.collectAsState()
    val resolvedContact = remember(call.phoneNumber, contacts) {
        vm.findContactByNumber(call.phoneNumber, contacts)
    }
    val displayTitle = resolvedContact?.name?.ifBlank { null } ?: call.displayTitle()

    val infiniteTransition = rememberInfiniteTransition(label = "scheduled_alert")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ring_scale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ring_alpha"
    )
    val chevronPulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chevron_pulse"
    )

    var offsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dragRange = with(density) { 86.dp.toPx() }
    val swipeThreshold = dragRange * 0.52f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A1912),
                        Color(0xFF10241A),
                        Color(0xFF090D0B)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status & Contact Identity
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = ForestGreen.copy(alpha = 0.25f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Vibration,
                            contentDescription = null,
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "SCHEDULED CALL DUE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBBF7D0),
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(144.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .scale(ringScale)
                            .border(3.dp, Color(0xFF35B978).copy(alpha = ringAlpha), CircleShape)
                    )
                    ContactAvatar(
                        name = displayTitle,
                        photoUri = resolvedContact?.photoUri,
                        size = 112.dp,
                        backgroundColor = ForestGreen,
                        contentColor = Color.White,
                        modifier = Modifier.border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = if (displayTitle.length > 14) 28.sp else 34.sp
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (displayTitle != call.phoneNumber) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = call.phoneNumber,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }

                if (!call.lineLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Via ${call.lineLabel}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF86EFAC)
                    )
                }

                if (call.note.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = call.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Centerpiece: Vertical Slider (Slide UP to Call, Slide DOWN to Reject)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                val draggingUp = offsetY < -24f
                val draggingDown = offsetY > 24f

                Text(
                    text = when {
                        offsetY <= -swipeThreshold -> "Release to place call"
                        offsetY >= swipeThreshold -> "Release to reject"
                        else -> "Slide UP to call • Slide DOWN to reject"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        draggingUp -> Color(0xFF4ADE80)
                        draggingDown -> Color(0xFFFCA5A5)
                        else -> Color.White.copy(alpha = 0.85f)
                    },
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .width(116.dp)
                        .height(268.dp)
                        .clip(RoundedCornerShape(58.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(58.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // UP = Confirm Call
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = if (offsetY <= 0f) {
                                Modifier.graphicsLayer(scaleX = chevronPulse, scaleY = chevronPulse)
                            } else Modifier
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Slide up to confirm call",
                                tint = if (draggingUp) Color(0xFF4ADE80) else Color(0xFF4ADE80).copy(alpha = 0.7f),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "CALL",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (draggingUp) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.72f)
                            )
                        }

                        // DOWN = Reject
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = if (offsetY >= 0f) {
                                Modifier.graphicsLayer(scaleX = chevronPulse, scaleY = chevronPulse)
                            } else Modifier
                        ) {
                            Text(
                                text = "REJECT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (draggingDown) Color(0xFFF87171) else Color.White.copy(alpha = 0.72f)
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Slide down to reject call",
                                tint = if (draggingDown) Color(0xFFF87171) else Color(0xFFF87171).copy(alpha = 0.7f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Draggable Center Handle
                    val thumbColor = when {
                        draggingUp -> ForestGreen
                        draggingDown -> EndRed
                        else -> Color.White.copy(alpha = 0.94f)
                    }
                    val thumbIconTint = if (draggingUp || draggingDown) Color.White else ForestGreen

                    val rippleTransition = rememberInfiniteTransition(label = "thumb_ripple")
                    val rippleScale by rippleTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.55f,
                        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                        label = "thumb_ripple_scale"
                    )
                    val rippleAlpha by rippleTransition.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                        label = "thumb_ripple_alpha"
                    )

                    Box(
                        modifier = Modifier.offset { IntOffset(0, offsetY.roundToInt()) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (offsetY == 0f) {
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .graphicsLayer(scaleX = rippleScale, scaleY = rippleScale, alpha = rippleAlpha)
                                    .clip(CircleShape)
                                    .background(ForestGreen)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .shadow(elevation = 14.dp, shape = CircleShape)
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(thumbColor)
                                .border(2.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                                .testTag("scheduled_call_vertical_slider")
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    state = rememberDraggableState { delta ->
                                        offsetY = (offsetY + delta).coerceIn(-dragRange, dragRange)
                                    },
                                    onDragStopped = {
                                        when (resolveVerticalSlideAction(offsetY, swipeThreshold)) {
                                            ScheduledCallSwipeAction.CONFIRM_UP -> onConfirmCall(call)
                                            ScheduledCallSwipeAction.REJECT_DOWN -> onRejectCall(call)
                                            ScheduledCallSwipeAction.NONE -> Unit
                                        }
                                        offsetY = 0f
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (draggingDown) Icons.Default.CallEnd else Icons.Default.Call,
                                contentDescription = "Vertical call confirmation slider",
                                tint = thumbIconTint,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onSnoozeCall(call) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scheduled_call_snooze_button")
                ) {
                    Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Snooze 5 min")
                }
            }
        }
    }
}
