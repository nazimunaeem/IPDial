package com.ipdial.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipdial.ui.theme.ThemeSchemePreview
import com.ipdial.ui.theme.themePreviewForMode
import com.ipdial.data.model.ThemeMode
import com.ipdial.ui.SipViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    vm: SipViewModel,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(previews) { _, preview ->
                val enabled = if (preview.label == "Dynamic") Build.VERSION.SDK_INT >= 31 else true
                val selected = themeMode.name == preview.label
                val isGlass = preview.label == "Obsidian" || preview.label == "Quartz"

                ThemePreviewCard(
                    preview = preview,
                    selected = selected,
                    enabled = enabled,
                    isGlass = isGlass,
                    onSelect = { vm.setThemeMode(context, ThemeMode.valueOf(preview.label)) }
                )
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    preview: ThemeSchemePreview,
    selected: Boolean,
    enabled: Boolean,
    isGlass: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) preview.primary else preview.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled) { if (enabled) onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Mini phone-style preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(preview.background)
                    .border(1.dp, preview.onSurface.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    // Status bar mock
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(preview.primary)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(14.dp).height(5.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(preview.onSurface.copy(alpha = 0.35f))
                            )
                            Box(
                                modifier = Modifier
                                    .width(14.dp).height(5.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(preview.onSurface.copy(alpha = 0.35f))
                            )
                        }
                    }

                    // Title bar mock
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(preview.surface.copy(alpha = if (isGlass) 0.85f else 1f))
                            .clip(RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dialpad,
                            contentDescription = null,
                            tint = preview.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(preview.onSurface.copy(alpha = 0.7f))
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Card row mock
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(preview.surface.copy(alpha = if (isGlass) 0.85f else 1f))
                        ) {
                            Column(
                                modifier = Modifier.padding(5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(preview.onSurface.copy(alpha = 0.5f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.4f)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(preview.onSurface.copy(alpha = 0.25f))
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(preview.surface.copy(alpha = if (isGlass) 0.85f else 1f))
                        ) {
                            Column(
                                modifier = Modifier.padding(5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(preview.onSurface.copy(alpha = 0.5f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.35f)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(preview.onSurface.copy(alpha = 0.25f))
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Bottom nav mock
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(preview.surface.copy(alpha = if (isGlass) 0.9f else 1f))
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(preview.primary)
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(preview.onSurface.copy(alpha = 0.2f))
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(preview.onSurface.copy(alpha = 0.2f))
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Label + description + radio
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = preview.primary
                    )
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        preview.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                    when (preview.label) {
                        "System" -> Text("Follows device settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        "Light" -> Text("Clean green, always light", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        "Dark" -> Text("Dark green, easy on eyes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        "Dynamic" -> Text("Material You, wallpaper-based", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        "Obsidian" -> Text("Apple-style dark glass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        "Quartz" -> Text("Apple-style light glass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (!enabled) {
                        Text(
                            "Requires Android 12+",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // Color swatch strip
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(preview.background)
                            .border(0.5.dp, preview.onSurface.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(preview.surface)
                            .border(0.5.dp, preview.onSurface.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(preview.primary)
                    )
                }
            }
        }
    }
}
