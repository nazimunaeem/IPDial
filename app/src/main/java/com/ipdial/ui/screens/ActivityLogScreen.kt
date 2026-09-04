package com.ipdial.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.glass
import com.ipdial.util.SipLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val logs by SipLogger.logs.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz
    val topBarContentColor = if (isGlass) (if (isQuartz) Color.Black else Color.White) else MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = logs.joinToString("\n")
                        val clip = ClipData.newPlainText("IPDial Logs", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = {
                        SipLogger.clear()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    titleContentColor = topBarContentColor,
                    navigationIconContentColor = topBarContentColor,
                    actionIconContentColor = topBarContentColor
                ),
                modifier = Modifier.then(if (isGlass) Modifier.glass(RoundedCornerShape(0.dp)) else Modifier)
            )
        },
        bottomBar = {
            com.ipdial.ui.components.StartIoBanner(
                vm = vm,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    if (com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None) 
                        Color(0xCC1E1E1E) 
                    else 
                        Color(0xFF1E1E1E)
                ) // Dark terminal-like background
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No logs available",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(logs) { logLine ->
                        val color = when {
                            logLine.contains("[ERROR]") || logLine.contains("failed") || logLine.contains("Error") -> Color(0xFFF44336) // Red
                            logLine.contains("DISCONNECTED") || logLine.contains("UNREGISTERED") -> Color(0xFFFF9800) // Orange
                            logLine.contains("REGISTERED") || logLine.contains("CONFIRMED") -> Color(0xFF4CAF50) // Green
                            logLine.contains("SipEngine:") || logLine.contains("SipService:") -> Color(0xFF00BCD4) // Cyan
                            else -> Color(0xFFECEFF1) // Off-white
                        }
                        Text(
                            text = logLine,
                            color = if (isGlass) (if (color == Color(0xFFECEFF1)) Color.White else color) else color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                                .then(if (isGlass) Modifier.glass(RoundedCornerShape(4.dp)) else Modifier)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}
