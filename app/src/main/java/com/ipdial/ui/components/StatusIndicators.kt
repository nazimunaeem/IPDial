package com.ipdial.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.SipViewModel
import kotlinx.coroutines.delay

val DotGreen = Color(0xFF4CAF50)
val DotRed = Color(0xFFF44336)
val DotAmber = Color(0xFFFF9800)
val DotGrey = Color(0xFF9E9E9E)

val ColorPro = Color(0xFFBC4749) // User requested deep red for Pro accent

@Composable
fun EmptyState(message: String, icon: ImageVector = Icons.Default.Info) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun RegStatusIndicator(
    accounts: List<SipAccount>,
    vm: SipViewModel? = null,
    showAccountInfo: SipAccount? = null
) {
    val vmActiveAccount by (vm?.activeAccount?.collectAsState() ?: remember { mutableStateOf(null) })
    val activeAccount = showAccountInfo ?: vmActiveAccount ?: accounts.firstOrNull { it.isEnabled } ?: accounts.firstOrNull()

    val regDotColor = when {
        activeAccount != null -> when (activeAccount.regStatus) {
            RegStatus.REGISTERED  -> DotGreen
            RegStatus.REGISTERING -> DotAmber
            RegStatus.ERROR       -> DotRed
            else                  -> DotGrey
        }
        accounts.any { it.regStatus == RegStatus.REGISTERED }    -> DotGreen
        accounts.any { it.regStatus == RegStatus.REGISTERING }   -> DotAmber
        accounts.any { it.regStatus == RegStatus.ERROR }         -> DotRed
        else                                                      -> DotGrey
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier.padding(start = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(regDotColor.copy(alpha = 0.2f))
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(regDotColor)
                )
            }
            if (activeAccount != null) {
                Spacer(Modifier.width(6.dp)) // Increased from 4
                Text(
                    text = activeAccount.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), // Increased from 9.sp
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp) // Increased from 120
                )
            }
        }

        if (activeAccount != null && (activeAccount.domain.equals("sip.amarip.net", ignoreCase = true) || activeAccount.domain.equals("103.170.231.10", ignoreCase = true) || activeAccount.domain.equals("103.129.202.202", ignoreCase = true)) && vm != null) {
            val balanceMap by vm.balances.collectAsState()
            val balance = balanceMap[activeAccount.id]
            val isPro by vm.isPro.collectAsState()

            var isRevealing by remember { mutableStateOf(false) }
            val offsetX = remember { Animatable(-10f) }

            // Always show balance for Pro users or if specifically revealing
            val showBalance = isPro || isRevealing

            LaunchedEffect(isRevealing, isPro) {
                if (isPro) {
                    vm?.fetchBalance(activeAccount, context)
                    if (!isRevealing) offsetX.snapTo(20f)
                }
                if (isRevealing) {
                    offsetX.snapTo(0f)
                    offsetX.animateTo(20f, animationSpec = tween(600))
                    delay(10000)
                    isRevealing = false
                }
            }

            Row(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable {
                        if (isPro) {
                            // Pro users just refresh on click
                            vm?.fetchBalance(activeAccount, context)
                        } else {
                            if (isRevealing) {
                                isRevealing = false
                                vm?.dismissAd()
                            } else {
                                vm?.fetchBalance(activeAccount, context)
                                isRevealing = true
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .widthIn(min = 60.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (showBalance) {
                    val cleanBalance = (balance ?: "...").replace("BDT", "").trim()
                    Text(
                        text = cleanBalance,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "৳",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.offset(x = (offsetX.value - 20).dp)
                    )
                } else {
                    Text(
                        text = "Balance",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
