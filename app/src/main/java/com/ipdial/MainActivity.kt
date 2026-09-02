package com.ipdial

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.screens.AboutScreen
import com.ipdial.ui.screens.AccountsScreen
import com.ipdial.ui.screens.ActivityLogScreen
import com.ipdial.ui.screens.AudioCodecScreen
import com.ipdial.ui.screens.CallScreen
import com.ipdial.ui.screens.ContactsScreen
import com.ipdial.ui.screens.DialpadScreen
import com.ipdial.ui.screens.GetProScreen
import com.ipdial.ui.screens.home.HomeScreen
import com.ipdial.ui.screens.IncomingCallScreen
import com.ipdial.ui.screens.IncomingCallSettingsScreen
import com.ipdial.ui.screens.PrivacyPolicyScreen
import com.ipdial.ui.screens.RecordingsScreen
import com.ipdial.ui.screens.SettingsScreen
import com.ipdial.ui.screens.ThemeSettingsScreen
import com.ipdial.ui.theme.IPDialTheme
import com.ipdial.ui.theme.glass
import kotlinx.coroutines.launch

object AppState {
    var isForeground = false
}

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.READ_CONTACTS] == true) {
            vm.refreshContacts()
        }
    }

    private val vm: SipViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        AppState.isForeground = true
        val session = vm.callSession.value
        if (session != null && session.state != CallState.DISCONNECTED) {
            applyLockScreenFlags()
            vm.setShowFullIncomingScreen(true)
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(com.ipdial.service.NOTIF_ID_INCOMING)
        }
    }

    override fun onPause() {
        super.onPause()
        AppState.isForeground = false
        val session = vm.callSession.value
        if (session != null && session.state != CallState.DISCONNECTED) {
            com.ipdial.service.showCallNotificationStatic(this, session.remoteDisplayName, session.callId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
        
        applyLockScreenFlags()
        
        requestRequiredPermissions()
        com.ipdial.service.SipService.start(this)

        handleIntent(intent)

        setContent {
            val vm: SipViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val fontMultiplier by vm.fontSizeMultiplier.collectAsState()

            // Keep screen on when there's an active call
            val callSession by vm.callSession.collectAsState()
            val localView = LocalView.current
            
            var launchedForCall by remember { mutableStateOf(intent?.action == "com.ipdial.ACTION_INCOMING_CALL" || intent?.action == "com.ipdial.ACTION_SHOW_CALL") }
            var hasSeenActiveCall by remember { mutableStateOf(false) }
            
            LaunchedEffect(callSession) {
                val window = (localView.context as? android.app.Activity)?.window
                val activity = localView.context as? android.app.Activity
                if (callSession != null) {
                    hasSeenActiveCall = true
                    activity?.volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
                    window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        activity?.setTurnScreenOn(true)
                        activity?.setShowWhenLocked(true)
                    }
                } else {
                    activity?.volumeControlStream = android.media.AudioManager.STREAM_MUSIC
                    window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        activity?.setTurnScreenOn(false)
                        activity?.setShowWhenLocked(false)
                    } else {
                        @Suppress("DEPRECATION")
                        window?.clearFlags(
                            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        )
                    }
                    if (hasSeenActiveCall && launchedForCall) {
                        launchedForCall = false
                        activity?.moveTaskToBack(true)
                    }
                }
            }

            IPDialTheme(
                themeMode = themeMode,
                fontMultiplier = fontMultiplier
            ) {
                IPDialApp()

                if (showBatteryDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog.value = false },
                        title = { Text("Battery Optimization") },
                        text = {
                            Text(
                                "IPDial needs to run in the background to receive incoming calls. " +
                                "Battery optimization may prevent calls from reaching you.\n\n" +
                                "Please disable battery optimization for IPDial to ensure reliable call reception."
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showBatteryDialog.value = false
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }) {
                                Text("Open Settings")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatteryDialog.value = false }) {
                                Text("Later")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenFlags()
        handleIntent(intent)
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            try {
                km.requestDismissKeyguard(this, null)
            } catch (e: Exception) {
                Log.e("MainActivity", "requestDismissKeyguard failed", e)
            }
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let { i ->
            if (i.action == "com.ipdial.TEST_CALL") {
                val num = i.getStringExtra("number")
                if (!num.isNullOrBlank()) {
                    vm.makeCall(num)
                }
            } else if (i.action == "com.ipdial.TEST_HANGUP") {
                vm.hangup()
            } else if (i.action == "com.ipdial.ACTION_INCOMING_CALL" || i.action == "com.ipdial.ACTION_SHOW_CALL") {
                vm.setShowFullIncomingScreen(true)
            } else if (i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW || i.action == Intent.ACTION_CALL) {
                val data = i.data
                if (data != null && data.scheme == "tel") {
                    val number = data.schemeSpecificPart
                    if (!number.isNullOrBlank()) {
                        if (i.action == Intent.ACTION_CALL) {
                            vm.makeCall(number)
                        } else {
                            vm.setDialString(androidx.compose.ui.text.input.TextFieldValue(
                                text = number,
                                selection = androidx.compose.ui.text.TextRange(number.length)
                            ))
                        }
                    }
                }
            } else if (i.action == Intent.ACTION_PROCESS_TEXT) {
                val text = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    vm.setDialString(androidx.compose.ui.text.input.TextFieldValue(
                        text = text,
                        selection = androidx.compose.ui.text.TextRange(text.length)
                    ))
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
            required.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray())

        lifecycleScope.launch {
            val shown = vm.repo.batteryNoticeShown.first()
            if (!shown) {
                checkBatteryOptimizations()
            }
        }
    }

    private var showBatteryDialog = mutableStateOf(false)

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryDialog.value = true
        }
        lifecycleScope.launch {
            vm.setBatteryNoticeShown(true)
        }
    }
}

