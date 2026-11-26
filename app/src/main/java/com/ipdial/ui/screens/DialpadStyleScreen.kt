package com.ipdial.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.screens.dialpad.DialpadKeypad
import com.ipdial.ui.theme.ForestGreen
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadStyleScreen(
    vm: SipViewModel,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    val keypadDesign by vm.keypadDesign.collectAsState()
    val dialpadVibrate by vm.dialpadVibrate.collectAsState()
    val context = LocalContext.current
    var previewNumber by remember { mutableStateOf("+15552348910") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dialpad Style") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("dialpad_style_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Live Dialer Preview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )

                Spacer(Modifier.height(8.dp))

                DialerMockPreviewCard(
                    design = keypadDesign,
                    previewNumber = previewNumber,
                    vibrateOnPress = dialpadVibrate,
                    onDigit = { ch ->
                        if (previewNumber.length < 18) previewNumber += ch
                    },
                    onBackspace = {
                        if (previewNumber.isNotEmpty()) {
                            previewNumber = previewNumber.dropLast(1)
                        } else {
                            previewNumber = "+15552348910"
                        }
                    },
                    onResetPreview = {
                        previewNumber = "+15552348910"
                    }
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "Choose Dialpad Style",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )

                Spacer(Modifier.height(8.dp))

                StyleOption(
                    title = "Grid",
                    subtitle = "Classic flat grid with crisp dividers",
                    selected = keypadDesign == KeypadDesign.Grid,
                    testTag = "dialpad_style_grid",
                    onClick = { vm.setKeypadDesign(context, KeypadDesign.Grid) }
                )

                StyleOption(
                    title = "Rounded",
                    subtitle = "Floating circular keys",
                    selected = keypadDesign == KeypadDesign.Rounded,
                    testTag = "dialpad_style_rounded",
                    onClick = { vm.setKeypadDesign(context, KeypadDesign.Rounded) }
                )

                StyleOption(
                    title = "Ring",
                    subtitle = "Rotary dial with center call button",
                    selected = keypadDesign == KeypadDesign.Ring,
                    testTag = "dialpad_style_ring",
                    onClick = { vm.setKeypadDesign(context, KeypadDesign.Ring) }
                )
            }
        }
    }
}

@Composable
private fun DialerMockPreviewCard(
    design: KeypadDesign,
    previewNumber: String,
    vibrateOnPress: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onResetPreview: () -> Unit
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    val keys = remember {
        listOf(
            Triple("1", "⠀", null),
            Triple("2", "ABC", null),
            Triple("3", "DEF", null),
            Triple("4", "GHI", null),
            Triple("5", "JKL", null),
            Triple("6", "MNO", null),
            Triple("7", "PQRS", null),
            Triple("8", "TUV", null),
            Triple("9", "WXYZ", null),
            Triple("*", "", null),
            Triple("0", "+", null),
            Triple("#", "", null),
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(24.dp)
            )
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.background
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Subtle simulated suggested contact strip (mirrors DialpadScreen top layer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alex Morgan",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "SIM 1 · SIP Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Tap keys",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Bottom dialpad sheet (mirrors DialpadScreen bottom layer)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface)
                    .padding(bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Dial display row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onResetPreview,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Reset preview number",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = formatPreviewNumber(previewNumber).ifEmpty { "Enter number" },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center
                        ),
                        color = if (previewNumber.isEmpty()) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    AnimatedVisibility(visible = previewNumber.isNotEmpty()) {
                        IconButton(
                            onClick = onBackspace,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Backspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (previewNumber.isEmpty()) {
                        Spacer(Modifier.size(40.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    DialpadKeypad(
                        keys = keys,
                        design = design,
                        onKeyPress = onDigit,
                        onZeroLongPress = { onDigit('+') },
                        onCallClick = if (design == KeypadDesign.Ring) onResetPreview else null,
                        fitWithinWidth = minOf(maxWidth, 340.dp),
                        vibrateOnPress = vibrateOnPress
                    )
                }

                if (design != KeypadDesign.Ring) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = onResetPreview,
                        shape = CircleShape,
                        color = ForestGreen,
                        modifier = Modifier
                            .width(124.dp)
                            .height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatPreviewNumber(raw: String): String {
    if (raw.isEmpty()) return ""
    return when {
        raw == "+15552348910" -> "+1 555-234-8910"
        raw.length > 7 && raw.startsWith("+") -> "${raw.take(2)} ${raw.drop(2)}"
        else -> raw
    }
}

@Composable
private fun StyleOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = if (selected) {
            CardDefaults.outlinedCardBorder().copy(
                brush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else null
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