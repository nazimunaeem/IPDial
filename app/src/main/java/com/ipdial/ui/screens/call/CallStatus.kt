package com.ipdial.ui.screens.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.CallState

@Composable
fun HdAudioBadge(
    label: String = "HD",
    photoMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val containerColor = if (photoMode) {
        Color.White.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (photoMode) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val border = if (photoMode) {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    }

    Surface(
        modifier = modifier
            .testTag("hd_audio_badge")
            .semantics { contentDescription = "$label Voice active" },
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = border
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Hd,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
            if (label != "HD") {
                Text(
                    text = label.removePrefix("HD · ").removePrefix("HD"),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    ),
                    color = contentColor
                )
            }
        }
    }
}

@Composable
fun CompactHdBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .testTag("call_history_hd_badge")
            .semantics { contentDescription = "HD Audio" },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Text(
            text = "HD",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun PulsingStateLabel(
    state: CallState,
    showShadow: Boolean = false,
    textColor: Color? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statePulse")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Dot animations for "walking" effect
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    val label = when (state) {
        CallState.CALLING -> "Calling"
        CallState.INCOMING -> "Incoming"
        CallState.EARLY -> "Ringing"
        CallState.CONNECTING -> "Connecting"
        else -> ""
    }

    val color = textColor ?: when (state) {
        CallState.INCOMING -> MaterialTheme.colorScheme.primary
        CallState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onBackground
    }

    val style = MaterialTheme.typography.titleLarge.copy(
        shadow = if (showShadow) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
    )

    // Center the label; dots sit immediately to its right without
    // shifting the label away from center.
    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.width(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Invisible dots on the left to balance the visible ones on the right
            Box(modifier = Modifier.width(24.dp))
            Text(
                text = label,
                style = style,
                color = color.copy(alpha = alpha)
            )
            // Visible animated dots on the right
            Box(modifier = Modifier.width(24.dp)) {
                Row {
                    Text(text = ".", style = style, color = color.copy(alpha = dot1Alpha * alpha))
                    Text(text = ".", style = style, color = color.copy(alpha = dot2Alpha * alpha))
                    Text(text = ".", style = style, color = color.copy(alpha = dot3Alpha * alpha))
                }
            }
        }
    }
}


fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