sealed class NavDest(val route: String, val label: String, val icon: ImageVector) {
    object Home    : NavDest("home",    "Home",     Icons.Default.Home)
    object Keypad  : NavDest("keypad",  "Keypad",   Icons.Default.Dialpad)
    object Contacts: NavDest("contacts","Contacts", Icons.Default.Contacts)
    object Settings: NavDest("settings","Settings", Icons.Default.Settings)
    object Accounts: NavDest("accounts","Accounts", Icons.Default.AccountBalance)
    object About   : NavDest("about",   "About",    Icons.Default.Info)
    object Recordings: NavDest("recordings", "Recordings", Icons.Default.Mic)
    object Logs    : NavDest("logs",    "Activity Log", Icons.AutoMirrored.Filled.List)
    object GetPro  : NavDest("get_pro",  "IPDial Pro",   Icons.Default.CardGiftcard)
    object Privacy : NavDest("privacy",  "Privacy Policy", Icons.Default.PrivacyTip)
    object AudioCodecs : NavDest("audio_codecs", "Audio Codecs", Icons.Default.Audiotrack)
    object ThemeSettings : NavDest("theme_settings", "Theme", Icons.Default.Settings)
    object IncomingCallStyle : NavDest("incoming_call_style", "Incoming Call Style", Icons.Default.Call)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IPDialApp() {
    val vm: SipViewModel = viewModel()
    val callSession by vm.callSession.collectAsState()
    val showFullIncomingScreen by vm.showFullIncomingScreen.collectAsState()
    
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route ?: NavDest.Home.route

    val pagerState = rememberPagerState(pageCount = { 3 })

