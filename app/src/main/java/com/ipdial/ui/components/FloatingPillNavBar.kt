package com.ipdial.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.abs

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

    val containerShape = RoundedCornerShape(28.dp)
    val containerBgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    // Authentic Apple iOS Liquid Glass backgrounds & specular borders
    val iosGlassBackground = when {
        isQuartz -> Brush.verticalGradient(
            listOf(
                Color(0xE6FFFFFF),
                Color(0xC8F2F2F7)
            )
        )
        isGlass -> Brush.verticalGradient(
            listOf(
                Color(0xD92C2C2E),
                Color(0xB31C1C1E)
            )
        )
        else -> null
    }

    val iosSpecularBorder = when {
        isQuartz -> Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.95f),
                Color.Black.copy(alpha = 0.12f)
            )
        )
        isGlass -> Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.45f),
                Color.White.copy(alpha = 0.10f)
            )
        )
        else -> null
    }

    val activeIndicatorBg = when {
        isQuartz -> Color.White.copy(alpha = 0.95f)
        isGlass -> Color.White.copy(alpha = 0.22f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }

    val activeContentColor = when {
        isQuartz -> Color(0xFF007AFF)
        isGlass -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }

    val inactiveContentColor = when {
        isQuartz -> Color.Black.copy(alpha = 0.50f)
        isGlass -> Color.White.copy(alpha = 0.60f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Continuous page position for sliding animations
    val scrollPosition = if (isMainScreen) {
        (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 2f)
    } else {
        when (currentRoute) {
            NavDest.Home.route -> 0f
            NavDest.Keypad.route -> 1f
            NavDest.Contacts.route -> 2f
            else -> 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // 1. Compact Main Navigation Pill with Sliding Background Indicator (Centered)
        Surface(
            modifier = Modifier
                .width(168.dp)
                .height(50.dp)
                .shadow(
                    elevation = if (isGlass) 14.dp else 10.dp,
                    shape = containerShape,
                    ambientColor = Color.Black.copy(alpha = if (isGlass) 0.18f else 0.12f),
                    spotColor = Color.Black.copy(alpha = if (isGlass) 0.25f else 0.18f)
                )
                .then(
                    if (isGlass) {
                        Modifier
                            .clip(containerShape)
                            .background(iosGlassBackground!!)
                            .border(1.dp, iosSpecularBorder!!, containerShape)
                    } else {
                        Modifier
                            .clip(containerShape)
                            .background(containerBgColor)
                            .border(1.dp, borderColor, containerShape)
                    }
                ),
            shape = containerShape,
            color = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                val tabWidth = maxWidth / 3f

                // Sliding Indicator Capsule
                Box(
                    modifier = Modifier
                        .offset(x = tabWidth * scrollPosition)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(activeIndicatorBg)
                        .then(
                            if (isQuartz) Modifier.border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                            else if (isGlass) Modifier.border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                            else Modifier
                        )
                )

                // 3 Nav Icon Items (No text)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, item ->
                        val distance = abs(scrollPosition - index.toFloat()).coerceIn(0f, 1f)
                        val progress = 1f - distance

                        val contentColor = lerp(inactiveContentColor, activeContentColor, progress)
                        val iconSize = (20f + 3f * progress).dp

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, radius = 24.dp)
                                ) {
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
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = contentColor,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }
            }
        }

        // 2. Separate Rounded 3-Dot Button Attached Rightward Beside the Centered Pill
        Surface(
            onClick = onOpenMenu,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 117.dp) // 84dp (half pill) + 8dp (gap) + 25dp (half button)
                .size(50.dp)
                .shadow(
                    elevation = if (isGlass) 14.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = if (isGlass) 0.18f else 0.12f),
                    spotColor = Color.Black.copy(alpha = if (isGlass) 0.25f else 0.18f)
                )
                .then(
                    if (isGlass) {
                        Modifier
                            .clip(CircleShape)
                            .background(iosGlassBackground!!)
                            .border(1.dp, iosSpecularBorder!!, CircleShape)
                    } else {
                        Modifier
                            .clip(CircleShape)
                            .background(containerBgColor)
                            .border(1.dp, borderColor, CircleShape)
                    }
                ),
            shape = CircleShape,
            color = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "Menu",
                    tint = inactiveContentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

