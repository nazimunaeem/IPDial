package com.ipdial

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.material.icons.filled.Person
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.ipdial.ui.screens.CallStatsScreen
import com.ipdial.ui.screens.ContactDetailsScreen
import com.ipdial.ui.screens.ContactsScreen
import com.ipdial.ui.screens.DialpadScreen
import com.ipdial.ui.screens.DialpadStyleScreen
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
    // Mirrors the "Disable proximity sensor" setting so the SIP service can skip the
    // proximity wake lock without touching DataStore from the service thread.
    var disableProximitySensor = false
}

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.READ_CONTACTS] == true) {
            vm.syncContacts()
        }
        if (results[Manifest.permission.READ_PHONE_STATE] == true) {
            vm.refreshSimAccounts()
        }
        if (results[Manifest.permission.READ_CALL_LOG] == true) {
            vm.refreshCallHistory()
        }
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startSipServiceIfPermitted()
        }
    }

    private val vm: SipViewModel by viewModels()

    private fun currentLiveSession(): CallSession? =
        com.ipdial.service.SipEngine.callSession.value
            ?: com.ipdial.service.sim.SimCallManager.callSession.value
            ?: vm.callSession.value

    override fun onStart() {
        super.onStart()
        if (isCallScreenVisible(currentLiveSession())) {
            applyLockScreenFlags()
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.isForeground = true
        vm.refreshSimAccounts()
        vm.refreshCallHistory()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            vm.syncContacts()
        }
        val session = currentLiveSession()
        if (isCallScreenVisible(session)) {
            applyLockScreenFlags()
            vm.setShowFullIncomingScreen(true)
        } else if (!com.ipdial.service.IncomingCallPresentationHelper.isCallLaunchAction(intent?.action)) {
            clearLockScreenFlags()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isCallScreenVisible(currentLiveSession())) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(com.ipdial.service.NOTIF_ID_INCOMING)
        }
    }

    override fun onPause() {
        super.onPause()
        AppState.isForeground = false
        val session = currentLiveSession()
        if (isCallScreenVisible(session) && session != null) {
            com.ipdial.service.showCallNotificationStatic(this, session.remoteDisplayName, session.callId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            val session = vm.callSession.value
            val isIncomingRinging = session != null &&
                session.direction == CallDirection.INCOMING &&
                (session.state == CallState.INCOMING || session.state == CallState.EARLY)
            if (isIncomingRinging) {
                // Silencing the ringtone on volume button press is standard for incoming calls
                com.ipdial.service.SipService.silenceRinger(this)
                com.ipdial.service.sim.SimCallManager.silenceRinger()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun updateVolumeControlStream(session: CallSession?) {
        val isActiveCall = isCallScreenVisible(session)
        volumeControlStream = if (isActiveCall) {
            val isIncomingRinging = session?.direction == CallDirection.INCOMING &&
                (session.state == CallState.INCOMING || session.state == CallState.EARLY)
            if (isIncomingRinging) {
                android.media.AudioManager.STREAM_RING
            } else {
                android.media.AudioManager.STREAM_VOICE_CALL
            }
        } else {
            android.media.AudioManager.STREAM_MUSIC
        }
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

        if (com.ipdial.service.IncomingCallPresentationHelper.isCallLaunchAction(intent?.action) ||
            isCallScreenVisible(currentLiveSession())
        ) {
            applyLockScreenFlags()
        }

        updateVolumeControlStream(currentLiveSession())
        lifecycleScope.launch {
            vm.callSession.collect { session ->
                updateVolumeControlStream(session)
            }
        }
        
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

            // Keep screen on and show over lock screen when there's an active call
            val callSession by vm.callSession.collectAsState()
            val localView = LocalView.current
            
            LaunchedEffect(callSession) {
                val activity = localView.context as? MainActivity
                val isActiveCall = isCallScreenVisible(callSession)
                activity?.updateVolumeControlStream(callSession)
                if (isActiveCall) {
                    activity?.applyLockScreenFlags()
                } else {
                    activity?.clearLockScreenFlags()
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
                                "DialDuo needs to run in the background to receive incoming calls. " +
                                "Battery optimization may prevent calls from reaching you.\n\n" +
                                "Please disable battery optimization for DialDuo to ensure reliable call reception."
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
        if (com.ipdial.service.IncomingCallPresentationHelper.isCallLaunchAction(intent.action) ||
            isCallScreenVisible(currentLiveSession())
        ) {
            applyLockScreenFlags()
        }
        handleIntent(intent)
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun clearLockScreenFlags() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(false)
            setShowWhenLocked(false)
        }
        @Suppress("DEPRECATION")
        window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
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
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            vm.syncContacts()
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
    object Privacy : NavDest("privacy",  "Privacy Policy", Icons.Default.PrivacyTip)
    object AudioCodecs : NavDest("audio_codecs", "Audio Codecs", Icons.Default.Audiotrack)
    object ThemeSettings : NavDest("theme_settings", "Theme", Icons.Default.Settings)
    object IncomingCallStyle : NavDest("incoming_call_style", "Incoming Call Style", Icons.Default.Call)
    object DialpadStyle : NavDest("dialpad_style", "Dialpad Style", Icons.Default.Dialpad)
    object ServerInspector : NavDest("server_inspector", "Server Inspector", Icons.Default.NetworkCheck)
    object CallStats : NavDest("call_stats?contactId={contactId}&number={number}&title={title}", "Call Statistics", Icons.Default.BarChart) {
        fun route(contactId: String = "", number: String = "", title: String = ""): String =
            "call_stats?contactId=${android.net.Uri.encode(contactId)}&number=${android.net.Uri.encode(number)}&title=${android.net.Uri.encode(title)}"
    }
    object ContactDetails : NavDest("contact_details/{contactId}", "Contact Details", Icons.Default.Person) {
        fun route(contactId: String): String = "contact_details/${android.net.Uri.encode(contactId)}"
    }
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

    val showAccountSelection by vm.showAccountSelectionDialog.collectAsState()
    val pendingCallNumber by vm.pendingCallNumber.collectAsState()
    val pendingCallName by vm.pendingCallName.collectAsState()
    val simLines by vm.simLines.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val balances by vm.balances.collectAsState()
    val selectedLineId by vm.selectedAccountId.collectAsState()
    val ussdSession by vm.ussdDialog.collectAsState()
    val enabledAccounts = remember(accounts) {
        accounts.filter { it.isEnabled }
    }

    if (showAccountSelection && (enabledAccounts.isNotEmpty() || simLines.isNotEmpty())) {
        com.ipdial.ui.components.AccountSelectionDialog(
            pendingNumber = pendingCallNumber,
            pendingDisplayName = pendingCallName,
            simLines = simLines,
            enabledAccounts = enabledAccounts,
            balances = balances,
            selectedLineId = selectedLineId,
            onSimSelected = { vm.proceedWithSimCall(it) },
            onAccountSelected = { vm.proceedWithCallAfterAccountSelection(it) },
            onDismiss = { vm.dismissAccountSelection() }
        )
    }

    if (ussdSession != null) {
        val session = ussdSession!!
        AlertDialog(
            onDismissRequest = { vm.dismissUssdDialog() },
            title = { Text("USSD · ${session.request}") },
            text = {
                Text(
                    session.error
                        ?: session.response?.takeIf { it.isNotBlank() }
                        ?: "Request sent. No response from the network."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissUssdDialog() }) { Text("OK") }
            }
        )
    }

    // "Open dialpad at launch" — jump to the keypad the first time the setting resolves.
    val openDialpadAtLaunch by vm.openDialpadAtLaunch.collectAsState()
    var appliedLaunchTab by remember { mutableStateOf(false) }
    LaunchedEffect(openDialpadAtLaunch, currentRoute) {
        if (!appliedLaunchTab && openDialpadAtLaunch && currentRoute == NavDest.Home.route) {
            appliedLaunchTab = true
            navController.navigate(NavDest.Keypad.route) {
                popUpTo(NavDest.Home.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

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
            val release = com.ipdial.util.UpdateChecker.checkForUpdates(currentVersion)
            if (release == null) {
                com.ipdial.util.UpdateChecker.cleanupCachedUpdates(context)
            }
            updateRelease = release
        } catch (_: Exception) {}
    }

    updateRelease?.let { release ->
        com.ipdial.ui.components.AppUpdateDialog(
            release = release,
            onDismiss = { updateRelease = null }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler {
        if (!com.ipdial.service.IncomingCallPresentationHelper.isDeviceLockedOrScreenOff(context)) {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }
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
                    },
                    onOpenContactDetails = { contactId ->
                        navController.navigate(NavDest.ContactDetails.route(contactId))
                    },
                    onOpenStats = { contactId, number, title ->
                        navController.navigate(NavDest.CallStats.route(contactId, number, title))
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
                    onNavigateToAccounts = { navController.navigate(NavDest.Accounts.route) },
                    onOpenContactDetails = { contact ->
                        navController.navigate(NavDest.ContactDetails.route(contact.id))
                    },
                    onOpenInDialpad = { number ->
                        vm.prefillDialer(number)
                        navController.graph.let { graph ->
                            navController.navigate(NavDest.Keypad.route) {
                                popUpTo(graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenContactStats = { contact ->
                        navController.navigate(
                            NavDest.CallStats.route(
                                contactId = contact.id,
                                number = contact.numbers.firstOrNull().orEmpty(),
                                title = "${contact.name} · Stats"
                            )
                        )
                    }
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
                onNavigateToDialpadStyle = { navController.navigate(NavDest.DialpadStyle.route) },
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
        composable(NavDest.ServerInspector.route) {
            ServerInspectorScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = NavDest.CallStats.route,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType; defaultValue = "" },
                navArgument("number") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val contactId = android.net.Uri.decode(backStackEntry.arguments?.getString("contactId").orEmpty())
            val number = android.net.Uri.decode(backStackEntry.arguments?.getString("number").orEmpty())
            val title = android.net.Uri.decode(backStackEntry.arguments?.getString("title").orEmpty())
            CallStatsScreen(
                vm = vm,
                contactId = contactId,
                targetNumber = number,
                customTitle = title,
                onBack = { navController.popBackStack() },
                onOpenContactDetails = { id ->
                    navController.navigate(NavDest.ContactDetails.route(id))
                }
            )
        }
        composable(
            route = NavDest.ContactDetails.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactId = android.net.Uri.decode(
                backStackEntry.arguments?.getString("contactId").orEmpty()
            )
            ContactDetailsScreen(
                contactId = contactId,
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenInDialpad = { number ->
                    vm.prefillDialer(number)
                    navController.graph.let { graph ->
                        navController.navigate(NavDest.Keypad.route) {
                            popUpTo(graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    }
                },
                onOpenStats = { contact ->
                    navController.navigate(
                        NavDest.CallStats.route(
                            contactId = contact.id,
                            number = contact.numbers.firstOrNull().orEmpty(),
                            title = "${contact.name} · Stats"
                        )
                    )
                }
            )
        }
    }
}



