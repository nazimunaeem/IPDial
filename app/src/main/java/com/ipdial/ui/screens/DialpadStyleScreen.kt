package com.ipdial.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.SipViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadStyleScreen(
    vm: SipViewModel,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    val keypadDesign by vm.keypadDesign.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dialpad Style") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Choose Dialpad Style",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(8.dp))

            StyleOption(
                title = "Grid",
                subtitle = "Classic flat grid with separators",
                selected = keypadDesign == KeypadDesign.Grid,
                onClick = { vm.setKeypadDesign(context, KeypadDesign.Grid) }
            )

            StyleOption(
                title = "Rounded",
                subtitle = "Floating circular keys",
                selected = keypadDesign == KeypadDesign.Rounded,
                onClick = { vm.setKeypadDesign(context, KeypadDesign.Rounded) }
            )

            StyleOption(
                title = "Ring",
                subtitle = "Circular keys with ring outlines",
                selected = keypadDesign == KeypadDesign.Ring,
                onClick = { vm.setKeypadDesign(context, KeypadDesign.Ring) }
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
                    val subLabels = mapOf(
                        "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
                        "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
                        "0" to "+"
                    )
                    if (keypadDesign == KeypadDesign.Ring) {
                        // Match the rotary keypad: 0 at bottom, with * and # flanking it.
                        val previewSize = 176.dp
                        val keyR = 26.dp
                        val orbit = (previewSize.value / 2 - keyR.value / 2 - 8).dp
                        val angularPos = mapOf(
                            "1" to 150f, "2" to 180f, "3" to 210f, "4" to 240f,
                            "5" to 270f, "6" to 300f, "7" to 330f, "8" to 0f, "9" to 30f,
                            "#" to 60f, "0" to 90f, "*" to 120f
                        )
                        Box(
                            modifier = Modifier
                                .size(previewSize)
                                .height(previewSize),
                            contentAlignment = Alignment.Center
                        ) {
                            keys.forEachIndexed { index, digit ->
                                val angleDeg = angularPos[digit] ?: (index * (360f / keys.size))
                                val radians = Math.toRadians(angleDeg.toDouble())
                                val dx = (kotlin.math.cos(radians) * orbit.value).toFloat().dp
                                val dy = (kotlin.math.sin(radians) * orbit.value).toFloat().dp
                                PreviewRotaryKey(
                                    digit = digit,
                                    subLabel = subLabels[digit].orEmpty(),
                                    size = keyR,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .offset(x = dx, y = dy)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(44.dp)
                                    .background(com.ipdial.ui.theme.ForestGreen, CircleShape),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        keys.chunked(3).forEach { rowKeys ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                rowKeys.forEach { digit ->
                                    PreviewKey(
                                        digit = digit,
                                        subLabel = subLabels[digit].orEmpty(),
                                        design = keypadDesign
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = if (selected)
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewRotaryKey(
    digit: String,
    subLabel: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .size(size)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                CircleShape
            )
            .border(
                2.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                CircleShape
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = digit,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        if (subLabel.isNotBlank()) {
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PreviewKey(digit: String, subLabel: String, design: KeypadDesign) {
    val size = 44.dp
    when (design) {
        KeypadDesign.Grid -> {
            Column(
                modifier = Modifier
                    .width(60.dp)
                    .height(size),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = digit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subLabel.isNotBlank()) {
                    Text(
                        text = subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        KeypadDesign.Rounded -> {
            Column(
                modifier = Modifier
                    .width(size)
                    .height(size)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        CircleShape
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = digit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subLabel.isNotBlank()) {
                    Text(
                        text = subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        else -> Unit
    }
}