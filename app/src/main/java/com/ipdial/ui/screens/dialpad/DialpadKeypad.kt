package com.ipdial.ui.screens.dialpad

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadKeypad(
    keys: List<Triple<String, String, Nothing?>>,
    design: KeypadDesign,
    onKeyPress: (Char) -> Unit,
    onZeroLongPress: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isGlass = LocalGlassMode.current != GlassMode.None

    if (design == KeypadDesign.Rounded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 64.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            keys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    row.forEach { (digit, sub, _) ->
                        DialKeyRounded(
                            digit = digit,
                            subLabel = sub,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onKeyPress(digit[0])
                            },
                            onLongClick = if (digit == "0") {
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onZeroLongPress()
                                }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp)
                .background(if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            keys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    row.forEachIndexed { colIndex, (digit, sub, _) ->
                        DialKey(
                            digit = digit,
                            subLabel = sub,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onKeyPress(digit[0])
                            },
                            onLongClick = if (digit == "0") {
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onZeroLongPress()
                                }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                        if (colIndex < 2) {
                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxHeight().width(1.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKeyRounded(
    digit: String,
    subLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    Box(
        modifier = modifier.height(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .size(64.dp)
                .then(if (isGlass) Modifier.glass(CircleShape) else Modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = digit,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 32.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 32.sp
                    )
                    if (subLabel.isNotBlank() && digit.any { it.isDigit() }) {
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKey(
    digit: String,
    subLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    Box(
            modifier = modifier
            .height(52.dp)
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(0.dp), borderWidth = 0.5.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}
