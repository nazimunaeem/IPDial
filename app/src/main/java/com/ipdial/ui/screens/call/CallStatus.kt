package com.ipdial.ui.screens.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.CallState

@Composable
fun PulsingStateLabel(state: CallState, showShadow: Boolean = false) {
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

    val color = when (state) {
        CallState.INCOMING -> MaterialTheme.colorScheme.primary
        CallState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onBackground
    }

    val style = MaterialTheme.typography.titleLarge.copy(
        shadow = if (showShadow) Shadow(Color.Black, Offset(1f, 1f), 4f) else null
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = style,
            color = color.copy(alpha = alpha)
        )
        // Fixed width box for dots to prevent layout jitter
        Box(modifier = Modifier.width(24.dp)) {
            Row {
                Text(text = ".", style = style, color = color.copy(alpha = dot1Alpha * alpha))
                Text(text = ".", style = style, color = color.copy(alpha = dot2Alpha * alpha))
                Text(text = ".", style = style, color = color.copy(alpha = dot3Alpha * alpha))
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
