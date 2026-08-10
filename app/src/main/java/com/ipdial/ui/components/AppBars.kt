package com.ipdial.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IPDialTopBar(
    accounts: List<SipAccount>,
    vm: SipViewModel? = null,
    onOpenDrawer: () -> Unit,
    onAddAccount: (() -> Unit)? = null
) {
    val isGlass = LocalGlassMode.current != GlassMode.None
    val containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface
    val isPro = vm?.isPro?.collectAsState()?.value ?: false
    val appName = if (isPro) "IPDial Pro" else "IPDial"
    val appNameColor = MaterialTheme.colorScheme.onSurface
    val themeColor = MaterialTheme.colorScheme.primary

    Surface(
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(0.dp)) else Modifier),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
        ) {
            // Left: Status Dot & Name
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                RegStatusIndicator(accounts = accounts, vm = vm)
            }

            // Center: App Name with soft background
            Surface(
                color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(12.dp)) else Modifier)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = appNameColor
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Right side items: Add Account (if no accounts) + Hamburger
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                if (accounts.isEmpty() && onAddAccount != null) {
                    Surface(
                        onClick = onAddAccount,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Setup Account",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = themeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