    // Sync navController with pager (immediate skip to avoid double-animation)
    LaunchedEffect(currentRoute) {
        val targetPage = when (currentRoute) {
            NavDest.Home.route -> 0
            NavDest.Keypad.route -> 1
            NavDest.Contacts.route -> 2
            else -> -1
        }
        if (targetPage != -1 && pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // Sync pager with navController for back button and other nav logic
    // We remove the NavController navigation on swipe to avoid instantiating multiple 
    // HorizontalPager composables at once during transitions, which causes massive lag.
    // The bottom bar now syncs its highlight directly from pagerState.

    // Navigation drawer state
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    UpdateCheckDialog()

    // Wrap the entire app in Ltr by default, but ModalNavigationDrawer uses LocalLayoutDirection
    // to decide which side it opens from. We want it to open from the right.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            scrimColor = if (isGlass) Color.Black.copy(alpha = 0.3f) else DrawerDefaults.scrimColor,
            drawerContent = {
                // Wrap drawer content back to Ltr so text isn't flipped
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    AppDrawerSheet(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            scope.launch { drawerState.close() }
                            navController.graph?.let { graph ->
                                navController.navigate(route) {
                                    popUpTo(graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            }
        ) {
            // Wrap main app content back to Ltr
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(modifier = Modifier.fillMaxSize().then(
                    if (isGlass && (drawerState.isOpen || drawerState.isAnimationRunning)) 
                        Modifier.blur(20.dp) else Modifier
                )) {
                    AppScaffold(
                        vm = vm,
                        navController = navController,
                        pagerState = pagerState,
                        currentRoute = currentRoute,
                        callSession = callSession,
                        showFullIncomingScreen = showFullIncomingScreen,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onShowFullIncoming = { vm.setShowFullIncomingScreen(true) }
                    )
                }
            }
        }
    }
}

@Composable
fun UpdateCheckDialog() {
    var updateRelease by remember { mutableStateOf<com.ipdial.util.UpdateChecker.GitHubRelease?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        try {
            val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
            updateRelease = com.ipdial.util.UpdateChecker.checkForUpdates(currentVersion)
        } catch (_: Exception) {}
    }

    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

    if (updateRelease != null) {
        AlertDialog(
            onDismissRequest = { updateRelease = null },
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
            modifier = if (isGlass) Modifier.glass(MaterialTheme.shapes.extraLarge, alpha = 0.95f) else Modifier,
            title = { Text("Update Available") },
            text = {
                val description = updateRelease?.body?.takeIf { it.isNotBlank() }
                Text("A new version (${updateRelease?.tagName}) is available on GitHub. Would you like to download it?" + (description?.let { "\n\n$it" } ?: ""))
            },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, updateRelease?.htmlUrl?.toUri())
                    context.startActivity(intent)
                    updateRelease = null
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { updateRelease = null }) { Text("Later") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerSheet(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    val vm: SipViewModel = viewModel()
    val isPro by vm.isPro.collectAsState()
    val proExpiration by vm.proExpiration.collectAsState()
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz

    val context = androidx.compose.ui.platform.LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    val items = remember(isPro) {
        val list = mutableListOf(
            NavDest.Home,
            NavDest.Accounts,
            NavDest.Recordings,
            NavDest.Settings,
            NavDest.GetPro,
            NavDest.Privacy,
            NavDest.About
        )
        list
    }

    val drawerShape = androidx.compose.ui.graphics.RectangleShape
    ModalDrawerSheet(
        modifier = Modifier
            .width(300.dp)
            .then(if (isGlass) Modifier.glass(drawerShape, alpha = 0.9f) else Modifier),
        drawerShape = drawerShape,
        drawerContainerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 0.dp
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Menu",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge
        )
        HorizontalDivider()
        items.forEach { dest ->
            val labelText = if (dest == NavDest.GetPro && isPro) {
                val diff = proExpiration - System.currentTimeMillis()
                val days = maxOf(0, java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff) + 1)
                "${dest.label} ($days days left)"
            } else dest.label

            val labelColor = if (dest == NavDest.GetPro) Color(0xFFBC4749) else Color.Unspecified
            
            NavigationDrawerItem(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .height(44.dp),
                label = { Text(labelText, color = labelColor) },
                selected = currentRoute == dest.route,
                onClick = { onNavigate(dest.route) },
                icon = { Icon(dest.icon, null, tint = if (labelColor != Color.Unspecified) labelColor else LocalContentColor.current) },
                colors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = Color.Transparent,
                    selectedContainerColor = when {
                        isQuartz -> Color.Black.copy(alpha = 0.1f)
                        isGlass -> Color.White.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    selectedTextColor = when {
                        isQuartz -> Color.Black
                        isGlass -> Color.White
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    unselectedTextColor = when {
                        isQuartz -> Color.Black.copy(alpha = 0.7f)
                        isGlass -> Color.White.copy(alpha = 0.8f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    selectedIconColor = when {
                        isQuartz -> Color.Black
                        isGlass -> Color.White
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    unselectedIconColor = when {
                        isQuartz -> Color.Black.copy(alpha = 0.7f)
                        isGlass -> Color.White.copy(alpha = 0.8f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )

        // Exit button at the bottom of the drawer
        NavigationDrawerItem(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .height(44.dp),
            label = { Text("Exit") },
            selected = false,
            onClick = { showExitDialog = true },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                selectedContainerColor = Color.Transparent,
                unselectedTextColor = MaterialTheme.colorScheme.error,
                unselectedIconColor = MaterialTheme.colorScheme.error
            )
        )

        // Exit confirmation dialog
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Exit IPDial?") },
                text = { Text("This will close the app and stop all background processes. You will not receive incoming calls.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitDialog = false
                            // Stop the foreground SIP service — its onDestroy calls SipEngine.destroy()
                            context.stopService(Intent(context, com.ipdial.service.SipService::class.java))
                            // Tear down any active Telecom connections
                            com.ipdial.service.SipConnectionService.destroyAll()
                            // Null out the call session
                            com.ipdial.service.SipEngine._callSession.value = null
                            // Finish activity and kill process so nothing runs in background
                            (context as? android.app.Activity)?.finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                            System.exit(0)
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Exit") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun AppScaffold(
    vm: SipViewModel,
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    currentRoute: String,
    callSession: CallSession?,
    showFullIncomingScreen: Boolean,
    onOpenDrawer: () -> Unit,
    onShowFullIncoming: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
        bottomBar = {
            AppBottomBar(navController, pagerState, currentRoute, callSession, showFullIncomingScreen)
        }
    ) { innerPadding ->
        val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
        AppMainContent(
            vm = vm,
            navController = navController,
            pagerState = pagerState,
            innerPadding = innerPadding,
            callSession = callSession,
            showFullIncomingScreen = showFullIncomingScreen,
            onOpenDrawer = onOpenDrawer,
            onShowFullIncoming = onShowFullIncoming
        )

        val showProPopup by vm.showProBlockPopup.collectAsState()
        if (showProPopup) {
            AlertDialog(
                onDismissRequest = { vm.dismissProPopup() },
                containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
                modifier = if (isGlass) Modifier.glass(MaterialTheme.shapes.extraLarge, alpha = 0.95f) else Modifier,
                title = { Text("Pro Feature") },
                text = { Text("Upgrade to IPDial Pro to unlock this feature and enjoy an ad-free experience!") },
                confirmButton = {
                    Button(onClick = {
                        vm.dismissProPopup()
                        navController.graph?.let { graph ->
                            navController.navigate(NavDest.GetPro.route) {
                                popUpTo(graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                            }
                        }
                    }) {
                        Text("Get Pro for Free!")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissProPopup() }) {
                        Text("Later")
                    }
                }
            )
        }

        val adGateCallback by vm.adGateCallback.collectAsState()
        if (adGateCallback != null) {
            AlertDialog(
                onDismissRequest = { vm.dismissAdGate() },
                containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
                modifier = if (isGlass) Modifier.glass(MaterialTheme.shapes.extraLarge, alpha = 0.95f) else Modifier,
                title = { Text("Watch Ad to Unlock") },
                text = { Text("Please watch a short video to use this feature for free, or upgrade to Pro for unlimited access.") },
                confirmButton = {
                    Button(onClick = {
                        vm.triggerAdGate(context)
                    }) {
                        Text("Watch Ad")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissAdGate() }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun AppBottomBar(
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    currentRoute: String,
    callSession: CallSession?,
    showFullIncomingScreen: Boolean
) {
    // Remove GetPro from the bottom bar; it is available in the drawer as "IPDial Pro"
    val bottomTabs = listOf(NavDest.Home, NavDest.Keypad, NavDest.Contacts)
    val coroutineScope = rememberCoroutineScope()

    val isMainScreen = currentRoute == NavDest.Home.route || currentRoute == NavDest.Keypad.route || currentRoute == NavDest.Contacts.route
    val hasActiveCall = callSession != null && callSession.state != CallState.DISCONNECTED
    val showBottomBar = !hasActiveCall && (isMainScreen ||
                        ((callSession == null || !showFullIncomingScreen) &&
                         currentRoute == NavDest.GetPro.route))
    
    if (showBottomBar) {
        val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

        NavigationBar(
            tonalElevation = 0.dp,
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface
        ) {
            bottomTabs.forEachIndexed { index, dest ->
                val isSelected = if (isMainScreen) {
                    pagerState.currentPage == index
                } else {
                    currentRoute == dest.route
                }

                NavigationBarItem(
                    selected = isSelected,
                    onClick = {
                        if (isMainScreen) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        } else {
                            navController.graph?.let { graph ->
                                navController.navigate(dest.route) {
                                    popUpTo(graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            }
                        }
                    },
                    icon = { Icon(dest.icon, dest.label, modifier = Modifier.size(20.dp)) },
                    label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
fun AppMainContent(
    vm: SipViewModel,
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    innerPadding: PaddingValues,
    callSession: CallSession?,
    showFullIncomingScreen: Boolean,
    onOpenDrawer: () -> Unit,
    onShowFullIncoming: () -> Unit
) {
    Log.d("MainActivity", "AppMainContent: session=${callSession?.state}, showFull=$showFullIncomingScreen")
    
    // Logic: Always show CallOverlay if there is an active call (incoming OR outgoing)
    // regardless of showFullIncomingScreen flag, as long as it's not disconnected.
    val hasActiveCall = callSession != null && callSession.state != CallState.DISCONNECTED
    
    if (hasActiveCall) {
        CallOverlay(vm, callSession!!)
    } else {
        AppNavHost(vm, navController, pagerState, innerPadding, onOpenDrawer)
    }
}

@Composable
fun CallOverlay(vm: SipViewModel, session: CallSession) {
    if (session.state == CallState.DISCONNECTED) return
    when (session.direction) {
        CallDirection.INCOMING -> {
            if (session.state == CallState.INCOMING || session.state == CallState.EARLY) {
                IncomingCallScreen(vm = vm, session = session)
            } else {
                CallScreen(vm = vm, session = session)
            }
        }
        else -> {
            CallScreen(vm = vm, session = session)
        }
    }
}

@Composable
fun MainPagerScreen(
    vm: SipViewModel,
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    onOpenDrawer: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        com.ipdial.ui.components.IPDialTopBar(
            accounts = accounts,
            vm = vm,
            onOpenDrawer = onOpenDrawer,
            onAddAccount = { navController.navigate(NavDest.Accounts.route) }
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    vm = vm, 
                    onOpenDrawer = onOpenDrawer,
                    onNavigateToAccounts = { navController.navigate(NavDest.Accounts.route) },
                    onEditBeforeCall = { number ->
                    vm.prefillDialer(number)
                    navController.graph?.let { graph ->
                        navController.navigate(NavDest.Keypad.route) {
                            popUpTo(graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    }
                }
            )
            1 -> DialpadScreen(
                vm = vm, 
                onOpenDrawer = onOpenDrawer,
                onNavigateToAccounts = { navController.navigate(NavDest.Accounts.route) }
            )
            2 -> ContactsScreen(
                vm = vm, 
                onOpenDrawer = onOpenDrawer,
                onNavigateToAccounts = { navController.navigate(NavDest.Accounts.route) }
            )
        }
    }
    }
}

@Composable
fun AppNavHost(
    vm: SipViewModel,
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    innerPadding: PaddingValues,
    onOpenDrawer: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = NavDest.Home.route,
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        composable(NavDest.Home.route) { 
            MainPagerScreen(vm, navController, pagerState, onOpenDrawer)
        }
        composable(NavDest.Keypad.route) { 
            MainPagerScreen(vm, navController, pagerState, onOpenDrawer)
        }
        composable(NavDest.Contacts.route) { 
            MainPagerScreen(vm, navController, pagerState, onOpenDrawer)
        }
        composable(NavDest.Settings.route) { 
            SettingsScreen(
                vm = vm, 
                onOpenDrawer = onOpenDrawer,
                onNavigateToLogs = { navController.navigate(NavDest.Logs.route) },
                onNavigateToCodecs = { navController.navigate(NavDest.AudioCodecs.route) },
                onNavigateToTheme = { navController.navigate(NavDest.ThemeSettings.route) },
                onNavigateToIncomingCallStyle = { navController.navigate(NavDest.IncomingCallStyle.route) }
            ) 
        }
        composable(NavDest.ThemeSettings.route) {
            ThemeSettingsScreen(
                vm = vm,
                onOpenDrawer = onOpenDrawer,
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavDest.IncomingCallStyle.route) {
            IncomingCallSettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavDest.AudioCodecs.route) {
            AudioCodecScreen(
                vm = vm,
                onOpenDrawer = onOpenDrawer,
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavDest.Accounts.route) { 
            AccountsScreen(vm = vm, onOpenDrawer = onOpenDrawer) 
        }
        composable(NavDest.Recordings.route) {
            RecordingsScreen(vm = vm, onOpenDrawer = onOpenDrawer)
        }
        composable(NavDest.Logs.route) {
            ActivityLogScreen(vm = vm, onOpenDrawer = onOpenDrawer)
        }
        composable(NavDest.About.route) { 
            AboutScreen(vm = vm, onOpenDrawer = onOpenDrawer)
        }
        composable(NavDest.Privacy.route) {
            PrivacyPolicyScreen(vm = vm, onOpenDrawer = onOpenDrawer)
        }
        composable(NavDest.GetPro.route) {
            GetProScreen(vm = vm, onOpenDrawer = onOpenDrawer)
        }
    }
}


