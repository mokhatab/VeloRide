package com.example.ui.main

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.core.alerts.OfferNotificationAction
import com.example.core.alerts.RideAlertPlayer
import com.example.core.alerts.RideOfferActionBus
import com.example.core.push.FcmPushCoordinator
import com.example.core.push.FcmSyncResult
import com.example.core.push.TripStatusNotificationHelper
import com.example.core.engine.CacheStats
import com.example.core.engine.ConditionalEngineStats
import com.example.core.engine.ConditionalUpdateEngine
import com.example.core.engine.ConditionalUpdateResult
import com.example.core.engine.H3Core
import com.example.core.engine.H3SpatialIndex
import com.example.core.engine.MessagePriority
import com.example.core.engine.MessageQueueStats
import com.example.core.engine.RideStateMachine
import com.example.core.engine.RiderSyncEngine
import com.example.core.engine.RiderSyncSnapshot
import com.example.core.engine.StateTransitionRecord
import com.example.core.engine.SyncedRiderRecord
import com.example.core.engine.VeloInMemoryStore
import com.example.core.engine.VeloMessageQueue
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.core.i18n.StringsProvider
import com.example.core.network.LocationUpdatePacket
import com.example.core.network.LocationWebSocketClient
import com.example.core.network.WebSocketTelemetry
import com.example.data.firestore.DispatchRepository
import com.example.data.firestore.DriverRepository
import com.example.data.firestore.FcmTokenRepository
import com.example.data.firestore.PricingAndAdminRepository
import com.example.data.firestore.UserRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import com.example.domain.mapping.GeoPoint
import com.example.domain.mapping.MappingProvider
import com.example.domain.mapping.PlaceSearchResult
import com.example.domain.mapping.ReverseGeocodeResult
import com.example.domain.mapping.RouteResult
import com.example.domain.model.AccountMode
import com.example.domain.model.AppNotification
import com.example.domain.model.DriverApprovalStatus
import com.example.domain.model.DriverAvailability
import com.example.domain.model.DriverDocument
import com.example.domain.model.DriverDocumentType
import com.example.domain.model.DriverEarning
import com.example.domain.model.DriverOnlineStatus
import com.example.domain.model.DriverPayout
import com.example.domain.model.DriverProfile
import com.example.domain.model.FareQuote
import com.example.domain.model.FcmPushEvent
import com.example.domain.model.FcmPushEventBus
import com.example.domain.model.FcmPushEventType
import com.example.domain.model.OperationalConfig
import com.example.domain.model.PaymentMethodRecord
import com.example.domain.model.PaymentRecord
import com.example.domain.model.PricingPolicy
import com.example.domain.model.ProfileRatingSummary
import com.example.domain.model.RatingAggregator
import com.example.domain.model.RatingRecord
import com.example.domain.model.Ride
import com.example.domain.model.RideOffer
import com.example.domain.model.RideStatus
import com.example.domain.model.RiderPaymentChoice
import com.example.domain.model.SavedLocation
import com.example.domain.model.StripeAutoConfig
import com.example.domain.model.UserAccount
import com.example.domain.model.UserFcmToken
import com.example.domain.model.VehicleCategory
import com.example.domain.payment.PaymentGateway
import com.example.domain.payment.StripeConfigurationStatus
import com.example.domain.pricing.FareCalculationInput
import com.example.domain.pricing.MarketCatalog
import com.example.domain.pricing.PricingEngine
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RiderBookingState(
    val pickupPoint: GeoPoint = GeoPoint(30.0444, 31.2357),
    val pickupAddress: String = "Tahrir Square, Cairo",
    val destinationPoint: GeoPoint = GeoPoint(30.1128, 31.3999),
    val destinationAddress: String = "Cairo International Airport",
    val detectedMarket: ReverseGeocodeResult = ReverseGeocodeResult(
        address = "Tahrir Square, Cairo",
        countryCode = "EG",
        countryName = "Egypt",
        city = "Cairo",
        currencyCode = "EGP",
        currencySymbol = "EGP",
        currencyDecimals = 2
    ),
    val selectedCategory: VehicleCategory = VehicleCategory.ECONOMY,
    val selectedPaymentChoice: RiderPaymentChoice = RiderPaymentChoice.E_WALLET,
    val stripeAutoConfig: StripeAutoConfig = StripeAutoConfig(),
    val routeResult: RouteResult? = null,
    val currentQuote: FareQuote? = null,
    val taxiReferenceFareMinor: Long? = null,
    val searchResults: List<PlaceSearchResult> = emptyList(),
    val isCalculatingRoute: Boolean = false,
    val isDispatching: Boolean = false
)

data class EngineTelemetrySnapshot(
    val riderSync: RiderSyncSnapshot = RiderSyncSnapshot(),
    val webSocket: WebSocketTelemetry = WebSocketTelemetry(),
    val cacheStats: List<CacheStats> = emptyList(),
    val pickupH3CellHex: String = "",
    val nearbyH3DriversCount: Int = 0,
    val nearbyH3RidersCount: Int = 0,
    val h3SearchLatencyMicros: Long = 0L,
    val conditionalUpdates: ConditionalEngineStats = ConditionalEngineStats(0, 0, 0, 0),
    val queueStats: MessageQueueStats = MessageQueueStats(),
    val recentStateTransitions: List<StateTransitionRecord> = emptyList()
)

sealed interface TripHistoryUiState {
    data object Loading : TripHistoryUiState
    data class Success(val rides: List<Ride>) : TripHistoryUiState
    data class Error(val message: String) : TripHistoryUiState
}

data class PendingRatingPrompt(
    val ride: Ride,
    val raterRole: String,
    val toUserId: String,
    val counterpartyName: String
)

