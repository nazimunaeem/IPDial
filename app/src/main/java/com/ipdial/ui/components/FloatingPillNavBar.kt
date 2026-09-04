package com.ipdial.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.ipdial.NavDest
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import kotlinx.coroutines.launch

data class PillNavItem(
    val title: String,
    val icon: ImageVector,
    val route: String
)

@Composable
fun FloatingPillNavBar(
    navController: NavController,
    pagerState: PagerState,
    currentRoute: String,
    callSession: CallSession?,
    showFullIncomingScreen: Boolean,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val hasActiveCall = callSession != null && callSession.state != CallState.DISCONNECTED
    val isMainScreen = currentRoute == NavDest.Home.route ||
            currentRoute == NavDest.Keypad.route ||
            currentRoute == NavDest.Contacts.route

    val shouldShow = !hasActiveCall && (isMainScreen ||
            ((callSession == null || !showFullIncomingScreen) && currentRoute == NavDest.GetPro.route))

    if (!shouldShow) return

    val items = remember {
        listOf(
            PillNavItem("Recents", Icons.Default.Home, NavDest.Home.route),
            PillNavItem("Keypad", Icons.Default.Dialpad, NavDest.Keypad.route),
            PillNavItem("Contacts", Icons.Default.Contacts, NavDest.Contacts.route)
        )
    }

    val glassMode = LocalGlassMode.current
    val isGlass = glassMode != GlassMode.None
    val isQuartz = glassMode == GlassMode.Quartz

    val containerShape = RoundedCornerShape(32.dp)
    val containerBgColor = when {
        isGlass -> Color.Transparent
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    }
    val borderColor = when {
        isQuartz -> Color.Black.copy(alpha = 0.15f)
        isGlass -> Color.White.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .shadow(
                    elevation = if (isGlass) 0.dp else 12.dp,
                    shape = containerShape,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.18f)
                )
                .then(
                    if (isGlass) {
                        Modifier.glass(containerShape, borderWidth = 1.dp, alpha = 0.88f)
                    } else {
                        Modifier
                            .clip(containerShape)
                            .background(containerBgColor)
                            .border(1.dp, borderColor, containerShape)
                    }
                ),
            shape = containerShape,
            color = if (isGlass) Color.Transparent else containerBgColor,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 3 Main Nav Tabs
                items.forEachIndexed { index, item ->
                    val isSelected = if (isMainScreen) {
                        pagerState.currentPage == index
                    } else {
                        currentRoute == item.route
                    }

                    PillTabItem(
                        item = item,
                        isSelected = isSelected,
                        onClick = {
                            if (isMainScreen) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            } else {
                                navController.graph.let { graph ->
                                    navController.navigate(item.route) {
                                        popUpTo(graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        }
                    )
                }

                // Divider / Spacer before 3-dot trigger
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .width(1.dp)
                        .background(
                            if (isQuartz) Color.Black.copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                )

                Spacer(Modifier.width(4.dp))

                // 4th Item: 3-Dot Pill Button for Bottom Sliding Sheet
                PillMoreButton(
                    onClick = onOpenMenu
                )
            }
        }
    }
}

@Composable
private fun PillTabItem(
    item: PillNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val glassMode = LocalGlassMode.current
    val isGlass = glassMode != GlassMode.None
    val isQuartz = glassMode == GlassMode.Quartz

    val pillShape = RoundedCornerShape(24.dp)

    val activeBgColor = when {
        isQuartz -> Color.Black.copy(alpha = 0.08f)
        isGlass -> Color.White.copy(alpha = 0.22f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }

    val activeContentColor = when {
        isQuartz -> Color.Black
        isGlass -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }

    val inactiveContentColor = when {
        isQuartz -> Color.Black.copy(alpha = 0.55f)
        isGlass -> Color.White.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) activeBgColor else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "pillBgColor"
    )

    val animatedContentColor by animateColorAsState(
        targetValue = if (isSelected) activeContentColor else inactiveContentColor,
        animationSpec = tween(durationMillis = 200),
        label = "pillContentColor"
    )

    Box(
        modifier = Modifier
            .clip(pillShape)
            .background(animatedBgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 28.dp),
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = animatedContentColor,
                modifier = Modifier.size(20.dp)
            )

            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150))
            ) {
                Row {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        color = animatedContentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun PillMoreButton(
    onClick: () -> Unit
) {
    val glassMode = LocalGlassMode.current
    val isGlass = glassMode != GlassMode.None
    val isQuartz = glassMode == GlassMode.Quartz

    val iconColor = when {
        isQuartz -> Color.Black.copy(alpha = 0.75f)
        isGlass -> Color.White.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 20.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MoreHoriz,
            contentDescription = "More Menu",
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
    }
}
