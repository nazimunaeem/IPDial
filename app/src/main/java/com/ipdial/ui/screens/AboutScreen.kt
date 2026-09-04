package com.ipdial.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ipdial.R
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.SipViewModel
import kotlinx.coroutines.launch

private const val SHOW_AD_TOGGLE = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val adsEnabled by vm.adsEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboard.current

    // Current version from PackageManager
    val currentVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }
        catch (e: Exception) { "1.0" }
    }

    val deviceId by vm.deviceId.collectAsState()

    val appIconBitmap = remember(context) {
        try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            if (drawable != null) {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 200
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 200
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, title = "About", onBack = onBack)
        },
        bottomBar = {
            com.ipdial.ui.components.StartIoBanner(
                vm = vm,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            if (appIconBitmap != null) {
                Image(
                    bitmap = appIconBitmap,
                    contentDescription = "App Icon",
                    modifier = Modifier.size(100.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "App Icon",
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Version $currentVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "A simple VoIP client for Android.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 32.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Developer:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "NAZIM U. NAEEM",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(Modifier.height(32.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            clipboardManager.setClipEntry(
                                androidx.compose.ui.platform.ClipEntry(
                                    android.content.ClipData.newPlainText("Device ID", deviceId)
                                )
                            )
                            android.widget.Toast.makeText(context, "Device ID copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Device ID",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = deviceId,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/IPDial"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Group, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Join Telegram Community")
            }

            Spacer(Modifier.height(32.dp))
            
            /*
            // Donation Section
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Support Development",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Donate via bKash (Personal)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { 
                                clipboardManager.setText(AnnotatedString("01728867695"))
                                scope.launch {
                                    val toast = android.widget.Toast.makeText(context, "bKash number copied", android.widget.Toast.LENGTH_SHORT)
                                    toast.show()
                                }
                            }
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "01728867695",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "(Click number to copy)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            */

            Spacer(Modifier.height(24.dp))

            // Ad Toggle
            if (SHOW_AD_TOGGLE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setAdsEnabled(!adsEnabled) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Advertisements",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Support us by keeping ads on",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = adsEnabled,
                        onCheckedChange = { vm.setAdsEnabled(it) }
                    )
                }
            }
        }
    }
}