class VeloRideViewModel(
    private val currentUserId: String,
    private val userRepository: UserRepository,
    private val driverRepository: DriverRepository,
    private val dispatchRepository: DispatchRepository,
    private val pricingRepository: PricingAndAdminRepository,
    private val mappingProvider: MappingProvider,
    private val paymentGateway: PaymentGateway,
    private val alertPlayer: RideAlertPlayer?,
    private val fcmTokenRepository: FcmTokenRepository? = null,
    private val fcmPushCoordinator: FcmPushCoordinator? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val _accountLoaded = MutableStateFlow(false)
    val accountLoaded: StateFlow<Boolean> = _accountLoaded.asStateFlow()

    private val _userAccount = MutableStateFlow<UserAccount?>(null)
    val userAccount: StateFlow<UserAccount?> = _userAccount.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _bookingState = MutableStateFlow(RiderBookingState())
    val bookingState: StateFlow<RiderBookingState> = _bookingState.asStateFlow()

    private val _nowMs = MutableStateFlow(System.currentTimeMillis())
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    val stripeStatus: StripeConfigurationStatus = paymentGateway.configurationStatus()

    val driverProfile: StateFlow<DriverProfile?> = driverRepository.observeDriverProfile(currentUserId)
        .catch { handleStreamError("driverProfile", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val driverDocuments: StateFlow<List<DriverDocument>> = driverRepository.observeDriverDocuments(currentUserId)
        .catch { handleStreamError("driverDocuments", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val ownDriverAvailability: StateFlow<DriverAvailability?> = driverRepository.observeOwnAvailability(currentUserId)
        .catch { handleStreamError("ownAvailability", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val onlineDrivers: StateFlow<List<DriverAvailability>> = driverRepository.observeOnlineApprovedDrivers()
        .catch { handleStreamError("onlineDrivers", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val riderRides: StateFlow<List<Ride>> = dispatchRepository.observeRiderRides(currentUserId)
        .catch { handleStreamError("riderRides", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val driverRides: StateFlow<List<Ride>> = dispatchRepository.observeDriverRides(currentUserId)
        .catch { handleStreamError("driverRides", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val riderTripHistoryState: StateFlow<TripHistoryUiState> = dispatchRepository.observeRiderTripHistory(currentUserId)
        .map<List<Ride>, TripHistoryUiState> { TripHistoryUiState.Success(it) }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            handleStreamError("riderTripHistory", error)
            emit(TripHistoryUiState.Error((fe ?: error).localizedMessage ?: "Failed to load rider trip history"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), TripHistoryUiState.Loading)

    val driverTripHistoryState: StateFlow<TripHistoryUiState> = dispatchRepository.observeDriverTripHistory(currentUserId)
        .map<List<Ride>, TripHistoryUiState> { TripHistoryUiState.Success(it) }
        .catch { error ->
            val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
            if (fe == null && error is CancellationException) throw error
            handleStreamError("driverTripHistory", error)
            emit(TripHistoryUiState.Error((fe ?: error).localizedMessage ?: "Failed to load driver trip history"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), TripHistoryUiState.Loading)

    val pendingDriverOffers: StateFlow<List<RideOffer>> = dispatchRepository.observePendingDriverOffers(currentUserId)
        .catch { handleStreamError("pendingOffers", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val ratingsReceived: StateFlow<List<RatingRecord>> = dispatchRepository.observeRatingsReceived(currentUserId)
        .catch { handleStreamError("ratingsReceived", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val ratingsGiven: StateFlow<List<RatingRecord>> = dispatchRepository.observeRatingsGiven(currentUserId)
        .catch { handleStreamError("ratingsGiven", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val riderRatingSummary: StateFlow<ProfileRatingSummary> = combine(
        ratingsReceived,
        _userAccount
    ) { received, account ->
        RatingAggregator.summarize(
            ratings = received.filter { it.fromRole.equals("DRIVER", ignoreCase = true) },
            fallbackAvg = account?.riderRatingAvg ?: 5.0,
            fallbackCount = account?.riderRatingCount?.toInt() ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ProfileRatingSummary())

    val driverRatingSummary: StateFlow<ProfileRatingSummary> = combine(
        ratingsReceived,
        driverProfile
    ) { received, profile ->
        RatingAggregator.summarize(
            ratings = received.filter { it.fromRole.equals("RIDER", ignoreCase = true) },
            fallbackAvg = profile?.driverRatingAvg ?: 5.0,
            fallbackCount = profile?.driverRatingCount?.toInt() ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ProfileRatingSummary())

    private val _dismissedRatingKeys = MutableStateFlow<Set<String>>(emptySet())
    private val _manualRatingPrompt = MutableStateFlow<PendingRatingPrompt?>(null)
    private val _autoCompletedPromptRide = MutableStateFlow<PendingRatingPrompt?>(null)
    private val seenCompletedRideKeys = ConcurrentHashMap.newKeySet<String>()
    private var initialRiderRidesSeeded = false
    private var initialDriverRidesSeeded = false

    val pendingRatingPrompt: StateFlow<PendingRatingPrompt?> = combine(
        _manualRatingPrompt,
        _autoCompletedPromptRide,
        ratingsGiven,
        _dismissedRatingKeys
    ) { manual, autoPrompt, given, dismissed ->
        if (manual != null) {
            manual
        } else if (autoPrompt != null) {
            val promptKey = "${autoPrompt.ride.id}_${autoPrompt.raterRole}"
            val alreadyGiven = given.any { it.rideId == autoPrompt.ride.id && it.fromRole.equals(autoPrompt.raterRole, ignoreCase = true) }
            if (promptKey in dismissed || alreadyGiven) null else autoPrompt
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val pricingPolicies: StateFlow<List<PricingPolicy>> = pricingRepository.observePricingPolicies()
        .catch { handleStreamError("pricingPolicies", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), MarketCatalog.defaultPoliciesForAllMarkets())

    val operationalConfig: StateFlow<OperationalConfig> = pricingRepository.observeOperationalConfig()
        .catch { handleStreamError("operationalConfig", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OperationalConfig())

    val savedLocations: StateFlow<List<SavedLocation>> = userRepository.observeSavedLocations(currentUserId)
        .catch { handleStreamError("savedLocations", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val paymentMethods: StateFlow<List<PaymentMethodRecord>> = userRepository.observePaymentMethods(currentUserId)
        .catch { handleStreamError("paymentMethods", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val notifications: StateFlow<List<AppNotification>> = pricingRepository.observeNotifications(currentUserId)
        .catch { handleStreamError("notifications", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val fcmTokens: StateFlow<List<UserFcmToken>> = (
        fcmTokenRepository?.observeUserFcmTokens(currentUserId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        )
        .catch { handleStreamError("fcmTokens", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _fcmSyncState = MutableStateFlow(FcmSyncResult())
    val fcmSyncState: StateFlow<FcmSyncResult> = _fcmSyncState.asStateFlow()

    private val _pushNotificationsEnabled = MutableStateFlow(true)
    val pushNotificationsEnabled: StateFlow<Boolean> = _pushNotificationsEnabled.asStateFlow()

    private val _pendingNavigationRoute = MutableStateFlow<String?>(null)
    val pendingNavigationRoute: StateFlow<String?> = _pendingNavigationRoute.asStateFlow()

    val driverEarnings: StateFlow<List<DriverEarning>> = pricingRepository.observeDriverEarnings(currentUserId)
        .catch { handleStreamError("driverEarnings", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val driverPayouts: StateFlow<List<DriverPayout>> = pricingRepository.observeDriverPayouts(currentUserId)
        .catch { handleStreamError("driverPayouts", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val riderPayments: StateFlow<List<PaymentRecord>> = pricingRepository.observeRiderPayments(currentUserId)
        .catch { handleStreamError("riderPayments", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _revokedOfferIds = MutableStateFlow<Set<String>>(emptySet())
    val revokedOfferIds: StateFlow<Set<String>> = _revokedOfferIds.asStateFlow()

    val activeDriverOffers: StateFlow<List<RideOffer>> = combine(
        pendingDriverOffers,
        _revokedOfferIds,
        _nowMs
    ) { offers, revokedIds, now ->
        offers.filter { offer ->
            offer.id !in revokedIds && now <= offer.expiresAtMs
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _assignedDriverLocation = MutableStateFlow<DriverAvailability?>(null)
    val assignedDriverLocation: StateFlow<DriverAvailability?> = _assignedDriverLocation.asStateFlow()

    // Core scalability & real-time engine modules
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val locationPacketAdapter = moshi.adapter(LocationUpdatePacket::class.java)
    val messageQueue = VeloMessageQueue()
    val riderSyncEngine = RiderSyncEngine()
    val driverSpatialIndex = H3SpatialIndex<DriverAvailability>()
    val locationWebSocketClient = LocationWebSocketClient(moshi = moshi, scope = viewModelScope)

    private val _engineTelemetry = MutableStateFlow(EngineTelemetrySnapshot())
    val engineTelemetry: StateFlow<EngineTelemetrySnapshot> = _engineTelemetry.asStateFlow()

    private var dispatchEscalationJob: Job? = null
    private var assignedDriverObserverJob: Job? = null
    private var observedAssignedDriverId: String? = null
    private val offerRideWatchJobs = ConcurrentHashMap<String, Job>()
    private val initiatedAutoPaymentRides = ConcurrentHashMap.newKeySet<String>()
    private val seenNotificationKeys = ConcurrentHashMap.newKeySet<String>()
    private var initialNotificationsSeeded = false

    init {
        observeAccount()
        checkAdminStatus()
        startClockTicker()
        observeDriverOfferAlerts()
        observeNotificationActionBus()
        observeAssignedDriverTracking()
        observeCompletedRidesForAutoStripe()
        observeNewlyCompletedRidesForRating()
        observeFcmLifecycle()
        startEnginePipelines()
        refreshStripeAutoConfig()
        recalculateQuote()
    }

    override fun onCleared() {
        super.onCleared()
        offerRideWatchJobs.values.forEach { it.cancel() }
        offerRideWatchJobs.clear()
        alertPlayer?.stopAllAlerts()
        messageQueue.stopWorker()
        locationWebSocketClient.disconnect()
    }

    private fun startEnginePipelines() {
        if (BuildConfig.WS_LOCATION_URL.isNotBlank()) {
            locationWebSocketClient.connect(BuildConfig.WS_LOCATION_URL)
        }

        // Start async priority MessageQueue worker
        messageQueue.startWorker(viewModelScope, pollIntervalMs = 150L) { msg ->
            when (msg.topic) {
                "location.update" -> {
                    val packet = locationPacketAdapter.fromJson(msg.payloadJson)
                        ?: return@startWorker Result.failure(IllegalArgumentException("Malformed location packet"))
                    locationWebSocketClient.sendLocationUpdate(packet)
                    val res = driverRepository.updateLiveLocation(
                        point = GeoPoint(packet.latitude, packet.longitude),
                        heading = packet.heading,
                        speedKmh = packet.speedKmh,
                        locationTimestampMs = packet.timestampMs,
                        activeRideId = packet.rideId
                    )
                    if (res.isSuccess || (res.exceptionOrNull()?.message?.contains("Availability record missing") == true)) {
                        Result.success(Unit)
                    } else {
                        res
                    }
                }
                "rider.sync.batch", "ride.state.event" -> {
                    Result.success(Unit)
                }
                else -> Result.success(Unit)
            }
        }

        // Index online approved drivers into H3 + LRU cache
        viewModelScope.launch {
            onlineDrivers.collect { drivers ->
                VeloInMemoryStore.driverLocations.putAll(drivers.associateBy { it.driverId })
                driverSpatialIndex.batchUpsert(
                    drivers.map { d -> Triple(d.driverId, GeoPoint(d.latitude, d.longitude), d) }
                )
                refreshEngineTelemetry()
            }
        }

        // Hydrate real Firestore rider rides into RiderSyncEngine + Cache + H3
        viewModelScope.launch {
            riderRides.collect { rides ->
                if (rides.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val records = rides.map { r ->
                        SyncedRiderRecord(
                            riderId = r.id,
                            displayName = r.riderName,
                            latitude = r.pickupLat,
                            longitude = r.pickupLng,
                            h3Cell = H3Core.latLngToCell(r.pickupLat, r.pickupLng),
                            city = r.city,
                            status = r.status,
                            version = (riderSyncEngine.conditionalEngine.get(r.id)?.version ?: 0L) + 1L,
                            updatedAtMs = now
                        )
                    }
                    riderSyncEngine.syncRidersInChunks(records, queue = messageQueue, isSimulation = false)
                }
                refreshEngineTelemetry()
            }
        }

        // Process inbound WebSocket location packets with H3 & Cache updates
        viewModelScope.launch {
            locationWebSocketClient.inboundLocations.collect { packet ->
                if (packet.role.equals("DRIVER", ignoreCase = true)) {
                    val existing = VeloInMemoryStore.driverLocations.get(packet.entityId)
                    val updated = (existing ?: DriverAvailability(driverId = packet.entityId)).copy(
                        latitude = packet.latitude,
                        longitude = packet.longitude,
                        heading = packet.heading,
                        locationTimestampMs = packet.timestampMs,
                        activeRideId = packet.rideId
                    )
                    VeloInMemoryStore.driverLocations.put(packet.entityId, updated)
                    driverSpatialIndex.upsert(packet.entityId, packet.latitude, packet.longitude, updated)
                    if (packet.entityId == observedAssignedDriverId) {
                        _assignedDriverLocation.value = updated
                    }
                } else {
                    val cell = H3Core.latLngToCell(packet.latitude, packet.longitude)
                    val riderRec = SyncedRiderRecord(
                        riderId = packet.entityId,
                        displayName = "Rider ${packet.entityId.take(6)}",
                        latitude = packet.latitude,
                        longitude = packet.longitude,
                        h3Cell = cell,
                        city = _bookingState.value.detectedMarket.city,
                        status = "LIVE_WS",
                        version = packet.sequence.coerceAtLeast(1L),
                        updatedAtMs = packet.timestampMs
                    )
                    riderSyncEngine.ingestChunk(listOf(riderRec), 0, 1, isSimulation = false)
                }
                refreshEngineTelemetry()
            }
        }
    }

    fun refreshEngineTelemetry(searchRadiusKm: Double = 5.0) {
        val pickup = _bookingState.value.pickupPoint
        val driverSearch = driverSpatialIndex.searchNearby(pickup, searchRadiusKm, limit = 50)
        val riderSearch = riderSyncEngine.spatialIndex.searchNearby(pickup, searchRadiusKm, limit = 100)

        val syncCas = riderSyncEngine.conditionalEngine.stats()
        val rideCas = dispatchRepository.conditionalStats()
        val locCas = driverRepository.locationCasStats()
        val combinedCas = ConditionalEngineStats(
            trackedKeys = syncCas.trackedKeys + rideCas.trackedKeys + locCas.trackedKeys,
            successfulUpdates = syncCas.successfulUpdates + rideCas.successfulUpdates + locCas.successfulUpdates,
            versionConflicts = syncCas.versionConflicts + rideCas.versionConflicts + locCas.versionConflicts,
            staleTimestampRejections = syncCas.staleTimestampRejections + rideCas.staleTimestampRejections + locCas.staleTimestampRejections
        )

        _engineTelemetry.value = EngineTelemetrySnapshot(
            riderSync = riderSyncEngine.syncSnapshot.value,
            webSocket = locationWebSocketClient.telemetry.value,
            cacheStats = VeloInMemoryStore.aggregateStats(),
            pickupH3CellHex = driverSearch.originCellHex,
            nearbyH3DriversCount = driverSearch.matches.size,
            nearbyH3RidersCount = riderSearch.matches.size,
            h3SearchLatencyMicros = driverSearch.latencyMicros + riderSearch.latencyMicros,
            conditionalUpdates = combinedCas,
            queueStats = messageQueue.snapshotStats(),
            recentStateTransitions = RideStateMachine.recentTransitions().take(8)
        )
    }

    fun runAdmin5000RiderScaleBenchmark() {
        viewModelScope.launch {
            val state = _bookingState.value
            val snap = riderSyncEngine.runScaleBenchmark5000(
                centerLat = state.pickupPoint.latitude,
                centerLng = state.pickupPoint.longitude,
                city = state.detectedMarket.city,
                count = RiderSyncEngine.DEFAULT_SYNC_TARGET,
                queue = messageQueue
            )
            refreshEngineTelemetry(searchRadiusKm = 6.0)
            _bannerMessage.value = "Synced ${snap.syncedCount} riders in ${snap.chunksCompleted} chunks (${snap.lastSyncDurationMs}ms)"
        }
    }

    fun runRaceConditionVerification() {
        viewModelScope.launch {
            val cas = riderSyncEngine.conditionalEngine
            val probeKey = "race_probe_${System.currentTimeMillis()}"
            cas.compareAndSet(probeKey, 0L) { SyncedRiderRecord(probeKey, "Probe", 30.04, 31.23, 0L, "Cairo", "PROBE", 1L, System.currentTimeMillis()) }
            val results = coroutineScope {
                (1..40).map { idx ->
                    async {
                        cas.compareAndSet(probeKey, expectedVersion = 1L) { cur ->
                            (cur ?: SyncedRiderRecord(probeKey, "Probe", 30.04, 31.23, 0L, "Cairo", "PROBE", 1L, System.currentTimeMillis()))
                                .copy(status = "WON_BY_$idx", version = 2L)
                        }
                    }
                }.awaitAll()
            }
            val winners = results.count { it is ConditionalUpdateResult.Success }
            val conflicts = results.count { it is ConditionalUpdateResult.Conflict }
            refreshEngineTelemetry()
            _bannerMessage.value = "CAS Race Guard (40 concurrent threads): $winners winner, $conflicts conflicts blocked"
        }
    }

    fun connectLocationWebSocket(wsUrl: String) {
        locationWebSocketClient.connect(wsUrl)
        refreshEngineTelemetry()
    }

    private fun observeAccount() {
        viewModelScope.launch {
            userRepository.observeUserAccount(currentUserId)
                .catch { handleStreamError("userAccount", it) }
                .collect { account ->
                    _userAccount.value = account
                    _accountLoaded.value = true
                }
        }
    }

    private fun checkAdminStatus() {
        viewModelScope.launch {
            val admin = userRepository.isUserAdmin(currentUserId)
            _isAdmin.value = admin
            if (admin) {
                pricingRepository.seedDefaultPricingPoliciesIfAdmin()
            }
        }
    }

    private fun startClockTicker() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                _nowMs.value = now
                // Auto-expire driver offers whose 30-second server deadline elapsed
                pendingDriverOffers.value.forEach { offer ->
                    if (now > offer.expiresAtMs && offer.status == "PENDING") {
                        alertPlayer?.stopOfferAlert(offer.id)
                        launch { dispatchRepository.rejectOrExpireOffer(offer, expired = true) }
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun observeDriverOfferAlerts() {
        viewModelScope.launch {
            pendingDriverOffers.collect { offers ->
                // Attach sub-second ride watch per pending offer so if ANOTHER driver accepts in the same second,
                // this driver immediately revokes the offer, silences alert tone, and cancels status notification.
                val currentOfferRideIds = offers.map { it.rideId }.toSet()
                offerRideWatchJobs.keys.filter { it !in currentOfferRideIds }.forEach { staleRideId ->
                    offerRideWatchJobs.remove(staleRideId)?.cancel()
                }
                offers.forEach { offer ->
                    offerRideWatchJobs.computeIfAbsent(offer.rideId) {
                        viewModelScope.launch {
                            dispatchRepository.observeRide(offer.rideId)
                                .catch { /* ignore transient rule/network close */ }
                                .collect { liveRide ->
                                    if (liveRide != null) {
                                        val claimedByOther = liveRide.driverId.isNotBlank() && liveRide.driverId != currentUserId
                                        val noLongerSearchable = liveRide.status !in listOf(
                                            RideStatus.REQUESTED.name,
                                            RideStatus.SEARCHING_FOR_DRIVER.name,
                                            RideStatus.OFFERED.name
                                        )
                                        if (claimedByOther || (noLongerSearchable && liveRide.driverId != currentUserId)) {
                                            _revokedOfferIds.update { it + offer.id }
                                            alertPlayer?.stopOfferAlert(offer.id)
                                            _bannerMessage.value = "Ride accepted by another driver — offer revoked"
                                            launch { dispatchRepository.markOfferCancelledByOtherIfLost(offer) }
                                        }
                                    }
                                }
                        }
                    }
                }

                val activeOffer = offers.firstOrNull {
                    it.id !in _revokedOfferIds.value && _nowMs.value <= it.expiresAtMs
                }
                if (activeOffer != null && alertPlayer != null) {
                    val fareText = LocaleFormatter.formatCompactCurrency(
                        activeOffer.estimatedFareMinor,
                        activeOffer.currencySymbol,
                        activeOffer.currencyDecimals.toInt(),
                        AppLanguage.fromCode(_userAccount.value?.preferredLanguage)
                    )
                    val zoneHex = H3Core.cellToString(H3Core.latLngToCell(activeOffer.pickupLat, activeOffer.pickupLng))
                    alertPlayer.notifyNewRideOffer(
                        offerId = activeOffer.id,
                        pickup = activeOffer.pickupAddress,
                        destination = activeOffer.destinationAddress,
                        fareDisplay = fareText,
                        expiresAtMs = activeOffer.expiresAtMs,
                        zoneHex = zoneHex
                    )
                } else {
                    alertPlayer?.stopAllAlerts()
                }
            }
        }
    }

    private fun observeNotificationActionBus() {
        viewModelScope.launch {
            RideOfferActionBus.actions.collect { action ->
                val matchingOffer = pendingDriverOffers.value.firstOrNull { it.id == action.offerId }
                if (matchingOffer == null) {
                    alertPlayer?.stopOfferAlert(action.offerId)
                    return@collect
                }
                when (action) {
                    is OfferNotificationAction.Accept -> acceptRideOffer(matchingOffer)
                    is OfferNotificationAction.Reject -> rejectRideOffer(matchingOffer)
                }
            }
        }
    }

    private fun observeCompletedRidesForAutoStripe() {
        viewModelScope.launch {
            riderRides.collect { rides ->
                val newlyCompletedDigital = rides.filter { ride ->
                    ride.status == RideStatus.COMPLETED.name &&
                        ride.paymentStatus == "PENDING" &&
                        RiderPaymentChoice.fromRidePaymentMethodId(ride.paymentMethodId).usesStripe &&
                        ride.id !in initiatedAutoPaymentRides
                }
                newlyCompletedDigital.forEach { completedRide ->
                    initiatedAutoPaymentRides.add(completedRide.id)
                    initiateRidePayment(completedRide)
                }
            }
        }
    }

    private fun observeNewlyCompletedRidesForRating() {
        viewModelScope.launch {
            riderRides.collect { rides ->
                val completed = rides.filter { it.status == RideStatus.COMPLETED.name }
                if (!initialRiderRidesSeeded) {
                    if (completed.isNotEmpty() || _accountLoaded.value) {
                        completed.forEach { seenCompletedRideKeys.add("${it.id}_RIDER") }
                        initialRiderRidesSeeded = true
                    }
                    return@collect
                }
                val newCandidate = completed.firstOrNull { ride ->
                    !ride.riderRated &&
                        ride.driverId.isNotBlank() &&
                        ride.driverId != currentUserId &&
                        seenCompletedRideKeys.add("${ride.id}_RIDER") &&
                        ratingsGiven.value.none { it.rideId == ride.id && it.fromRole == "RIDER" }
                }
                if (newCandidate != null && _userAccount.value?.activeMode == AccountMode.RIDER.name) {
                    _autoCompletedPromptRide.value = PendingRatingPrompt(
                        ride = newCandidate,
                        raterRole = "RIDER",
                        toUserId = newCandidate.driverId,
                        counterpartyName = newCandidate.driverName?.takeIf { it.isNotBlank() } ?: "Driver"
                    )
                }
            }
        }

        viewModelScope.launch {
            driverRides.collect { rides ->
                val completed = rides.filter { it.status == RideStatus.COMPLETED.name }
                if (!initialDriverRidesSeeded) {
                    if (completed.isNotEmpty() || _accountLoaded.value) {
                        completed.forEach { seenCompletedRideKeys.add("${it.id}_DRIVER") }
                        initialDriverRidesSeeded = true
                    }
                    return@collect
                }
                val newCandidate = completed.firstOrNull { ride ->
                    !ride.driverRated &&
                        ride.riderId.isNotBlank() &&
                        ride.riderId != currentUserId &&
                        seenCompletedRideKeys.add("${ride.id}_DRIVER") &&
                        ratingsGiven.value.none { it.rideId == ride.id && it.fromRole == "DRIVER" }
                }
                if (newCandidate != null && _userAccount.value?.activeMode == AccountMode.DRIVER.name) {
                    _autoCompletedPromptRide.value = PendingRatingPrompt(
                        ride = newCandidate,
                        raterRole = "DRIVER",
                        toUserId = newCandidate.riderId,
                        counterpartyName = newCandidate.riderName.ifBlank { "Passenger" }
                    )
                }
            }
        }
    }

    private fun observeAssignedDriverTracking() {
        viewModelScope.launch {
            riderRides.collect { rides ->
                val activeRide = rides.firstOrNull {
                    it.status in listOf(
                        RideStatus.ACCEPTED.name,
                        RideStatus.DRIVER_ARRIVING.name,
                        RideStatus.ARRIVED.name,
                        RideStatus.IN_PROGRESS.name
                    )
                }
                val driverId = activeRide?.driverId?.takeIf { it.isNotBlank() }
                if (driverId != observedAssignedDriverId) {
                    observedAssignedDriverId = driverId
                    assignedDriverObserverJob?.cancel()
                    if (driverId != null) {
                        assignedDriverObserverJob = viewModelScope.launch {
                            driverRepository.observeAssignedDriverAvailability(driverId)
                                .catch { handleStreamError("assignedDriver", it) }
                                .collect { _assignedDriverLocation.value = it }
                        }
                    } else {
                        _assignedDriverLocation.value = null
                    }
                }
            }
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun completeInitialRegistration(
        fullName: String,
        mobileNumber: String,
        countryCode: String,
        language: AppLanguage,
        wantsDriverOnboarding: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                userRepository.registerOrEnsureUser(
                    fullName = fullName,
                    mobileNumber = mobileNumber,
                    countryCode = countryCode,
                    preferredLanguage = language.code,
                    wantsDriverOnboarding = wantsDriverOnboarding
                )
            } catch (e: Exception) {
                _bannerMessage.value = e.localizedMessage
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun updateProfile(
        fullName: String,
        mobileNumber: String,
        countryCode: String,
        language: AppLanguage,
        photoUrl: String?
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                userRepository.updateProfile(fullName, mobileNumber, countryCode, language.code, photoUrl)
                _bannerMessage.value = if (language == AppLanguage.ARABIC) "تم تحديث الملف الشخصي" else "Profile updated"
            } catch (e: Exception) {
                _bannerMessage.value = e.localizedMessage
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun changeLanguage(language: AppLanguage) {
        viewModelScope.launch {
            try {
                if (_userAccount.value != null) {
                    userRepository.updatePreferredLanguage(language.code)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun switchAccountMode(targetMode: AccountMode, onNeedDriverOnboarding: () -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = userRepository.switchAccountMode(targetMode)
            _isBusy.value = false
            result.onFailure { err ->
                if (targetMode == AccountMode.DRIVER && driverProfile.value == null) {
                    onNeedDriverOnboarding()
                } else {
                    _bannerMessage.value = err.localizedMessage
                }
            }
        }
    }

    fun setPickupPoint(point: GeoPoint) {
        if (!point.isValid()) return
        viewModelScope.launch {
            val geo = mappingProvider.reverseGeocode(point)
            _bookingState.update {
                it.copy(
                    pickupPoint = point,
                    pickupAddress = geo.address,
                    detectedMarket = geo
                )
            }
            recalculateQuote()
        }
    }

    fun setDestinationPoint(point: GeoPoint, label: String? = null) {
        if (!point.isValid()) return
        viewModelScope.launch {
            val addr = label ?: mappingProvider.reverseGeocode(point).address
            _bookingState.update {
                it.copy(destinationPoint = point, destinationAddress = addr)
            }
            recalculateQuote()
        }
    }

    fun switchMarketPreset(countryCode: String, city: String) {
        val market = MarketCatalog.marketByCountryAndCity(countryCode, city)
        val pickup = GeoPoint(market.centerLat, market.centerLng)
        val dest = GeoPoint(market.defaultDestinationLat, market.defaultDestinationLng)
        _bookingState.update {
            it.copy(
                pickupPoint = pickup,
                pickupAddress = market.defaultPickupName,
                destinationPoint = dest,
                destinationAddress = market.defaultDestinationName,
                detectedMarket = ReverseGeocodeResult(
                    address = market.defaultPickupName,
                    countryCode = market.countryCode,
                    countryName = market.countryName,
                    city = market.city,
                    currencyCode = market.currencyCode,
                    currencySymbol = market.currencySymbol,
                    currencyDecimals = market.currencyDecimals
                )
            )
        }
        recalculateQuote()
    }

    fun searchDestination(query: String) {
        viewModelScope.launch {
            val results = mappingProvider.searchPlaces(query, _bookingState.value.pickupPoint)
            _bookingState.update { it.copy(searchResults = results) }
        }
    }

    fun clearSearchResults() {
        _bookingState.update { it.copy(searchResults = emptyList()) }
    }

    fun selectVehicleCategory(category: VehicleCategory) {
        _bookingState.update { it.copy(selectedCategory = category) }
        recalculateQuote()
    }

    fun selectPaymentChoice(choice: RiderPaymentChoice) {
        val market = _bookingState.value.detectedMarket
        val autoCfg = paymentGateway.configureForRiderChoice(
            choice = choice,
            currencyCode = market.currencyCode,
            decimals = market.currencyDecimals,
            linkedMethods = paymentMethods.value
        )
        _bookingState.update {
            it.copy(selectedPaymentChoice = choice, stripeAutoConfig = autoCfg)
        }
        // If E-Wallet was selected and rider has no tokenized wallet entry yet, auto-register one for Stripe
        if (choice == RiderPaymentChoice.E_WALLET &&
            paymentMethods.value.none { it.methodType.equals("WALLET", ignoreCase = true) }
        ) {
            viewModelScope.launch {
                runCatching {
                    userRepository.addTokenizedPaymentMethod(
                        brand = "Stripe E-Wallet",
                        last4 = "8888",
                        expiryMonth = 12L,
                        expiryYear = 2029L,
                        stripeTokenId = "pm_wallet_stripe_${currentUserId.take(6)}",
                        methodType = "WALLET"
                    )
                }
            }
        }
    }

    private fun refreshStripeAutoConfig() {
        val state = _bookingState.value
        val cfg = paymentGateway.configureForRiderChoice(
            choice = state.selectedPaymentChoice,
            currencyCode = state.detectedMarket.currencyCode,
            decimals = state.detectedMarket.currencyDecimals,
            linkedMethods = paymentMethods.value
        )
        _bookingState.update { it.copy(stripeAutoConfig = cfg) }
    }

    fun recalculateQuote() {
        viewModelScope.launch {
            val state = _bookingState.value
            _bookingState.update { it.copy(isCalculatingRoute = true) }
            try {
                val routeKey = "${state.pickupPoint.latitude},${state.pickupPoint.longitude}|${state.destinationPoint.latitude},${state.destinationPoint.longitude}"
                val route = VeloInMemoryStore.routeCache.get(routeKey)
                    ?: mappingProvider.calculateRoute(state.pickupPoint, state.destinationPoint).also {
                        VeloInMemoryStore.routeCache.put(routeKey, it)
                    }
                val policies = pricingPolicies.value
                val market = state.detectedMarket
                val commercialPolicy = policies.firstOrNull {
                    !it.isTaxiReference && it.isActive &&
                        it.countryCode.equals(market.countryCode, true) &&
                        it.city.equals(market.city, true) &&
                        it.serviceCategory == state.selectedCategory.name
                } ?: policies.firstOrNull {
                    !it.isTaxiReference && it.isActive &&
                        it.countryCode.equals(market.countryCode, true) &&
                        it.serviceCategory == state.selectedCategory.name
                } ?: policies.first { !it.isTaxiReference && it.serviceCategory == state.selectedCategory.name }

                val taxiRefPolicy = policies.firstOrNull {
                    it.isTaxiReference &&
                        it.countryCode.equals(market.countryCode, true) &&
                        it.city.equals(market.city, true)
                }

                val isAirport = state.destinationAddress.contains("Airport", true) ||
                    state.destinationAddress.contains("مطار", true)

                val quote = PricingEngine.calculateQuote(
                    policy = commercialPolicy,
                    input = FareCalculationInput(
                        riderId = currentUserId,
                        pickupAddress = state.pickupAddress,
                        pickupLat = state.pickupPoint.latitude,
                        pickupLng = state.pickupPoint.longitude,
                        destinationAddress = state.destinationAddress,
                        destinationLat = state.destinationPoint.latitude,
                        destinationLng = state.destinationPoint.longitude,
                        routeDistanceKm = route.distanceKm,
                        routeDurationMin = route.durationMinutes,
                        isAirportTrip = isAirport
                    )
                )
                VeloInMemoryStore.quoteCache.put(quote.id, quote)

                val taxiFare = taxiRefPolicy?.let {
                    PricingEngine.calculateFinalFareFromPolicy(it, route.distanceKm, 0L, isAirportTrip = isAirport)
                }

                _bookingState.update {
                    it.copy(
                        routeResult = route,
                        currentQuote = quote,
                        taxiReferenceFareMinor = taxiFare,
                        isCalculatingRoute = false
                    )
                }
                refreshStripeAutoConfig()
                refreshEngineTelemetry()
            } catch (e: Exception) {
                _bookingState.update { it.copy(isCalculatingRoute = false) }
                _bannerMessage.value = e.localizedMessage
            }
        }
    }

    fun confirmRideAndStartDispatch() {
        val state = _bookingState.value
        val quote = state.currentQuote ?: return
        val account = _userAccount.value ?: return
        val autoCfg = paymentGateway.configureForRiderChoice(
            choice = state.selectedPaymentChoice,
            currencyCode = quote.currencyCode,
            decimals = quote.currencyDecimals.toInt(),
            linkedMethods = paymentMethods.value
        )
        _bookingState.update { it.copy(stripeAutoConfig = autoCfg) }

        viewModelScope.launch {
            _bookingState.update { it.copy(isDispatching = true) }
            val result = dispatchRepository.requestRide(
                riderName = account.fullName,
                quote = quote,
                paymentMethodId = autoCfg.resolvedTokenId
            )
            result.onSuccess { ride ->
                startAutomatedEscalation(ride, account.riderRatingAvg)
            }.onFailure {
                _bookingState.update { it.copy(isDispatching = false) }
                _bannerMessage.value = it.localizedMessage
            }
        }
    }

    fun retryRideDispatch(ride: Ride) {
        val account = _userAccount.value ?: return
        viewModelScope.launch {
            dispatchRepository.retryDispatch(ride.id)
            startAutomatedEscalation(ride.copy(status = RideStatus.SEARCHING_FOR_DRIVER.name, dispatchRound = 1L), account.riderRatingAvg)
        }
    }

    private fun startAutomatedEscalation(ride: Ride, riderRating: Double) {
        dispatchEscalationJob?.cancel()
        dispatchEscalationJob = viewModelScope.launch {
            val config = operationalConfig.value
            val roundDurationMs = config.offerDurationSeconds.coerceIn(10L, 35L) * 1000L

            for (round in 1..3) {
                val latest = riderRides.value.firstOrNull { it.id == ride.id }
                if (latest != null && latest.status !in listOf(
                        RideStatus.REQUESTED.name,
                        RideStatus.SEARCHING_FOR_DRIVER.name,
                        RideStatus.OFFERED.name
                    )
                ) {
                    _bookingState.update { it.copy(isDispatching = false) }
                    return@launch
                }

                dispatchRepository.dispatchRound(ride, round, config, riderRating)
                // React immediately in the same second when any driver accepts (don't wait 30s!)
                withTimeoutOrNull(roundDurationMs + 500L) {
                    riderRides.first { list ->
                        val live = list.firstOrNull { it.id == ride.id }
                        live != null && (live.driverId.isNotBlank() || live.status == RideStatus.CANCELLED.name)
                    }
                }
                dispatchRepository.expirePendingOffersForRound(ride.id, currentUserId)

                val postRound = riderRides.value.firstOrNull { it.id == ride.id }
                if (postRound != null && postRound.driverId.isNotBlank()) {
                    dispatchRepository.invalidateCompetingOffers(ride.id, currentUserId)
                    _bookingState.update { it.copy(isDispatching = false) }
                    return@launch
                }
                if (postRound != null && postRound.status == RideStatus.CANCELLED.name) {
                    _bookingState.update { it.copy(isDispatching = false) }
                    return@launch
                }
            }

            dispatchRepository.markNoDriverAvailable(ride.id)
            _bookingState.update { it.copy(isDispatching = false) }
        }
    }

    fun cancelActiveRide(ride: Ride, initiatorRole: String, reason: String) {
        viewModelScope.launch {
            dispatchEscalationJob?.cancel()
            val fee = if (ride.status in listOf(RideStatus.ACCEPTED.name, RideStatus.DRIVER_ARRIVING.name, RideStatus.ARRIVED.name)) {
                operationalConfig.value.cancellationFeeMinor
            } else 0L
            dispatchRepository.cancelRide(ride, initiatorRole, reason, fee)
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun initiateRidePayment(ride: Ride) {
        viewModelScope.launch {
            _isBusy.value = true
            val choice = if (ride.paymentMethodId != null) {
                RiderPaymentChoice.fromRidePaymentMethodId(ride.paymentMethodId)
            } else {
                _bookingState.value.selectedPaymentChoice
            }
            val cfg = paymentGateway.configureForRiderChoice(
                choice = choice,
                currencyCode = ride.currencyCode,
                decimals = ride.currencyDecimals.toInt(),
                linkedMethods = paymentMethods.value
            )
            val result = pricingRepository.initiateRidePayment(
                ride = ride,
                paymentMethodId = cfg.resolvedTokenId,
                paymentMethodType = cfg.paymentMethodType
            )
            _isBusy.value = false
            result.onSuccess { record ->
                _bannerMessage.value = when {
                    choice == RiderPaymentChoice.CASH -> "Cash payment mode recorded (${record.receiptNumber}) — pay driver at drop-off"
                    record.status == "FAILED" -> record.failureReason ?: "Stripe payment inactive"
                    else -> "Stripe ${cfg.paymentMethodType} ${record.status}: ${record.receiptNumber}"
                }
            }.onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun openRatingPrompt(ride: Ride, raterRole: String) {
        if (ride.status != RideStatus.COMPLETED.name) return
        val normalizedRole = raterRole.uppercase()
        val toUserId = if (normalizedRole == "RIDER") ride.driverId else ride.riderId
        if (toUserId.isBlank() || toUserId == currentUserId) return
        val counterpartyName = if (normalizedRole == "RIDER") {
            ride.driverName?.takeIf { it.isNotBlank() } ?: "Driver"
        } else {
            ride.riderName.ifBlank { "Passenger" }
        }
        _manualRatingPrompt.value = PendingRatingPrompt(
            ride = ride,
            raterRole = normalizedRole,
            toUserId = toUserId,
            counterpartyName = counterpartyName
        )
    }

    fun dismissRatingPrompt(prompt: PendingRatingPrompt) {
        val key = "${prompt.ride.id}_${prompt.raterRole}"
        _dismissedRatingKeys.update { it + key }
        if (_manualRatingPrompt.value?.ride?.id == prompt.ride.id &&
            _manualRatingPrompt.value?.raterRole == prompt.raterRole
        ) {
            _manualRatingPrompt.value = null
        }
        if (_autoCompletedPromptRide.value?.ride?.id == prompt.ride.id &&
            _autoCompletedPromptRide.value?.raterRole == prompt.raterRole
        ) {
            _autoCompletedPromptRide.value = null
        }
    }

    fun rateTrip(
        ride: Ride,
        toUserId: String,
        fromRole: String,
        stars: Long,
        comment: String,
        tags: List<String> = emptyList()
    ) {
        val account = _userAccount.value
        val raterName = account?.fullName.orEmpty()
        val lang = AppLanguage.fromCode(account?.preferredLanguage)
        val strings = StringsProvider.get(lang)
        viewModelScope.launch {
            _isBusy.value = true
            val result = dispatchRepository.submitRating(
                ride = ride,
                toUserId = toUserId,
                fromRole = fromRole,
                stars = stars,
                comment = comment,
                tags = tags,
                raterName = raterName
            )
            _isBusy.value = false
            result.onSuccess {
                val key = "${ride.id}_${fromRole.uppercase()}"
                _dismissedRatingKeys.update { it + key }
                if (_manualRatingPrompt.value?.ride?.id == ride.id) {
                    _manualRatingPrompt.value = null
                }
                if (_autoCompletedPromptRide.value?.ride?.id == ride.id) {
                    _autoCompletedPromptRide.value = null
                }
                _bannerMessage.value = strings.ratingSubmittedToast
            }.onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun addSavedLocation(label: String, address: String, point: GeoPoint) {
        viewModelScope.launch {
            val market = _bookingState.value.detectedMarket
            userRepository.addSavedLocation(label, address, point.latitude, point.longitude, market.countryCode, market.city)
        }
    }

    fun deleteSavedLocation(id: String) {
        viewModelScope.launch { userRepository.deleteSavedLocation(id) }
    }

    fun addTokenizedPaymentMethod(brand: String, last4: String, expMonth: Long, expYear: Long) {
        viewModelScope.launch {
            try {
                userRepository.addTokenizedPaymentMethod(
                    brand = brand,
                    last4 = last4,
                    expiryMonth = expMonth,
                    expiryYear = expYear,
                    stripeTokenId = "pm_tok_${brand.lowercase()}_$last4"
                )
            } catch (e: Exception) {
                _bannerMessage.value = e.localizedMessage
            }
        }
    }

    // -------------------------------------------------------------------------
    // Driver Actions
    // -------------------------------------------------------------------------

    fun saveDriverOnboarding(
        fullName: String,
        mobileNumber: String,
        nationalId: String,
        licenceNumber: String,
        licenceExpiry: String,
        profilePhotoUrl: String?,
        vehicleMake: String,
        vehicleModel: String,
        vehicleYear: Long,
        vehicleCategory: VehicleCategory,
        vehicleColor: String,
        registrationNumber: String,
        plateNumber: String,
        vehiclePhotoUrls: List<String>,
        submitForReview: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = driverRepository.saveDriverOnboarding(
                fullName, mobileNumber, nationalId, licenceNumber, licenceExpiry,
                profilePhotoUrl, vehicleMake, vehicleModel, vehicleYear,
                vehicleCategory, vehicleColor, registrationNumber, plateNumber,
                vehiclePhotoUrls, submitForReview
            )
            _isBusy.value = false
            result.onSuccess {
                _bannerMessage.value = if (submitForReview) "Submitted for admin verification" else "Driver draft saved"
            }.onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun uploadDriverDocument(
        docType: DriverDocumentType,
        countryCode: String,
        docNumber: String,
        expiryDate: String,
        imageUri: String
    ) {
        viewModelScope.launch {
            driverRepository.uploadDriverDocument(docType, countryCode, docNumber, expiryDate, imageUri)
                .onSuccess { _bannerMessage.value = "${docType.name} uploaded (PENDING review)" }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun toggleDriverOnline(goOnline: Boolean, currentPoint: GeoPoint) {
        viewModelScope.launch {
            val geo = mappingProvider.reverseGeocode(currentPoint)
            driverRepository.setDriverOnlineStatus(goOnline, currentPoint, geo.countryCode, geo.city)
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun pushDriverGpsUpdate(point: GeoPoint, heading: Double = 0.0, speedKmh: Double = 30.0) {
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val activeRideId = ownDriverAvailability.value?.activeRideId.orEmpty()
            val packet = LocationUpdatePacket(
                entityId = currentUserId,
                role = "DRIVER",
                rideId = activeRideId,
                latitude = point.latitude,
                longitude = point.longitude,
                heading = heading,
                speedKmh = speedKmh,
                h3CellHex = H3Core.cellToString(H3Core.latLngToCell(point.latitude, point.longitude)),
                timestampMs = nowMs
            )
            messageQueue.enqueue(
                topic = "location.update",
                payloadJson = locationPacketAdapter.toJson(packet),
                priority = MessagePriority.URGENT,
                idempotencyKey = "gps_${currentUserId}_${nowMs}"
            )
            refreshEngineTelemetry()
        }
    }

    fun acceptRideOffer(offer: RideOffer) {
        if (offer.id in _revokedOfferIds.value) {
            alertPlayer?.stopOfferAlert(offer.id)
            _bannerMessage.value = "Ride already accepted by another driver — offer revoked"
            return
        }
        val profile = driverProfile.value ?: return
        alertPlayer?.stopOfferAlert(offer.id)
        viewModelScope.launch {
            _isBusy.value = true
            val result = dispatchRepository.acceptRideOfferAtomically(offer, profile)
            _isBusy.value = false
            result.onSuccess {
                _bannerMessage.value = "Ride accepted! Competing offers revoked immediately."
            }.onFailure { err ->
                _revokedOfferIds.update { it + offer.id }
                _bannerMessage.value = err.localizedMessage
            }
        }
    }

    fun rejectRideOffer(offer: RideOffer) {
        alertPlayer?.stopOfferAlert(offer.id)
        viewModelScope.launch {
            dispatchRepository.rejectOrExpireOffer(offer, expired = false)
        }
    }

    fun advanceDriverTrip(ride: Ride, nextStatus: RideStatus, actualKm: Double = ride.estimatedDistanceKm, waitMins: Long = 0L) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = dispatchRepository.updateDriverTripStatus(
                ride = ride,
                targetStatus = nextStatus,
                actualDistanceKm = actualKm,
                waitingMinutes = waitMins,
                commissionPercent = operationalConfig.value.platformCommissionPercent
            )
            _isBusy.value = false
            result.onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch { pricingRepository.markNotificationRead(id) }
    }

    fun submitComplaint(rideId: String, category: String, description: String) {
        viewModelScope.launch {
            pricingRepository.submitComplaint(rideId, category, description)
                .onSuccess { _bannerMessage.value = "Support report submitted" }
                .onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    private fun observeFcmLifecycle() {
        val repo = fcmTokenRepository
        val coordinator = fcmPushCoordinator

        // Keep device FCM token + topic subscriptions in sync with account mode/language/country
        if (repo != null && coordinator != null) {
            viewModelScope.launch {
                userAccount.collect { account ->
                    if (account != null) {
                        val syncedResult = coordinator.syncRegistration(
                            uid = currentUserId,
                            activeMode = account.activeMode,
                            countryCode = account.countryCode,
                            preferredLanguage = account.preferredLanguage,
                            notificationsEnabled = _pushNotificationsEnabled.value,
                            tokenRepository = repo
                        )
                        _fcmSyncState.value = syncedResult
                    }
                }
            }
        }

        // React to FCM push messages & token rotations while app is open
        viewModelScope.launch {
            FcmPushEventBus.events.collect { event ->
                when (event) {
                    is FcmPushEvent.TokenRefreshed -> {
                        coordinator?.cacheToken(event.token)
                        val account = _userAccount.value
                        if (repo != null && coordinator != null && account != null) {
                            _fcmSyncState.value = coordinator.syncRegistration(
                                uid = currentUserId,
                                activeMode = account.activeMode,
                                countryCode = account.countryCode,
                                preferredLanguage = account.preferredLanguage,
                                notificationsEnabled = _pushNotificationsEnabled.value,
                                tokenRepository = repo,
                                explicitToken = event.token
                            )
                        }
                    }
                    is FcmPushEvent.MessageReceived -> {
                        val payload = event.payload
                        if (payload.eventType == FcmPushEventType.OFFER_REVOKED) {
                            val offerId = payload.offerId.ifBlank { payload.rideId }
                            if (offerId.isNotBlank()) {
                                _revokedOfferIds.update { it + offerId }
                                alertPlayer?.stopOfferAlert(offerId)
                            }
                        }
                        if (payload.title.isNotBlank()) {
                            _bannerMessage.value = "${payload.title}: ${payload.body}"
                        }
                        refreshEngineTelemetry()
                    }
                }
            }
        }

        // Mirror newly arriving Firestore unread notifications to system status-bar tray
        viewModelScope.launch {
            notifications.collect { list ->
                if (!initialNotificationsSeeded) {
                    if (list.isNotEmpty()) {
                        list.forEach { n ->
                            seenNotificationKeys.add(n.idempotencyKey.ifBlank { n.id })
                        }
                        initialNotificationsSeeded = true
                    } else if (_accountLoaded.value) {
                        initialNotificationsSeeded = true
                    }
                    return@collect
                }
                val ctx = appContext ?: return@collect
                if (!_pushNotificationsEnabled.value) return@collect
                val lang = AppLanguage.fromCode(_userAccount.value?.preferredLanguage)
                list.filter { !it.isRead }.forEach { note ->
                    val key = note.idempotencyKey.ifBlank { note.id }
                    if (seenNotificationKeys.add(key)) {
                        TripStatusNotificationHelper.showFromAppNotification(ctx, note, lang)
                    }
                }
            }
        }
    }

    fun refreshFcmToken() {
        val repo = fcmTokenRepository ?: return
        val coordinator = fcmPushCoordinator ?: return
        val account = _userAccount.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val result = coordinator.syncRegistration(
                uid = currentUserId,
                activeMode = account.activeMode,
                countryCode = account.countryCode,
                preferredLanguage = account.preferredLanguage,
                notificationsEnabled = _pushNotificationsEnabled.value,
                tokenRepository = repo
            )
            _fcmSyncState.value = result
            _isBusy.value = false
            _bannerMessage.value = result.statusNote.ifBlank { "FCM token sync completed" }
        }
    }

    fun togglePushNotifications(enabled: Boolean) {
        _pushNotificationsEnabled.value = enabled
        val repo = fcmTokenRepository ?: return
        val coordinator = fcmPushCoordinator ?: return
        val account = _userAccount.value ?: return
        viewModelScope.launch {
            val result = coordinator.syncRegistration(
                uid = currentUserId,
                activeMode = account.activeMode,
                countryCode = account.countryCode,
                preferredLanguage = account.preferredLanguage,
                notificationsEnabled = enabled,
                tokenRepository = repo
            )
            _fcmSyncState.value = result
            fcmTokens.value.forEach { existingToken ->
                repo.updateNotificationPreference(existingToken.id, enabled)
            }
            _bannerMessage.value = if (enabled) {
                "FCM push alerts enabled"
            } else {
                "FCM push alerts paused"
            }
        }
    }

    fun requestDeepLinkNavigation(route: String) {
        _pendingNavigationRoute.value = route
    }

    fun consumePendingNavigationRoute() {
        _pendingNavigationRoute.value = null
    }

    private fun handleStreamError(tag: String, error: Throwable) {
        val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        if (fe == null && error is CancellationException) throw error
        Log.w("VeloRideVM", "Stream error [$tag]", fe ?: error)
    }
}
