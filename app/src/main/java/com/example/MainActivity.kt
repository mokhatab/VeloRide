package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.ai.configureAppCheck
import com.example.core.alerts.RideAlertPlayer
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.StringsProvider
import com.example.core.push.FcmPushCoordinator
import com.example.core.push.TripStatusNotificationHelper
import com.example.data.firestore.DispatchRepository
import com.example.data.firestore.DriverRepository
import com.example.data.firestore.FcmTokenRepository
import com.example.data.firestore.PricingAndAdminRepository
import com.example.data.firestore.UserRepository
import com.example.domain.mapping.OsrmMappingProvider
import com.example.domain.model.AccountMode
import com.example.domain.model.RideStatus
import com.example.domain.model.TripHistoryRole
import com.example.domain.payment.StripePaymentGateway
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.admin.AdminViewModel
import com.example.ui.ai.VeloAiScreen
import com.example.ui.ai.VeloAiViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.CompleteRegistrationScreen
import com.example.ui.auth.GoogleAuthHelper
import com.example.ui.components.PostTripRatingDialog
import com.example.ui.driver.DriverWorkspaceScreen
import com.example.ui.history.TripHistoryScreen
import com.example.ui.main.VeloRideViewModel
import com.example.ui.profile.ProfileScreen
import com.example.ui.rider.RideActivityScreen
import com.example.ui.rider.RiderHomeScreen
import com.example.ui.theme.VeloRideTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

const val ROUTE_HOME = "home"
const val ROUTE_ACTIVITY = "activity"
const val ROUTE_AI_ASSISTANT = "ai_assistant"
const val ROUTE_PROFILE = "profile"
const val ROUTE_ADMIN = "admin"
const val ROUTE_DRIVER_ONBOARDING = "driver_onboarding"
const val ROUTE_TRIP_HISTORY = "trip_history"

