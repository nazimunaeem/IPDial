package com.ipdial.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.Contact
import com.ipdial.ui.screens.clickableWithRipple
import com.ipdial.ui.theme.glass

/**
 * Unified contact avatar composable.
 * Shows contact photo if available, otherwise a colored circle with the first letter.
 */
@Composable
fun ContactAvatar(
    name: String,
    photoUri: android.net.Uri? = null,
    size: Dp = 44.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(if (onClick != null) Modifier.clickableWithRipple { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (photoUri != null) {
            val request = remember(photoUri) {
                coil.request.ImageRequest.Builder(context)
                    .data(photoUri)
                    .size(size.value.toInt() * 2, size.value.toInt() * 2)
                    .crossfade(true)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build()
            }
            coil.compose.AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

@Composable
fun ContactItem(
    contact: Contact,
    onNumberClick: (String) -> Unit,
    onContactClick: () -> Unit,
    onToggleFavorite: ((Contact) -> Unit)? = null,
    modifier: Modifier = Modifier,
    isGlass: Boolean = false
) {
    val isFav = contact.isFavorite
    ListItem(
        headlineContent = {
            Text(
                text = contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isFav) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Column {
                contact.numbers.take(2).forEach { number ->
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable { onNumberClick(number) }
                            .padding(vertical = 1.dp)
                    )
                }
            }
        },
        leadingContent = {
            ContactAvatar(
                name = contact.name,
                photoUri = contact.photoUri,
                size = 44.dp,
                onClick = onContactClick
            )
        },
        trailingContent = if (onToggleFavorite != null) {
            {
                IconButton(
                    onClick = { onToggleFavorite(contact) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = if (isFav) "Remove from favorites" else "Add to favorites",
                        tint = if (isFav) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else if (isFav) {
            {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else null,
        colors = if (isGlass)
            ListItemDefaults.colors(containerColor = Color.Transparent)
        else ListItemDefaults.colors(),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isGlass) Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .glass(RoundedCornerShape(12.dp))
                else Modifier.padding(horizontal = 4.dp)
            )
    )
}
