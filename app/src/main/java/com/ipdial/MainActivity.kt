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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ipdial.R
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.AppMenuBottomSheet
import com.ipdial.ui.components.FloatingPillNavBar
import com.ipdial.ui.screens.AboutScreen
import com.ipdial.ui.screens.AccountsScreen
import com.ipdial.ui.screens.ActivityLogScreen
import com.ipdial.ui.screens.AudioCodecScreen
import com.ipdial.ui.screens.CallScreen
import com.ipdial.ui.screens.ContactsScreen
import com.ipdial.ui.screens.DialpadScreen
import com.ipdial.ui.screens.DialpadStyleScreen
import com.ipdial.ui.screens.GetProScreen
import com.ipdial.ui.screens.home.HomeScreen
import com.ipdial.ui.screens.IncomingCallScreen
import com.ipdial.ui.screens.IncomingCallSettingsScreen
import com.ipdial.ui.screens.PrivacyPolicyScreen
import com.ipdial.ui.screens.RecordingsScreen
import com.ipdial.ui.screens.ServerInspectorScreen
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
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startSipServiceIfPermitted()
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

    override fun onDestroy() {
        super.onDestroy()
        handledVolumeDownTimes.clear()
    }

    // Volume presses whose ACTION_DOWN was already handled by this activity.
    // Some OEM builds (ColorOS/EMUI) swallow the ACTION_DOWN of volume buttons
    // while a call is active and only forward the ACTION_UP, so we fall back to
    // handling the UP event for presses we never saw a DOWN for. Keyed on
    // event.downTime so each physical press adjusts exactly once.
    // Volume presses whose ACTION_DOWN was already handled by this activity.
    // Some OEM builds (ColorOS/EMUI) swallow the ACTION_DOWN of volume buttons
    // while a call is active and only forward the ACTION_UP, so we fall back to
    // handling the UP event for presses we never saw a DOWN for. Keyed on
    // event.downTime so each physical press adjusts exactly once.
    private val handledVolumeDownTimes = java.util.Collections.synchronizedCollection(
        java.util.LinkedHashSet<Long>()
    )

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Whenever ANY call screen is visible (incoming, dialing/ringing CALLING,
        // EARLY, or an active connected call), redirect the physical volume buttons
        // to the in-app call volume (PJSIP RX gain). Both ACTION_DOWN and ACTION_UP
        // are consumed so the system never ALSO adjusts the SIP-unused voice-call
        // stream or pops its volume HUD over the call screen. When no call screen
        // is visible the event falls through to the default media-volume handling.
        // This also makes the volume keys work during the CALLING phase (before the
        // media stream is ACTIVE): adjustCallVolumeByHardware stores the new factor
        // on the session, and onCallMediaState applies rxVolume the instant the
        // audio path is established.
        val isVolumeKey = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey && isCallScreenVisible(vm.callSession.value)) {
            val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    // Normal devices deliver the DOWN first. Record it so the
                    // matching UP is not double-counted, then adjust.
                    handledVolumeDownTimes.add(event.downTime)
                    vm.adjustCallVolumeByHardware(up)
                }
                android.view.KeyEvent.ACTION_UP -> {
                    // Oplus/OEM builds may never have delivered our DOWN. If we
                    // never handled this press, adjust now (exactly once).
                    if (!handledVolumeDownTimes.remove(event.downTime)) {
                        vm.adjustCallVolumeByHardware(up)
                    }
                }
            }
            // Keep the down-times set bounded (each entry is one physical press).
            if (handledVolumeDownTimes.size > 64) {
                handledVolumeDownTimes.clear()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            enableEdgeToEdge()
        } catch (e: Throwable) {
            // Devices whose framework is missing API 29+ WindowInsets$Type" /
            // Type.systemOverlays() (e.g. fake/upgraded Android images) throw a
            // NoSuchMethodError here and again on the first insets read. Fall back
            // to legacy decor-fits-system-windows so the app still renders.
            Log.e("MainActivity", "enableEdgeToEdge failed", e)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        }
        super.onCreate(savedInstanceState)
        
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        
        requestRequiredPermissions()
        // SipService is started from IPDialApplication.onCreate() so that
        // startForeground() runs before Android's 5-second FGS timeout expires.
        // The service checks RECORD_AUDIO permission itself and stops if missing;
        // startSipServiceIfPermitted() restarts it once permission is granted.

        handleIntent(intent)

        setContent {
            val vm: SipViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val fontMultiplier by vm.fontSizeMultiplier.collectAsState()

            // Keep screen on when there's an active call
            val callSession by vm.callSession.collectAsState()
            val localView = LocalView.current
            
            LaunchedEffect(callSession) {
                val window = (localView.context as? android.app.Activity)?.window
                val activity = localView.context as? android.app.Activity
                val isActiveCall = isCallScreenVisible(callSession)
                if (isActiveCall) {
                    // Route volume keys based on what's actually audible:
                    //  - INCOMING ringing: the app's ringtone plays on STREAM_RING,
                    //    so buttons control ring/ringer volume (a press also silences
                    //    the ringer on most devices).
                    //  - Outgoing CALLING/EARLY: ringback/early media comes over the
                    //    voice RTP path (MODE_IN_COMMUNICATION) — STREAM_VOICE_CALL.
                    //  - CONFIRMED: in-call audio — STREAM_VOICE_CALL.
                    val isIncomingRinging =
                        callSession?.direction == com.ipdial.data.model.CallDirection.INCOMING &&
                            (callSession?.state == com.ipdial.data.model.CallState.INCOMING ||
                                callSession?.state == com.ipdial.data.model.CallState.EARLY)
                    activity?.volumeControlStream = if (isIncomingRinging) {
                        android.media.AudioManager.STREAM_RING
                    } else {
                        android.media.AudioManager.STREAM_VOICE_CALL
                    }
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
                    }
                    @Suppress("DEPRECATION")
                    window?.clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    )
                    // Keep the app in the foreground after hangup so users return
                    // to the in-app screen instead of being sent to the launcher.
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
        if (intent.action == "com.ipdial.ACTION_INCOMING_CALL" ||
            intent.action == "com.ipdial.ACTION_SHOW_CALL") {
            applyLockScreenFlags()
        }
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

    private fun startSipServiceIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            com.ipdial.service.SipService.start(this)
        } else {
            Log.w("MainActivity", "SIP service delayed until microphone permission is granted")
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
    object DialpadStyle : NavDest("dialpad_style", "Dialpad Style", Icons.Default.Dialpad)
    object ServerInspector : NavDest("server_inspector", "Server Inspector", Icons.Default.NetworkCheck)
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

    var showMenuBottomSheet by remember { mutableStateOf(false) }

    UpdateCheckDialog()

    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

    Box(modifier = Modifier.fillMaxSize()) {
        AppScaffold(
            vm = vm,
            navController = navController,
            pagerState = pagerState,
            currentRoute = currentRoute,
            callSession = callSession,
            showFullIncomingScreen = showFullIncomingScreen,
            isMenuOpen = showMenuBottomSheet,
            onOpenMenu = { showMenuBottomSheet = true },
            onShowFullIncoming = { vm.setShowFullIncomingScreen(true) }
        )

        if (showMenuBottomSheet) {
            AppMenuBottomSheet(
                onDismissRequest = { showMenuBottomSheet = false },
                onNavigate = { route ->
                    navController.graph.let { graph ->
                        navController.navigate(route) {
                            popUpTo(graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                vm = vm
            )
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

@Composable
fun AppScaffold(
    vm: SipViewModel,
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    currentRoute: String,
    callSession: CallSession?,
    showFullIncomingScreen: Boolean,
    isMenuOpen: Boolean = false,
    onOpenMenu: () -> Unit,
    onShowFullIncoming: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isGlass) Color.Transparent else MaterialTheme.colorScheme.background)
    ) {
        AppMainContent(
            vm = vm,
            navController = navController,
            pagerState = pagerState,
            innerPadding = PaddingValues(0.dp),
            callSession = callSession,
            showFullIncomingScreen = showFullIncomingScreen,
            onOpenMenu = onOpenMenu,
            onShowFullIncoming = onShowFullIncoming
        )

        if (!isMenuOpen) {
            FloatingPillNavBar(
                navController = navController,
                pagerState = pagerState,
                currentRoute = currentRoute,
                callSession = callSession,
                showFullIncomingScreen = showFullIncomingScreen,
                onOpenMenu = onOpenMenu,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Sign-in-for-Pro offer for signed-out users — points & Pro only survive
        // reinstall/data-clear if backed up to their Google account via Firestore.
        // Shown on Home tab only, until dismissed.
        if (currentRoute == NavDest.Home.route) {
            GoogleSignInBackupBanner(
                vm = vm,
                onOpenGetPro = {
                    navController.graph.let { graph ->
                        navController.navigate(NavDest.GetPro.route) {
                            popUpTo(graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp, start = 16.dp, end = 16.dp)
            )
        }

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
                        navController.graph.let { graph ->
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

/**
 * True while ANY call screen is visible to the user (incoming call screen,
 * outgoing dialing/ringing screen, or an active connected call screen). This is
 * the single source of truth used by the call overlay, the volume-button
 * interception, and the volume-control-stream / keep-screen-on logic so they can
 * never drift apart.
 */
private fun isCallScreenVisible(session: CallSession?): Boolean =
    session != null && session.state != CallState.DISCONNECTED

@Composable
fun AppMainContent(
    vm: SipViewModel,
    navController: androidx.navigation.NavHostController,
    pagerState: PagerState,
    innerPadding: PaddingValues,
    callSession: CallSession?,
    showFullIncomingScreen: Boolean,
    onOpenMenu: () -> Unit,
    onShowFullIncoming: () -> Unit
) {
    Log.d("MainActivity", "AppMainContent: session=${callSession?.state}, showFull=$showFullIncomingScreen")
    
    // Logic: Always show CallOverlay if there is an active call (incoming OR outgoing)
    // regardless of showFullIncomingScreen flag, as long as it's not disconnected.
    val hasActiveCall = isCallScreenVisible(callSession)
    
    if (hasActiveCall) {
        CallOverlay(vm, callSession!!)
    } else {
        AppNavHost(vm, navController, pagerState, innerPadding, onOpenMenu)
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
    onOpenMenu: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        com.ipdial.ui.components.IPDialTopBar(
            accounts = accounts,
            vm = vm,
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
                    onOpenDrawer = onOpenMenu,
                    onNavigateToAccounts = { navController.navigate(NavDest.Accounts.route) },
                    onEditBeforeCall = { number ->
                        vm.prefillDialer(number)
                        navController.graph.let { graph ->
                            navController.navigate(NavDest.Keypad.route) {
                                popUpTo(graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        }
                    }
                )
                1 -> DialpadScreen(
                    vm = vm, 
                    onOpenDrawer = onOpenMenu,
                    onNavigateToAccounts = { navController.navigate(NavDest.Accounts.route) }
                )
                2 -> ContactsScreen(
                    vm = vm, 
                    onOpenDrawer = onOpenMenu,
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
    onOpenMenu: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = NavDest.Home.route,
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        composable(NavDest.Home.route) { 
            MainPagerScreen(vm, navController, pagerState, onOpenMenu)
        }
        composable(NavDest.Keypad.route) { 
            MainPagerScreen(vm, navController, pagerState, onOpenMenu)
        }
        composable(NavDest.Contacts.route) { 
            MainPagerScreen(vm, navController, pagerState, onOpenMenu)
        }
        composable(NavDest.Settings.route) { 
            SettingsScreen(
                vm = vm, 
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu,
                onNavigateToLogs = { navController.navigate(NavDest.Logs.route) },
                onNavigateToCodecs = { navController.navigate(NavDest.AudioCodecs.route) },
                onNavigateToTheme = { navController.navigate(NavDest.ThemeSettings.route) },
                onNavigateToIncomingCallStyle = { navController.navigate(NavDest.IncomingCallStyle.route) },
                onNavigateToPrivacy = { navController.navigate(NavDest.Privacy.route) },
                onNavigateToAbout = { navController.navigate(NavDest.About.route) },
                onNavigateToServerInspector = { navController.navigate(NavDest.ServerInspector.route) }
            ) 
        }
        composable(NavDest.ThemeSettings.route) {
            ThemeSettingsScreen(
                vm = vm,
                onOpenDrawer = onOpenMenu,
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
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.Accounts.route) { 
            AccountsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            ) 
        }
        composable(NavDest.Recordings.route) {
            RecordingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.Logs.route) {
            ActivityLogScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.DialpadStyle.route) {
            DialpadStyleScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.About.route) { 
            AboutScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.Privacy.route) {
            PrivacyPolicyScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.GetPro.route) {
            GetProScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDrawer = onOpenMenu
            )
        }
        composable(NavDest.ServerInspector.route) {
            ServerInspectorScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Persistent top banner shown to existing Pro users who haven't signed in with
 * Google. Their points and Pro subscription only survive reinstall /
 * data-clear if synced to their Google account via Firestore — so we encourage
 * them to sign in to avoid losing anything.
 */
@Composable
private fun GoogleSignInBackupBanner(
    vm: SipViewModel,
    modifier: Modifier = Modifier,
    onOpenGetPro: () -> Unit = {}
) {
    val isPro by vm.isPro.collectAsState()
    val isSignedIn by vm.isSignedIn.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    var isSigningIn by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Signed-in users don't need the offer; dismissed hides it for this visit.
    if (dismissed || isSignedIn) return

    val title = if (isPro) {
        "You're Pro — secure it with Google"
    } else {
        "Go Pro free — sign in with Google"
    }
    val subtitle = if (isPro) {
        "Sign in to keep your points & Pro subscription safe on any device. Don't lose them!"
    } else {
        "Sign in to earn points, unlock Pro features, and keep them on any device."
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        shadowElevation = 6.dp,
        onClick = onOpenGetPro,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
                    )
                )
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Google "G" avatar
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(com.ipdial.R.drawable.ic_google_g),
                            contentDescription = "Google",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    onClick = {
                        if (!isSigningIn) {
                            isSigningIn = true
                            vm.signIn(context) { success, msg ->
                                isSigningIn = false
                                if (!success && msg.isNotBlank()) {
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isSigningIn
                ) {
                    Text(
                        text = if (isSigningIn) "Signing in..." else "Sign in",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4A00E0)
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                IconButton(
                    onClick = { dismissed = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}