class MainActivity : ComponentActivity() {
    private var pendingDeepLinkRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        TripStatusNotificationHelper.ensureChannel(this)
        pendingDeepLinkRoute = extractNotificationRoute(intent)
        enableEdgeToEdge()
        setContent {
            VeloRideRoot(
                deepLinkRoute = pendingDeepLinkRoute,
                onDeepLinkHandled = { pendingDeepLinkRoute = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNotificationRoute(intent)?.let { route ->
            pendingDeepLinkRoute = route
        }
    }

    private fun extractNotificationRoute(launchIntent: Intent?): String? {
        val raw = launchIntent?.getStringExtra(TripStatusNotificationHelper.EXTRA_TARGET_ROUTE)
            ?.trim()?.lowercase()
            ?: return null
        return when (raw) {
            ROUTE_HOME, ROUTE_ACTIVITY, ROUTE_TRIP_HISTORY -> raw
            else -> ROUTE_ACTIVITY
        }
    }
}

private fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val auth = Firebase.auth
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
}

@Composable
fun VeloRideRoot(
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val currentUser by remember { authStateFlow() }.collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
    var selectedLanguage by remember { mutableStateOf(AppLanguage.DEFAULT) }
    var chosenRegistrationRole by remember { mutableStateOf(AccountMode.RIDER) }

    VeloRideTheme {
        CompositionLocalProvider(LocalLayoutDirection provides selectedLanguage.layoutDirection) {
            if (currentUser == null) {
                AuthScreen(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { selectedLanguage = it },
                    initialRegistrationRole = chosenRegistrationRole,
                    onRoleChoiceChanged = { chosenRegistrationRole = it },
                    onAuthSuccess = {}
                )
            } else {
                key(currentUser!!.uid) {
                    AuthenticatedVeloRideShell(
                        firebaseUser = currentUser!!,
                        selectedLanguage = selectedLanguage,
                        onLanguageChanged = { selectedLanguage = it },
                        chosenRegistrationRole = chosenRegistrationRole,
                        deepLinkRoute = deepLinkRoute,
                        onDeepLinkHandled = onDeepLinkHandled
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedVeloRideShell(
    firebaseUser: FirebaseUser,
    selectedLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    chosenRegistrationRole: AccountMode,
    deepLinkRoute: String?,
    onDeepLinkHandled: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val uiContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(uiContext) }

    val veloViewModel: VeloRideViewModel = viewModel(
        key = "velo_${firebaseUser.uid}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VeloRideViewModel(
                    currentUserId = firebaseUser.uid,
                    userRepository = UserRepository(context),
                    driverRepository = DriverRepository(context),
                    dispatchRepository = DispatchRepository(context),
                    pricingRepository = PricingAndAdminRepository(context),
                    mappingProvider = OsrmMappingProvider(context),
                    paymentGateway = StripePaymentGateway(),
                    alertPlayer = RideAlertPlayer(context),
                    fcmTokenRepository = FcmTokenRepository(context),
                    fcmPushCoordinator = FcmPushCoordinator(context),
                    appContext = context
                ) as T
            }
        }
    )

    val aiViewModel: VeloAiViewModel = viewModel(key = "ai_${firebaseUser.uid}")

    val accountLoaded by veloViewModel.accountLoaded.collectAsStateWithLifecycle()
    val userAccount by veloViewModel.userAccount.collectAsStateWithLifecycle()
    val isAdmin by veloViewModel.isAdmin.collectAsStateWithLifecycle()
    val isBusy by veloViewModel.isBusy.collectAsStateWithLifecycle()
    val bannerMessage by veloViewModel.bannerMessage.collectAsStateWithLifecycle()

    LaunchedEffect(userAccount?.preferredLanguage) {
        userAccount?.preferredLanguage?.let { code ->
            val accountLang = AppLanguage.fromCode(code)
            if (accountLang != selectedLanguage) {
                onLanguageChanged(accountLang)
            }
        }
    }

    if (!accountLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.testTag("account_loading_spinner"))
        }
        return
    }

    val account = userAccount
    if (account == null) {
        CompleteRegistrationScreen(
            language = selectedLanguage,
            defaultName = firebaseUser.displayName.orEmpty(),
            defaultEmail = firebaseUser.email.orEmpty(),
            chosenRole = chosenRegistrationRole,
            isSaving = isBusy,
            onSubmit = { fullName, mobile, countryCode ->
                veloViewModel.completeInitialRegistration(
                    fullName = fullName,
                    mobileNumber = mobile,
                    countryCode = countryCode,
                    language = selectedLanguage,
                    wantsDriverOnboarding = chosenRegistrationRole == AccountMode.DRIVER
                )
            }
        )
        return
    }

    if (account.isSuspended) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Account Suspended", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text("Contact VeloRide support for account review.")
            }
        }
        return
    }

    val strings = remember(selectedLanguage) { StringsProvider.get(selectedLanguage) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_HOME
    val snackbarHostState = remember { SnackbarHostState() }

    val bookingState by veloViewModel.bookingState.collectAsStateWithLifecycle()
    val onlineDrivers by veloViewModel.onlineDrivers.collectAsStateWithLifecycle()
    val assignedDriverLocation by veloViewModel.assignedDriverLocation.collectAsStateWithLifecycle()
    val savedLocations by veloViewModel.savedLocations.collectAsStateWithLifecycle()
    val paymentMethods by veloViewModel.paymentMethods.collectAsStateWithLifecycle()
    val riderRides by veloViewModel.riderRides.collectAsStateWithLifecycle()
    val driverRides by veloViewModel.driverRides.collectAsStateWithLifecycle()
    val riderTripHistoryState by veloViewModel.riderTripHistoryState.collectAsStateWithLifecycle()
    val driverTripHistoryState by veloViewModel.driverTripHistoryState.collectAsStateWithLifecycle()
    val pendingOffers by veloViewModel.activeDriverOffers.collectAsStateWithLifecycle()
    val driverProfile by veloViewModel.driverProfile.collectAsStateWithLifecycle()
    val driverDocuments by veloViewModel.driverDocuments.collectAsStateWithLifecycle()
    val ownAvailability by veloViewModel.ownDriverAvailability.collectAsStateWithLifecycle()
    val driverEarnings by veloViewModel.driverEarnings.collectAsStateWithLifecycle()
    val driverPayouts by veloViewModel.driverPayouts.collectAsStateWithLifecycle()
    val riderPayments by veloViewModel.riderPayments.collectAsStateWithLifecycle()
    val notifications by veloViewModel.notifications.collectAsStateWithLifecycle()
    val pricingPolicies by veloViewModel.pricingPolicies.collectAsStateWithLifecycle()
    val operationalConfig by veloViewModel.operationalConfig.collectAsStateWithLifecycle()
    val nowMs by veloViewModel.nowMs.collectAsStateWithLifecycle()
    val engineTelemetry by veloViewModel.engineTelemetry.collectAsStateWithLifecycle()
    val aiSelectedMode by aiViewModel.selectedMode.collectAsStateWithLifecycle()
    val aiQueryState by aiViewModel.queryState.collectAsStateWithLifecycle()
    val aiVoiceState by aiViewModel.voiceState.collectAsStateWithLifecycle()
    val vmDeepLinkRoute by veloViewModel.pendingNavigationRoute.collectAsStateWithLifecycle()
    val ratingsGiven by veloViewModel.ratingsGiven.collectAsStateWithLifecycle()
    val pendingRatingPrompt by veloViewModel.pendingRatingPrompt.collectAsStateWithLifecycle()

    LaunchedEffect(bannerMessage) {
        bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            veloViewModel.clearBanner()
        }
    }

    LaunchedEffect(deepLinkRoute, vmDeepLinkRoute) {
        val target = deepLinkRoute ?: vmDeepLinkRoute
        if (target != null && target in setOf(ROUTE_HOME, ROUTE_ACTIVITY, ROUTE_TRIP_HISTORY)) {
            navController.navigate(target) { launchSingleTop = true }
            if (deepLinkRoute != null) onDeepLinkHandled()
            if (vmDeepLinkRoute != null) veloViewModel.consumePendingNavigationRoute()
        }
    }

    val isDriverMode = account.activeMode == AccountMode.DRIVER.name
    val activeRide = riderRides.firstOrNull { ride ->
        val st = RideStatus.entries.firstOrNull { it.name == ride.status }
        st != null && !st.isTerminal || ride.status == RideStatus.NO_DRIVER_AVAILABLE.name
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isWideScreen) {
                NavigationRail(modifier = Modifier.fillMaxHeight().navigationBarsPadding()) {
                    NavigationRailItem(
                        selected = currentRoute == ROUTE_HOME,
                        onClick = { navController.navigate(ROUTE_HOME) { launchSingleTop = true } },
                        icon = { Icon(if (isDriverMode) Icons.Default.DirectionsCar else Icons.Default.Home, contentDescription = strings.navHome) },
                        label = { Text(strings.navHome) }
                    )
                    NavigationRailItem(
                        selected = currentRoute == ROUTE_ACTIVITY,
                        onClick = { navController.navigate(ROUTE_ACTIVITY) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.History, contentDescription = strings.navActivity) },
                        label = { Text(strings.navActivity) }
                    )
                    NavigationRailItem(
                        selected = currentRoute == ROUTE_AI_ASSISTANT,
                        onClick = { navController.navigate(ROUTE_AI_ASSISTANT) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = strings.navAiCopilot) },
                        label = { Text(strings.navAiCopilot) }
                    )
                    NavigationRailItem(
                        selected = currentRoute == ROUTE_PROFILE,
                        onClick = { navController.navigate(ROUTE_PROFILE) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Person, contentDescription = strings.navProfile) },
                        label = { Text(strings.navProfile) }
                    )
                    if (isAdmin) {
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_ADMIN,
                            onClick = { navController.navigate(ROUTE_ADMIN) { launchSingleTop = true } },
                            icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = strings.navAdmin) },
                            label = { Text(strings.navAdmin) }
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(strings.appTitle, fontWeight = FontWeight.ExtraBold)
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (isDriverMode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = if (isDriverMode) strings.driverMode else strings.riderMode,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    )
                },
                bottomBar = {
                    if (!isWideScreen) {
                        NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_HOME,
                                onClick = { navController.navigate(ROUTE_HOME) { launchSingleTop = true } },
                                icon = { Icon(if (isDriverMode) Icons.Default.DirectionsCar else Icons.Default.Home, contentDescription = strings.navHome) },
                                label = { Text(strings.navHome) },
                                modifier = Modifier.testTag("nav_home")
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_ACTIVITY,
                                onClick = { navController.navigate(ROUTE_ACTIVITY) { launchSingleTop = true } },
                                icon = { Icon(Icons.Default.History, contentDescription = strings.navActivity) },
                                label = { Text(strings.navActivity) },
                                modifier = Modifier.testTag("nav_activity")
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_AI_ASSISTANT,
                                onClick = { navController.navigate(ROUTE_AI_ASSISTANT) { launchSingleTop = true } },
                                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = strings.navAiCopilot) },
                                label = { Text(strings.navAiCopilot) },
                                modifier = Modifier.testTag("nav_ai_copilot")
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_PROFILE,
                                onClick = { navController.navigate(ROUTE_PROFILE) { launchSingleTop = true } },
                                icon = { Icon(Icons.Default.Person, contentDescription = strings.navProfile) },
                                label = { Text(strings.navProfile) },
                                modifier = Modifier.testTag("nav_profile")
                            )
                            if (isAdmin) {
                                NavigationBarItem(
                                    selected = currentRoute == ROUTE_ADMIN,
                                    onClick = { navController.navigate(ROUTE_ADMIN) { launchSingleTop = true } },
                                    icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = strings.navAdmin) },
                                    label = { Text(strings.navAdmin) },
                                    modifier = Modifier.testTag("nav_admin")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable(ROUTE_HOME) {
                        if (isDriverMode) {
                            DriverWorkspaceScreen(
                                language = selectedLanguage,
                                account = account,
                                driverProfile = driverProfile,
                                documents = driverDocuments,
                                availability = ownAvailability,
                                pendingOffers = pendingOffers,
                                driverRides = driverRides,
                                driverHistoryState = driverTripHistoryState,
                                payments = riderPayments,
                                earnings = driverEarnings,
                                payouts = driverPayouts,
                                nowMs = nowMs,
                                currentGpsPoint = bookingState.pickupPoint,
                                viewModel = veloViewModel
                            )
                        } else {
                            RiderHomeScreen(
                                language = selectedLanguage,
                                bookingState = bookingState,
                                onlineDrivers = onlineDrivers,
                                assignedDriverLocation = assignedDriverLocation,
                                savedLocations = savedLocations,
                                activeRide = activeRide,
                                viewModel = veloViewModel,
                                onOpenActiveActivity = { navController.navigate(ROUTE_ACTIVITY) },
                                onOpenAiCopilot = { navController.navigate(ROUTE_AI_ASSISTANT) { launchSingleTop = true } }
                            )
                        }
                    }

                    composable(ROUTE_ACTIVITY) {
                        BackHandler { navController.popBackStack() }
                        RideActivityScreen(
                            language = selectedLanguage,
                            rides = if (isDriverMode) driverRides else riderRides,
                            payments = riderPayments,
                            notifications = notifications,
                            assignedDriverLocation = assignedDriverLocation,
                            stripeStatus = veloViewModel.stripeStatus,
                            viewModel = veloViewModel,
                            onOpenTripHistory = {
                                navController.navigate(ROUTE_TRIP_HISTORY) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(ROUTE_TRIP_HISTORY) {
                        BackHandler { navController.popBackStack() }
                        TripHistoryScreen(
                            language = selectedLanguage,
                            initialRole = if (isDriverMode) TripHistoryRole.DRIVER else TripHistoryRole.RIDER,
                            showRoleSwitcher = account.hasDriverProfile || driverProfile != null,
                            riderHistoryState = riderTripHistoryState,
                            driverHistoryState = driverTripHistoryState,
                            payments = riderPayments,
                            ratingsGiven = ratingsGiven,
                            onRateTrip = { ride, role ->
                                veloViewModel.openRatingPrompt(
                                    ride = ride,
                                    raterRole = if (role == TripHistoryRole.DRIVER) "DRIVER" else "RIDER"
                                )
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(ROUTE_AI_ASSISTANT) {
                        BackHandler { navController.popBackStack() }
                        VeloAiScreen(
                            language = selectedLanguage,
                            cityName = bookingState.detectedMarket.city,
                            pickupPoint = bookingState.pickupPoint,
                            selectedMode = aiSelectedMode,
                            queryState = aiQueryState,
                            voiceState = aiVoiceState,
                            aiViewModel = aiViewModel,
                            onApplyPlaceAsDestinationQuery = { placeTitle ->
                                veloViewModel.searchDestination(placeTitle)
                                navController.navigate(ROUTE_HOME) { launchSingleTop = true }
                            }
                        )
                    }

                    composable(ROUTE_PROFILE) {
                        BackHandler { navController.popBackStack() }
                        ProfileScreen(
                            language = selectedLanguage,
                            account = account,
                            driverProfile = driverProfile,
                            savedLocations = savedLocations,
                            paymentMethods = paymentMethods,
                            viewModel = veloViewModel,
                            onLanguageSelected = onLanguageChanged,
                            onNeedDriverOnboarding = { navController.navigate(ROUTE_DRIVER_ONBOARDING) },
                            onOpenTripHistory = {
                                navController.navigate(ROUTE_TRIP_HISTORY) { launchSingleTop = true }
                            },
                            onSignOut = {
                                GoogleAuthHelper.signOut(credentialManager, scope)
                            }
                        )
                    }

                    composable(ROUTE_DRIVER_ONBOARDING) {
                        BackHandler { navController.popBackStack() }
                        DriverWorkspaceScreen(
                            language = selectedLanguage,
                            account = account,
                            driverProfile = driverProfile,
                            documents = driverDocuments,
                            availability = ownAvailability,
                            pendingOffers = pendingOffers,
                            driverRides = driverRides,
                            driverHistoryState = driverTripHistoryState,
                            payments = riderPayments,
                            earnings = driverEarnings,
                            payouts = driverPayouts,
                            nowMs = nowMs,
                            currentGpsPoint = bookingState.pickupPoint,
                            viewModel = veloViewModel
                        )
                    }

                    if (isAdmin) {
                        composable(ROUTE_ADMIN) {
                            BackHandler { navController.popBackStack() }
                            val adminVm: AdminViewModel = viewModel(
                                key = "admin_${firebaseUser.uid}",
                                factory = object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                        return AdminViewModel(PricingAndAdminRepository(context)) as T
                                    }
                                }
                            )
                            val adminMsg by adminVm.adminMessage.collectAsStateWithLifecycle()
                            LaunchedEffect(adminMsg) {
                                adminMsg?.let { msg ->
                                    snackbarHostState.showSnackbar(msg)
                                    adminVm.clearMessage()
                                }
                            }
                            val users by adminVm.users.collectAsStateWithLifecycle()
                            val drivers by adminVm.drivers.collectAsStateWithLifecycle()
                            val docs by adminVm.documents.collectAsStateWithLifecycle()
                            val rides by adminVm.rides.collectAsStateWithLifecycle()
                            val payments by adminVm.payments.collectAsStateWithLifecycle()
                            val earnings by adminVm.earnings.collectAsStateWithLifecycle()
                            val complaints by adminVm.complaints.collectAsStateWithLifecycle()
                            val logs by adminVm.auditLogs.collectAsStateWithLifecycle()

                            AdminDashboardScreen(
                                language = selectedLanguage,
                                users = users,
                                drivers = drivers,
                                documents = docs,
                                rides = rides,
                                payments = payments,
                                earnings = earnings,
                                complaints = complaints,
                                auditLogs = logs,
                                pricingPolicies = pricingPolicies,
                                operationalConfig = operationalConfig,
                                adminViewModel = adminVm,
                                engineTelemetry = engineTelemetry,
                                onRun5000RiderBenchmark = { veloViewModel.runAdmin5000RiderScaleBenchmark() },
                                onRunRaceGuardCheck = { veloViewModel.runRaceConditionVerification() },
                                onConnectWebSocket = { url -> veloViewModel.connectLocationWebSocket(url) },
                                onRefreshEngineTelemetry = { veloViewModel.refreshEngineTelemetry() }
                            )
                        }
                    }
                }
            }
        }

        pendingRatingPrompt?.let { prompt ->
            PostTripRatingDialog(
                language = selectedLanguage,
                ride = prompt.ride,
                raterRole = prompt.raterRole,
                counterpartyName = prompt.counterpartyName,
                isSubmitting = isBusy,
                onDismiss = { veloViewModel.dismissRatingPrompt(prompt) },
                onSubmit = { stars, comment, tags ->
                    veloViewModel.rateTrip(
                        ride = prompt.ride,
                        toUserId = prompt.toUserId,
                        fromRole = prompt.raterRole,
                        stars = stars,
                        comment = comment,
                        tags = tags
                    )
                }
            )
        }
    }
}
