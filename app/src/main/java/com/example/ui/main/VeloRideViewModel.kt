package com.example.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.alerts.RideAlertPlayer
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocaleFormatter
import com.example.data.firestore.DispatchRepository
import com.example.data.firestore.DriverRepository
import com.example.data.firestore.PricingAndAdminRepository
import com.example.data.firestore.UserRepository
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
import com.example.domain.model.OperationalConfig
import com.example.domain.model.PaymentMethodRecord
import com.example.domain.model.PaymentRecord
import com.example.domain.model.PricingPolicy
import com.example.domain.model.Ride
import com.example.domain.model.RideOffer
import com.example.domain.model.RideStatus
import com.example.domain.model.SavedLocation
import com.example.domain.model.UserAccount
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
    val routeResult: RouteResult? = null,
    val currentQuote: FareQuote? = null,
    val taxiReferenceFareMinor: Long? = null,
    val searchResults: List<PlaceSearchResult> = emptyList(),
    val isCalculatingRoute: Boolean = false,
    val isDispatching: Boolean = false
)

class VeloRideViewModel(
    private val currentUserId: String,
    private val userRepository: UserRepository,
    private val driverRepository: DriverRepository,
    private val dispatchRepository: DispatchRepository,
    private val pricingRepository: PricingAndAdminRepository,
    private val mappingProvider: MappingProvider,
    private val paymentGateway: PaymentGateway,
    private val alertPlayer: RideAlertPlayer?
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

    val pendingDriverOffers: StateFlow<List<RideOffer>> = dispatchRepository.observePendingDriverOffers(currentUserId)
        .catch { handleStreamError("pendingOffers", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

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

    val driverEarnings: StateFlow<List<DriverEarning>> = pricingRepository.observeDriverEarnings(currentUserId)
        .catch { handleStreamError("driverEarnings", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val driverPayouts: StateFlow<List<DriverPayout>> = pricingRepository.observeDriverPayouts(currentUserId)
        .catch { handleStreamError("driverPayouts", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val riderPayments: StateFlow<List<PaymentRecord>> = pricingRepository.observeRiderPayments(currentUserId)
        .catch { handleStreamError("riderPayments", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _assignedDriverLocation = MutableStateFlow<DriverAvailability?>(null)
    val assignedDriverLocation: StateFlow<DriverAvailability?> = _assignedDriverLocation.asStateFlow()

    private var dispatchEscalationJob: Job? = null
    private var assignedDriverObserverJob: Job? = null
    private var observedAssignedDriverId: String? = null

    init {
        observeAccount()
        checkAdminStatus()
        startClockTicker()
        observeDriverOfferAlerts()
        observeAssignedDriverTracking()
        recalculateQuote()
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
                val activeOffer = offers.firstOrNull { _nowMs.value <= it.expiresAtMs }
                if (activeOffer != null && alertPlayer != null) {
                    val fareText = LocaleFormatter.formatCompactCurrency(
                        activeOffer.estimatedFareMinor,
                        activeOffer.currencySymbol,
                        activeOffer.currencyDecimals.toInt(),
                        AppLanguage.fromCode(_userAccount.value?.preferredLanguage)
                    )
                    alertPlayer.notifyNewRideOffer(
                        offerId = activeOffer.id,
                        pickup = activeOffer.pickupAddress,
                        destination = activeOffer.destinationAddress,
                        fareDisplay = fareText
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

    fun recalculateQuote() {
        viewModelScope.launch {
            val state = _bookingState.value
            _bookingState.update { it.copy(isCalculatingRoute = true) }
            try {
                val route = mappingProvider.calculateRoute(state.pickupPoint, state.destinationPoint)
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
            } catch (e: Exception) {
                _bookingState.update { it.copy(isCalculatingRoute = false) }
                _bannerMessage.value = e.localizedMessage
            }
        }
    }

    fun confirmRideAndStartDispatch() {
        val quote = _bookingState.value.currentQuote ?: return
        val account = _userAccount.value ?: return
        val defaultPmId = paymentMethods.value.firstOrNull { it.isDefault }?.id

        viewModelScope.launch {
            _bookingState.update { it.copy(isDispatching = true) }
            val result = dispatchRepository.requestRide(
                riderName = account.fullName,
                quote = quote,
                paymentMethodId = defaultPmId
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
                delay(roundDurationMs + 1000L)
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
            val pm = paymentMethods.value.firstOrNull { it.isDefault }
            val result = pricingRepository.initiateRidePayment(
                ride = ride,
                paymentMethodId = pm?.stripePaymentMethodId,
                paymentMethodType = pm?.methodType ?: "CARD"
            )
            _isBusy.value = false
            result.onSuccess { record ->
                _bannerMessage.value = if (record.status == "FAILED") {
                    record.failureReason ?: "Stripe payment inactive"
                } else {
                    "Payment ${record.status}: ${record.receiptNumber}"
                }
            }.onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun rateTrip(ride: Ride, toUserId: String, fromRole: String, stars: Long, comment: String) {
        viewModelScope.launch {
            dispatchRepository.submitRating(ride, toUserId, fromRole, stars, comment)
                .onFailure { _bannerMessage.value = it.localizedMessage }
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
            val activeRideId = ownDriverAvailability.value?.activeRideId.orEmpty()
            driverRepository.updateLiveLocation(point, heading, speedKmh, System.currentTimeMillis(), activeRideId)
        }
    }

    fun acceptRideOffer(offer: RideOffer) {
        val profile = driverProfile.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            val result = dispatchRepository.acceptRideOfferAtomically(offer, profile)
            _isBusy.value = false
            result.onFailure { _bannerMessage.value = it.localizedMessage }
        }
    }

    fun rejectRideOffer(offer: RideOffer) {
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

    private fun handleStreamError(tag: String, error: Throwable) {
        val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        if (fe == null && error is CancellationException) throw error
        Log.w("VeloRideVM", "Stream error [$tag]", fe ?: error)
    }
}
