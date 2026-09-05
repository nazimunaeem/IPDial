package com.ipdial.ui.screens.dialpad

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.theme.ForestGreen
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadKeypad(
    keys: List<Triple<String, String, Nothing?>>,
    design: KeypadDesign,
    onKeyPress: (Char) -> Unit,
    onZeroLongPress: () -> Unit,
    onCallClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val isGlass = LocalGlassMode.current != GlassMode.None
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp > 600
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = if (isWide) (configuration.screenWidthDp * 0.2f).dp else 64.dp
    val keySize = if (isWide) 80.dp else if (isLandscape) 52.dp else 68.dp
    val gridHeight = if (isWide) 64.dp else if (isLandscape) 40.dp else 52.dp
    val rowSpacing = if (isWide) 16.dp else if (isLandscape) 6.dp else 10.dp

    if (design == KeypadDesign.Rounded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isLandscape) (configuration.screenWidthDp * 0.3f).dp else horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing)
        ) {
            keys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    row.forEach { (digit, sub, _) ->
                        DialKeyRounded(
                            digit = digit,
                            subLabel = sub,
                            keySize = keySize,
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
    } else if (design == KeypadDesign.Ring) {
        // Rotary dial: 0 at bottom, with * and # flanking it.
        val angularPos = mapOf(
            "1" to 150f, "2" to 180f, "3" to 210f, "4" to 240f,
            "5" to 270f, "6" to 300f, "7" to 330f, "8" to 0f, "9" to 30f,
            "#" to 60f, "0" to 90f, "*" to 120f
        )
        val rotarySizePx = if (isWide) 360f else if (isLandscape) 270f else 310f
        val rotarySize = rotarySizePx.dp
        val keyRadius = if (isWide) 58.dp else if (isLandscape) 46.dp else 56.dp
        val centerSize = if (isWide) 100.dp else 92.dp
        val ringPadding = (if (isLandscape) 14f else 16f).dp
        val orbitRadius = (rotarySizePx / 2f - keyRadius.value / 2f - ringPadding.value).dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(rotarySize),
            contentAlignment = Alignment.Center
        ) {
            keys.forEachIndexed { index, (digit, sub, _) ->
                val angleDeg = angularPos[digit] ?: (index * (360f / keys.size))
                val radians = Math.toRadians(angleDeg.toDouble())
                val dx = (kotlin.math.cos(radians) * orbitRadius.value).toFloat().dp
                val dy = (kotlin.math.sin(radians) * orbitRadius.value).toFloat().dp

                RotaryKey(
                    digit = digit,
                    subLabel = sub,
                    keySize = keyRadius,
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
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = dx, y = dy)
                )
            }

            // Central green call button
            Surface(
                onClick = { onCallClick?.invoke() },
                shape = CircleShape,
                color = ForestGreen,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(centerSize)
                    .shadow(elevation = if (isGlass) 16.dp else 12.dp, shape = CircleShape)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call",
                        tint = Color.White,
                        modifier = Modifier.size(centerSize * 0.42f)
                    )
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
                        .height(gridHeight)
                ) {
                    row.forEachIndexed { colIndex, (digit, sub, _) ->
                        DialKey(
                            digit = digit,
                            subLabel = sub,
                            height = gridHeight,
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
    keySize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    Box(
        modifier = modifier.height(keySize),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .size(keySize)
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
                            fontSize = if (keySize > 70.dp) 38.sp else 32.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = if (keySize > 70.dp) 38.sp else 32.sp
                    )
                    if (subLabel.isNotBlank() && digit.any { it.isDigit() }) {
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                            fontSize = if (keySize > 70.dp) 14.sp else 12.sp,
                            lineHeight = if (keySize > 70.dp) 14.sp else 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RotaryKey(
    digit: String,
    subLabel: String,
    keySize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    val ringColor = if (isGlass) Color.White.copy(alpha = 0.30f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Box(
        modifier = modifier.size(keySize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (isGlass) Color.Transparent
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    CircleShape
                )
                .border(2.dp, ringColor, CircleShape)
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
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (keySize > 45.dp) 24.sp else 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = if (keySize > 45.dp) 24.sp else 20.sp
                )
                if (subLabel.isNotBlank() && digit.any { it.isDigit() }) {
                    Text(
                        text = subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        fontSize = if (keySize > 45.dp) 10.sp else 9.sp,
                        lineHeight = if (keySize > 45.dp) 10.sp else 9.sp
                    )
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
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    Box(
        modifier = modifier
            .height(height)
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
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = if (height > 60.dp) 28.sp else 24.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = if (height > 60.dp) 12.sp else 10.sp
                )
            }
        }
    }
}
