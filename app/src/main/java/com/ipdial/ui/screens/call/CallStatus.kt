package com.ipdial.ui.screens.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import com.ipdial.data.model.CallState

@Composable
fun PulsingStateLabel(state: CallState) {
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

    Text(
        text = label,
        style = MaterialTheme.typography.titleLarge.copy(
            shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)
        ),
        color = color.copy(alpha = alpha),
    )
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
