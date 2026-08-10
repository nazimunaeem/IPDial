package com.ipdial.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.ipdial.ui.SipViewModel
import com.startapp.sdk.ads.banner.Banner

@Composable
fun StartIoBanner(modifier: Modifier = Modifier, vm: SipViewModel? = null) {
    val adsEnabled = vm?.adsEnabled?.collectAsState()?.value ?: true
    if (!adsEnabled) return

    val isPro = vm?.isPro?.collectAsState()?.value ?: false

    // Check if custom ad is enabled via Firestore (admin-managed)
    val customEnabled by com.ipdial.util.FirestoreAdConfig.customAdEnabled.collectAsState()
    val imageUrl by com.ipdial.util.FirestoreAdConfig.customAdImageUrl.collectAsState()
    val linkUrl by com.ipdial.util.FirestoreAdConfig.customAdLink.collectAsState()
    val showTo by com.ipdial.util.FirestoreAdConfig.customAdShowTo.collectAsState()
    val bgColorHex by com.ipdial.util.FirestoreAdConfig.customAdBg.collectAsState()

    val bgColor = try {
        Color(android.graphics.Color.parseColor(bgColorHex))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.surface
    }

    if (customEnabled && imageUrl.isNotBlank()) {
        val showForThisUser = when (showTo) {
            "pro" -> isPro
            "non_pro" -> !isPro
            else -> true  // "all"
        }
        if (!showForThisUser) return
        CustomAdBanner(imageUrl = imageUrl, linkUrl = linkUrl, bgColor = bgColor, modifier = modifier)
    } else {
        if (isPro) return
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { ctx ->
                Banner(ctx)
            }
        )
    }
}

@Composable
fun CustomAdBanner(imageUrl: String, linkUrl: String, bgColor: Color = MaterialTheme.colorScheme.surface, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    Card(
        onClick = {
            if (linkUrl.isNotBlank()) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                } catch (_: Exception) {}
            }
        },
        modifier = modifier
            .size(width = 720.dp, height = 90.dp),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (imageUrl.isNotBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Advertisement",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close ad",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun CustomAccountPageAd(vm: SipViewModel, modifier: Modifier = Modifier) {
    val adsEnabled = vm.adsEnabled.collectAsState().value
    if (!adsEnabled) return

    val rc = com.ipdial.util.FirestoreAdConfig
    val enabled by rc.accountPageAdEnabled.collectAsState()
    if (!enabled) return

    val imageUrl by rc.accountPageAdImage.collectAsState()
    val bgColorHex by rc.accountPageAdBg.collectAsState()
    val ctaUrl by rc.accountPageAdCta.collectAsState()
    val showTo by rc.accountPageAdShowTo.collectAsState()

    val isPro by vm.isPro.collectAsState()
    val showForThisUser = when (showTo) {
        "pro" -> isPro
        "non_pro" -> !isPro
        else -> true  // "all"
    }
    if (!showForThisUser) return

    val context = LocalContext.current
    val bgColor = try {
        Color(android.graphics.Color.parseColor(bgColorHex))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Card(
        onClick = {
            if (ctaUrl.isNotBlank()) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ctaUrl))) }
                catch (_: Exception) {}
            }
        },
        modifier = modifier.size(width = 720.dp, height = 90.dp),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Advertisement",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
