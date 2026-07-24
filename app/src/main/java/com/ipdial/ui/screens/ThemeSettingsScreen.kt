package com.ipdial.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipdial.ui.theme.ThemeSchemePreview
import com.ipdial.ui.theme.themePreviewForMode
import com.ipdial.data.model.ThemeMode
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.SipViewModel

private data class ThemePreview(
    val mode: ThemeMode,
    val label: String,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val onSurface: Color,
    val onPrimary: Color
)

private val previewData = listOf(
    ThemePreview(ThemeMode.System, "System", Color(0xFFEAEFE9), Color(0xFFF2F7F1), Color(0xFF1E6B3C), Color(0xFF1A2E1A), Color.White),
    ThemePreview(ThemeMode.Light, "Light", Color(0xFFEAEFE9), Color(0xFFF2F7F1), Color(0xFF1E6B3C), Color(0xFF1A2E1A), Color.White),
    ThemePreview(ThemeMode.Dark, "Dark", Color(0xFF121212), Color(0xFF1A1A1A), Color(0xFF8BCF8F), Color(0xFFE0E0E0), Color(0xFF003912)),
    ThemePreview(ThemeMode.Dynamic, "Dynamic", Color(0xFFD0E8FF), Color(0xFFE0F0FF), Color(0xFF0066CC), Color(0xFF1A1A2E), Color.White),
    ThemePreview(ThemeMode.Obsidian, "Obsidian", Color(0xFF1C1C1E), Color(0xCC1C1C1E), Color(0xFF34C759), Color.White, Color.White),
    ThemePreview(ThemeMode.Quartz, "Quartz", Color(0xFFF2F2F7), Color(0xCCFFFFFF), Color(0xFF007AFF), Color.Black, Color.White),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    vm: SipViewModel,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val previews = remember {
        ThemeMode.entries.map { themePreviewForMode(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Theme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(previews) { preview ->
                val enabled = if (preview.label == "Dynamic") Build.VERSION.SDK_INT >= 31 else true
                val selected = themeMode.name == preview.label

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) preview.primary else preview.primary.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled) {
                            if (enabled) vm.setThemeMode(context, ThemeMode.valueOf(preview.label))
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Mini preview swatch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(preview.background)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Sample surface chip
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(preview.surface)
                                    .border(1.dp, preview.onSurface.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(preview.primary)
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column {
                                Text(
                                    "Sample Title",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = preview.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Sample subtitle text",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = preview.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            // Sample button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(preview.primary)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Action", style = MaterialTheme.typography.labelMedium, color = preview.onPrimary)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Label + radio
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = enabled,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    preview.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                if (!enabled) {
                                    Text(
                                        "Requires Android 12+",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
