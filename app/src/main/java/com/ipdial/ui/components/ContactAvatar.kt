package com.ipdial.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    isGlass: Boolean = false,
    showNumbers: Boolean = true,
    showFavoriteBadge: Boolean = true,
    isExpanded: Boolean = false,
    onDialClick: (() -> Unit)? = null
) {
    val isFav = contact.isFavorite
    val expandedCardVisible = !showNumbers && isExpanded && contact.numbers.size > 1

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isGlass -> Color.Transparent
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            expandedCardVisible -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> Color.Transparent
        },
        tonalElevation = if (expandedCardVisible && !isGlass) 2.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isGlass) Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .glass(RoundedCornerShape(12.dp))
                else if (expandedCardVisible) Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                else Modifier.padding(horizontal = 4.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = {
                    Text(
                        text = contact.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isFav && showFavoriteBadge) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = if (showNumbers) {
                    {
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
                    }
                } else null,
                leadingContent = {
                    ContactAvatar(
                        name = contact.name,
                        photoUri = contact.photoUri,
                        size = 44.dp,
                        onClick = onAvatarClick
                    )
                },
                trailingContent = if (onDialClick != null) {
                    {
                        IconButton(
                            onClick = onDialClick,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("contact_dial_button_${contact.id}")
                        ) {
                            Icon(
                                imageVector = if (expandedCardVisible) Icons.Default.KeyboardArrowUp else Icons.Default.Call,
                                contentDescription = if (expandedCardVisible) {
                                    "Collapse numbers for ${contact.name}"
                                } else {
                                    "Call ${contact.name}"
                                },
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                } else if (onToggleFavorite != null) {
                    {
                        IconButton(
                            onClick = { onToggleFavorite(contact) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = if (isFav) "Remove from favorites" else "Add to favorites",
                                tint = if (isFav) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                } else if (isFav && showFavoriteBadge) {
                    {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else null,
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("contact_item_${contact.id}")
                    .clickable { onContactClick() }
            )

            AnimatedVisibility(
                visible = expandedCardVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
                        .testTag("contact_expanded_card_${contact.id}")
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                    contact.numbers.forEachIndexed { idx, number ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickableWithRipple { onNumberClick(number) }
                                .padding(start = 56.dp, end = 0.dp)
                                .testTag("contact_expanded_number_${contact.id}_$idx"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = number,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { onNumberClick(number) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("contact_expanded_dial_${contact.id}_$idx")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Call $number",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (idx < contact.numbers.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp, end = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
            }
        }
    }
}
