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
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.Contact
import com.ipdial.ui.screens.clickableWithRipple
import com.ipdial.ui.theme.glass

/**
 * Unified contact avatar composable.
 * Shows contact photo if available, otherwise a colored circle with the first letter or contact icon.
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
    val fontSize = (size.value * 0.42f).sp
    val isLetterInitial = name.firstOrNull()?.isLetter() == true
    val initialText = if (isLetterInitial) name.firstOrNull()?.uppercase() ?: "" else ""

    @Composable
    fun PlaceholderContent() {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (isLetterInitial) {
                Text(
                    text = initialText,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.52f),
                    tint = contentColor
                )
            }
        }
    }

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
                    .crossfade(true)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build()
            }
            coil.compose.SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { PlaceholderContent() },
                error = { PlaceholderContent() }
            )
        } else {
            PlaceholderContent()
        }
    }
}

@Composable
fun ContactItem(
    contact: Contact,
    onNumberClick: (String) -> Unit,
    onContactClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onToggleFavorite: ((Contact) -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    isGlass: Boolean = false
) {
    val isFav = contact.isFavorite
    ListItem(
        headlineContent = {
            Text(
                text = contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isFav) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Column {
                contact.numbers.forEach { number ->
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
                onClick = onAvatarClick
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
        else if (selected)
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
        else ListItemDefaults.colors(),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onContactClick() }
            .then(
                if (isGlass) Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .glass(RoundedCornerShape(12.dp))
                else Modifier.padding(horizontal = 4.dp)
            )
    )
}
