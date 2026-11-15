package com.example.data.firestore

import android.content.Context
import com.example.R
import com.example.core.engine.ConditionalEngineStats
import com.example.core.engine.ConditionalUpdateEngine
import com.example.core.engine.H3SpatialIndex
import com.example.core.engine.RideEvent
import com.example.core.engine.RideStateMachine
import com.example.core.engine.TransitionResult
import com.example.core.engine.VeloInMemoryStore
import com.example.domain.mapping.GeoPoint
import com.example.domain.mapping.MappingProvider
import com.example.domain.mapping.OsrmMappingProvider
import com.example.domain.model.DriverAvailability
import com.example.domain.model.DriverOnlineStatus
import com.example.domain.model.DriverProfile
import com.example.domain.model.FareQuote
import com.example.domain.model.OfferStatus
import com.example.domain.model.OperationalConfig
import com.example.domain.model.PricingPolicy
import com.example.domain.model.RatingRecord
import com.example.domain.model.Ride
import com.example.domain.model.RideOffer
import com.example.domain.model.RideStatus
import com.example.domain.model.VehicleCategory
import com.example.domain.pricing.PricingEngine
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class DispatchRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val mappingProvider: MappingProvider = OsrmMappingProvider(),
    private val driverSpatialIndex: H3SpatialIndex<DriverAvailability> = H3SpatialIndex(),
    private val rideCasEngine: ConditionalUpdateEngine<String, Ride> = ConditionalUpdateEngine()
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        mappingProvider = OsrmMappingProvider(context.applicationContext)
    )

    fun conditionalStats(): ConditionalEngineStats = rideCasEngine.stats()

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google.")
    }

    fun observeRiderRides(riderId: String): Flow<List<Ride>> = flow {
        val query = db.collection("rides").whereEqualTo("riderId", riderId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .also { rides -> rides.forEach { VeloInMemoryStore.activeRides.put(it.id, it) } }
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == riderId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "rides", auth)
                    }
                    throw error
                }
        )
    }

    fun observeDriverRides(driverId: String): Flow<List<Ride>> = flow {
        val query = db.collection("rides").whereEqualTo("driverId", driverId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == driverId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "rides", auth)
                    }
                    throw error
                }
        )
    }

    fun observeRiderTripHistory(riderId: String): Flow<List<Ride>> = flow {
        val query = db.collection("rides").whereEqualTo("riderId", riderId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter {
                            com.example.domain.model.TripHistoryMapper.isTerminalForRole(
                                it.status,
                                com.example.domain.model.TripHistoryRole.RIDER
                            )
                        }
                        .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == riderId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "rides", auth)
                    }
                    throw error
                }
        )
    }

    fun observeDriverTripHistory(driverId: String): Flow<List<Ride>> = flow {
        val query = db.collection("rides").whereEqualTo("driverId", driverId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter {
                            com.example.domain.model.TripHistoryMapper.isTerminalForRole(
                                it.status,
                                com.example.domain.model.TripHistoryRole.DRIVER
                            )
                        }
                        .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == driverId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "rides", auth)
                    }
                    throw error
                }
        )
    }

    fun observeRide(rideId: String): Flow<Ride?> = flow {
        val docRef = db.collection("rides").document(rideId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.GET, docRef.path, auth)
                    throw error
                }
        )
    }

    fun observeRatingsReceived(userId: String): Flow<List<RatingRecord>> = flow {
        val query = db.collection("ratings").whereEqualTo("toUserId", userId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(RatingRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "ratings", auth)
                    }
                    throw error
                }
        )
    }

    fun observeRatingsGiven(userId: String): Flow<List<RatingRecord>> = flow {
        val query = db.collection("ratings").whereEqualTo("fromUserId", userId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(RatingRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "ratings", auth)
                    }
                    throw error
                }
        )
    }

    fun observePendingDriverOffers(driverId: String): Flow<List<RideOffer>> = flow {
        val query = db.collection("ride_offers")
            .whereEqualTo("driverId", driverId)
            .whereEqualTo("status", OfferStatus.PENDING.name)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(RideOffer::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == driverId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, "ride_offers", auth)
                    }
                    throw error
                }
        )
    }

    fun observeOffersForRide(rideId: String, riderId: String): Flow<List<RideOffer>> = flow {
        val query = db.collection("ride_offers")
            .whereEqualTo("riderId", riderId)
            .whereEqualTo("rideId", rideId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(RideOffer::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "ride_offers", auth)
                    throw error
                }
        )
    }

    suspend fun requestRide(
        riderName: String,
        quote: FareQuote,
        paymentMethodId: String?
    ): Result<Ride> {
        val uid = requireUserId()
        val rideId = "ride_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val quoteRef = db.collection("fare_quotes").document(quote.id)
        val rideRef = db.collection("rides").document(rideId)

        return try {
            // Persist fare quote snapshot
            val quotePayload = mapOf(
                "riderId" to uid,
                "countryCode" to quote.countryCode,
                "countryName" to quote.countryName,
                "city" to quote.city,
                "currencyCode" to quote.currencyCode,
                "currencySymbol" to quote.currencySymbol,
                "currencyDecimals" to quote.currencyDecimals,
                "pickupAddress" to quote.pickupAddress,
                "pickupLat" to quote.pickupLat,
                "pickupLng" to quote.pickupLng,
                "destinationAddress" to quote.destinationAddress,
                "destinationLat" to quote.destinationLat,
                "destinationLng" to quote.destinationLng,
                "serviceCategory" to quote.serviceCategory,
                "estimatedDistanceKm" to quote.estimatedDistanceKm,
                "estimatedDurationMin" to quote.estimatedDurationMin,
                "baseFareMinor" to quote.baseFareMinor,
                "distanceChargeMinor" to quote.distanceChargeMinor,
                "waitingChargeMinor" to quote.waitingChargeMinor,
                "bookingFeeMinor" to quote.bookingFeeMinor,
                "surchargesMinor" to quote.surchargesMinor,
                "taxesMinor" to quote.taxesMinor,
                "discountsMinor" to quote.discountsMinor,
                "finalEstimatedFareMinor" to quote.finalEstimatedFareMinor,
                "pricingVersion" to quote.pricingVersion,
                "pricingPolicyId" to quote.pricingPolicyId,
                "createdAtMs" to quote.createdAtMs,
                "expiresAtMs" to quote.expiresAtMs,
                "createdAt" to FieldValue.serverTimestamp()
            )
            quoteRef.set(quotePayload).await()

            val ridePayload = mapOf(
                "riderId" to uid,
                "driverId" to "",
                "riderName" to riderName.ifBlank { "Rider" },
                "pickupAddress" to quote.pickupAddress,
                "pickupLat" to quote.pickupLat,
                "pickupLng" to quote.pickupLng,
                "destinationAddress" to quote.destinationAddress,
                "destinationLat" to quote.destinationLat,
                "destinationLng" to quote.destinationLng,
                "countryCode" to quote.countryCode,
                "countryName" to quote.countryName,
                "city" to quote.city,
                "currencyCode" to quote.currencyCode,
                "currencySymbol" to quote.currencySymbol,
                "currencyDecimals" to quote.currencyDecimals,
                "vehicleCategory" to quote.serviceCategory,
                "status" to RideStatus.SEARCHING_FOR_DRIVER.name,
                "dispatchRound" to 1L,
                "estimatedDistanceKm" to quote.estimatedDistanceKm,
                "estimatedDurationMin" to quote.estimatedDurationMin,
                "actualDistanceKm" to 0.0,
                "waitingMinutes" to 0L,
                "estimatedFareMinor" to quote.finalEstimatedFareMinor,
                "finalFareMinor" to 0L,
                "pricingVersion" to quote.pricingVersion,
                "pricingPolicyId" to quote.pricingPolicyId,
                "quoteId" to quote.id,
                "paymentStatus" to "PENDING",
                "paymentMethodId" to paymentMethodId,
                "offeredDriverIds" to emptyList<String>(),
                "cancellationFeeMinor" to 0L,
                "riderRated" to false,
                "driverRated" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            rideRef.set(ridePayload).await()
            recordStatusHistory(rideId, uid, "RIDER", "", RideStatus.SEARCHING_FOR_DRIVER.name, "Ride requested")

            val created = rideRef.get().await()
                .toObject(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: Ride(id = rideId, riderId = uid, status = RideStatus.SEARCHING_FOR_DRIVER.name)
            RideStateMachine.transition(rideId, RideStatus.REQUESTED, "", RideEvent.StartSearch)
            VeloInMemoryStore.activeRides.put(rideId, created)
            rideCasEngine.upsertIfNewer(rideId, 1L, System.currentTimeMillis(), created)
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, rideRef.path, auth)
            Result.failure(e)
        }
    }

    /**
     * Dispatches eligible nearby drivers for Round 1, 2, or 3.
     * Excludes drivers who already received/rejected/expired an offer for this ride.
     */
    suspend fun dispatchRound(
        ride: Ride,
        round: Int,
        config: OperationalConfig,
        riderRatingAvg: Double
    ): Result<Int> {
        val uid = requireUserId()
        val radiusKm = when (round) {
            1 -> config.dispatchRound1RadiusKm
            2 -> config.dispatchRound2RadiusKm
            else -> config.dispatchRound3RadiusKm
        }

        return try {
            val existingOffersSnap = db.collection("ride_offers")
                .whereEqualTo("riderId", uid)
                .whereEqualTo("rideId", ride.id)
                .get()
                .await()
            val previouslyOfferedDriverIds = existingOffersSnap.documents
                .mapNotNull { it.getString("driverId") }
                .toSet()

            val minFreshTs = System.currentTimeMillis() - (config.gpsFreshnessMaxSeconds * 1000L)
            val candidatesSnap = db.collection("driver_availability")
                .whereEqualTo("approvalStatus", "APPROVED")
                .whereEqualTo("status", DriverOnlineStatus.ONLINE.name)
                .whereEqualTo("vehicleCategory", ride.vehicleCategory)
                .get()
                .await()

            val pickup = GeoPoint(ride.pickupLat, ride.pickupLng)
            val fetchedDrivers = candidatesSnap
                .toObjects(DriverAvailability::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

            // Hydrate in-memory cache + H3 spatial index before performing O(1) hex-disk search
            VeloInMemoryStore.driverLocations.putAll(fetchedDrivers.associateBy { it.driverId })
            driverSpatialIndex.batchUpsert(
                fetchedDrivers.map { d -> Triple(d.driverId, GeoPoint(d.latitude, d.longitude), d) }
            )

            val h3Search = driverSpatialIndex.searchNearby(
                center = pickup,
                radiusKm = radiusKm,
                limit = 15,
                filter = { driver ->
                    driver.driverId != uid &&
                        driver.activeRideId.isBlank() &&
                        driver.locationTimestampMs >= minFreshTs &&
                        driver.driverId !in previouslyOfferedDriverIds &&
                        driver.vehicleCategory == ride.vehicleCategory
                }
            )
            val rankedDrivers = h3Search.matches
                .sortedWith(
                    compareBy<com.example.core.engine.SpatialMatch<DriverAvailability>> { it.distanceKm }
                        .thenByDescending { it.item.locationTimestampMs }
                )
                .take(5)
                .map { it.item to it.distanceKm }

            if (rankedDrivers.isEmpty()) {
                db.collection("rides").document(ride.id).update(
                    mapOf(
                        "dispatchRound" to round.toLong(),
                        "status" to RideStatus.SEARCHING_FOR_DRIVER.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                return Result.success(0)
            }

            val issuedAt = System.currentTimeMillis()
            val expiresAt = issuedAt + (config.offerDurationSeconds.coerceIn(10L, 35L) * 1000L)

            for ((driver, approxKm) in rankedDrivers) {
                val roadToPickupKm = try {
                    mappingProvider.calculateRoute(GeoPoint(driver.latitude, driver.longitude), pickup).distanceKm
                } catch (_: Exception) {
                    (approxKm * 1.25).coerceAtLeast(0.3)
                }
                val offerId = "${ride.id}_${driver.driverId}"
                val offerPayload = mapOf(
                    "rideId" to ride.id,
                    "riderId" to uid,
                    "driverId" to driver.driverId,
                    "round" to round.toLong(),
                    "status" to OfferStatus.PENDING.name,
                    "pickupAddress" to ride.pickupAddress,
                    "pickupLat" to ride.pickupLat,
                    "pickupLng" to ride.pickupLng,
                    "destinationAddress" to ride.destinationAddress,
                    "destinationLat" to ride.destinationLat,
                    "destinationLng" to ride.destinationLng,
                    "distanceToPickupKm" to roadToPickupKm,
                    "estimatedTripDistanceKm" to ride.estimatedDistanceKm,
                    "estimatedDurationMin" to ride.estimatedDurationMin,
                    "estimatedFareMinor" to ride.estimatedFareMinor,
                    "currencyCode" to ride.currencyCode,
                    "currencySymbol" to ride.currencySymbol,
                    "currencyDecimals" to ride.currencyDecimals,
                    "vehicleCategory" to ride.vehicleCategory,
                    "riderRatingAvg" to riderRatingAvg,
                    "issuedAtMs" to issuedAt,
                    "expiresAtMs" to expiresAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("ride_offers").document(offerId).set(offerPayload).await()

                // Idempotent notification entry for driver
                createNotification(
                    userId = driver.driverId,
                    eventType = "DRIVER_RIDE_OFFER",
                    idempotencyKey = "offer_${offerId}",
                    titleAr = "طلب رحلة جديد • ${ride.currencySymbol} ${ride.estimatedFareMinor / 100}",
                    titleEn = "New Ride Offer • ${ride.currencySymbol} ${ride.estimatedFareMinor / 100}",
                    bodyAr = "من ${ride.pickupAddress} إلى ${ride.destinationAddress}",
                    bodyEn = "${ride.pickupAddress} → ${ride.destinationAddress}",
                    rideId = ride.id,
                    channel = "FCM_PUSH"
                )
            }

            RideStateMachine.transition(
                rideId = ride.id,
                currentStatus = RideStateMachine.parseStatus(ride.status),
                currentDriverId = ride.driverId,
                event = RideEvent.DispatchOffer(round)
            )
            val allOfferedIds = (previouslyOfferedDriverIds + rankedDrivers.map { it.first.driverId })
                .distinct()
                .take(30)
            db.collection("rides").document(ride.id).update(
                mapOf(
                    "dispatchRound" to round.toLong(),
                    "status" to RideStatus.OFFERED.name,
                    "offeredDriverIds" to allOfferedIds,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(rankedDrivers.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "ride_offers", auth)
            Result.failure(e)
        }
    }

    suspend fun markNoDriverAvailable(rideId: String) {
        val uid = requireUserId()
        try {
            RideStateMachine.transition(rideId, RideStatus.OFFERED, "", RideEvent.MarkNoDriver)
            db.collection("rides").document(rideId).update(
                mapOf(
                    "status" to RideStatus.NO_DRIVER_AVAILABLE.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordStatusHistory(rideId, uid, "SYSTEM", RideStatus.OFFERED.name, RideStatus.NO_DRIVER_AVAILABLE.name, "All 3 dispatch rounds exhausted")
            createNotification(
                userId = uid,
                eventType = "NO_DRIVER_AVAILABLE",
                idempotencyKey = "nodriver_$rideId",
                titleAr = "لم يتم العثور على سائق متاح",
                titleEn = "No drivers available right now",
                bodyAr = "يمكنك إعادة محاولة البحث عن سائق قريب.",
                bodyEn = "All nearby drivers are busy. Tap to retry dispatch.",
                rideId = rideId,
                channel = "FCM_PUSH"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "rides/$rideId", auth)
        }
    }

    suspend fun retryDispatch(rideId: String) {
        val uid = requireUserId()
        try {
            RideStateMachine.transition(rideId, RideStatus.NO_DRIVER_AVAILABLE, "", RideEvent.RetrySearch)
            db.collection("rides").document(rideId).update(
                mapOf(
                    "status" to RideStatus.SEARCHING_FOR_DRIVER.name,
                    "dispatchRound" to 1L,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordStatusHistory(rideId, uid, "RIDER", RideStatus.NO_DRIVER_AVAILABLE.name, RideStatus.SEARCHING_FOR_DRIVER.name, "Rider retried dispatch")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "rides/$rideId", auth)
        }
    }

    suspend fun expirePendingOffersForRound(rideId: String, riderId: String) {
        try {
            val snap = db.collection("ride_offers")
                .whereEqualTo("riderId", riderId)
                .whereEqualTo("rideId", rideId)
                .whereEqualTo("status", OfferStatus.PENDING.name)
                .get()
                .await()
            val nowMs = System.currentTimeMillis()
            for (doc in snap.documents) {
                val expiresAt = doc.getLong("expiresAtMs") ?: 0L
                if (nowMs >= expiresAt) {
                    doc.reference.update(
                        mapOf("status" to OfferStatus.EXPIRED.name, "updatedAt" to FieldValue.serverTimestamp())
                    ).await()
                }
            }
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }

    /**
     * ATOMIC SINGLE-WINNER RIDE ASSIGNMENT (Section 7D):
     * Performs a Firestore transaction checking offer validity, expiration, and ride availability,
     * then atomically updates ride + offer + driver availability.
     */
    suspend fun acceptRideOfferAtomically(
        offer: RideOffer,
        driverProfile: DriverProfile,
        nowMs: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val uid = requireUserId()
        require(offer.driverId == uid) { "Driver can only accept their own offer." }

        val rideRef = db.collection("rides").document(offer.rideId)
        val offerRef = db.collection("ride_offers").document(offer.id)
        val availRef = db.collection("driver_availability").document(uid)

        return try {
            val competingDriverIds: List<String> = db.runTransaction { tx ->
                val rideSnap = tx.get(rideRef)
                val offerSnap = tx.get(offerRef)
                val availSnap = tx.get(availRef)

                if (!rideSnap.exists()) throw IllegalStateException("Ride no longer exists.")
                if (!offerSnap.exists()) throw IllegalStateException("Offer no longer exists.")

                val currentOfferStatus = offerSnap.getString("status") ?: ""
                val expiresAtMs = offerSnap.getLong("expiresAtMs") ?: 0L
                if (currentOfferStatus != OfferStatus.PENDING.name) {
                    throw IllegalStateException("Offer is no longer pending ($currentOfferStatus).")
                }
                if (nowMs > expiresAtMs) {
                    throw IllegalStateException("30-second acceptance window has expired.")
                }

                val currentRideDriver = rideSnap.getString("driverId") ?: ""
                val currentRideStatus = rideSnap.getString("status") ?: ""
                val fsmCheck = RideStateMachine.transition(
                    rideId = offer.rideId,
                    currentStatus = RideStateMachine.parseStatus(currentRideStatus),
                    currentDriverId = currentRideDriver,
                    event = RideEvent.DriverAccept(uid)
                )
                if (fsmCheck is TransitionResult.Rejected) {
                    throw IllegalStateException("Another driver has already accepted this ride (${fsmCheck.reason}).")
                }

                val activeRideId = availSnap.getString("activeRideId") ?: ""
                if (activeRideId.isNotBlank()) {
                    throw IllegalStateException("Driver already has an active assigned trip.")
                }

                val vehicleDesc = "${driverProfile.vehicleColor} ${driverProfile.vehicleMake} ${driverProfile.vehicleModel}".trim()
                tx.update(
                    rideRef,
                    mapOf(
                        "driverId" to uid,
                        "driverName" to driverProfile.fullName,
                        "driverPlate" to driverProfile.plateNumber,
                        "driverVehicle" to vehicleDesc,
                        "status" to RideStatus.ACCEPTED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.update(
                    offerRef,
                    mapOf(
                        "status" to OfferStatus.ACCEPTED.name,
                        "respondedAtMs" to nowMs,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                tx.update(
                    availRef,
                    mapOf(
                        "status" to DriverOnlineStatus.ACCEPTED.name,
                        "activeRideId" to offer.rideId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                @Suppress("UNCHECKED_CAST")
                val rawList = rideSnap.get("offeredDriverIds") as? List<*>
                rawList?.mapNotNull { it as? String }?.filter { it.isNotBlank() && it != uid }.orEmpty()
            }.await()

            // Same-second immediate revocation of all competing drivers in the same ride
            if (competingDriverIds.isNotEmpty()) {
                revokeCompetingOffersByIds(offer.rideId, competingDriverIds)
            }

            recordStatusHistory(
                offer.rideId,
                uid,
                "DRIVER",
                RideStatus.OFFERED.name,
                RideStatus.ACCEPTED.name,
                "Driver accepted atomically; ${competingDriverIds.size} competing offers revoked immediately"
            )
            createNotification(
                userId = offer.riderId,
                eventType = "DRIVER_ACCEPTED",
                idempotencyKey = "accepted_${offer.rideId}",
                titleAr = "تم قبول رحلتك! السائق في الطريق",
                titleEn = "Driver Accepted! On the way",
                bodyAr = "${driverProfile.fullName} • ${driverProfile.vehicleMake} ${driverProfile.vehicleModel} (${driverProfile.plateNumber})",
                bodyEn = "${driverProfile.fullName} • ${driverProfile.vehicleMake} ${driverProfile.vehicleModel} (${driverProfile.plateNumber})",
                rideId = offer.rideId,
                channel = "FCM_PUSH"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, rideRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun rejectOrExpireOffer(offer: RideOffer, expired: Boolean): Result<Unit> {
        val uid = requireUserId()
        val offerRef = db.collection("ride_offers").document(offer.id)
        val nextStatus = if (expired) OfferStatus.EXPIRED.name else OfferStatus.REJECTED.name
        return try {
            offerRef.update(
                mapOf(
                    "status" to nextStatus,
                    "respondedAtMs" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, offerRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun revokeCompetingOffersByIds(rideId: String, competingDriverIds: List<String>) {
        if (competingDriverIds.isEmpty()) return
        try {
            val batch = db.batch()
            competingDriverIds.distinct().forEach { otherUid ->
                val siblingRef = db.collection("ride_offers").document("${rideId}_$otherUid")
                batch.update(
                    siblingRef,
                    mapOf(
                        "status" to OfferStatus.CANCELLED_BY_OTHER.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        } catch (_: Exception) {
            // Fall back to per-document best-effort updates in case one sibling already rejected/expired
            competingDriverIds.distinct().forEach { otherUid ->
                try {
                    db.collection("ride_offers").document("${rideId}_$otherUid").update(
                        mapOf(
                            "status" to OfferStatus.CANCELLED_BY_OTHER.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun markOfferCancelledByOtherIfLost(offer: RideOffer) {
        try {
            db.collection("ride_offers").document(offer.id).update(
                mapOf(
                    "status" to OfferStatus.CANCELLED_BY_OTHER.name,
                    "respondedAtMs" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (_: Exception) {
            // Already cancelled/accepted/expired
        }
    }

    suspend fun invalidateCompetingOffers(rideId: String, riderId: String) {
        try {
            val snap = db.collection("ride_offers")
                .whereEqualTo("riderId", riderId)
                .whereEqualTo("rideId", rideId)
                .whereEqualTo("status", OfferStatus.PENDING.name)
                .get()
                .await()
            val batch = db.batch()
            snap.documents.forEach { doc ->
                batch.update(
                    doc.reference,
                    mapOf(
                        "status" to OfferStatus.CANCELLED_BY_OTHER.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            if (!snap.isEmpty) batch.commit().await()
        } catch (_: Exception) {
            // Non-blocking cleanup
        }
    }

    suspend fun updateDriverTripStatus(
        ride: Ride,
        targetStatus: RideStatus,
        actualDistanceKm: Double = ride.estimatedDistanceKm,
        waitingMinutes: Long = 0L,
        commissionPercent: Double = 15.0
    ): Result<Unit> {
        val uid = requireUserId()
        val fsmCheck = RideStateMachine.validateDirectTransition(ride, targetStatus)
        if (fsmCheck is TransitionResult.Rejected) {
            return Result.failure(IllegalStateException(fsmCheck.reason))
        }

        val rideRef = db.collection("rides").document(ride.id)
        val availRef = db.collection("driver_availability").document(uid)

        return try {
            if (targetStatus == RideStatus.COMPLETED) {
                val policySnap = db.collection("pricing_policies").document(ride.pricingPolicyId).get().await()
                val policy = policySnap.toObject(PricingPolicy::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                val finalFareMinor = if (policy != null) {
                    PricingEngine.calculateFinalFareFromPolicy(policy, actualDistanceKm, waitingMinutes)
                } else {
                    ride.estimatedFareMinor
                }
                val taxMinor = if (policy != null) {
                    (finalFareMinor * policy.taxPercentage / (100.0 + policy.taxPercentage)).toLong()
                } else 0L

                db.runTransaction { tx ->
                    val liveSnap = tx.get(rideRef)
                    val liveStatus = liveSnap.getString("status") ?: ""
                    if (liveStatus != ride.status) {
                        throw IllegalStateException("Conditional update conflict: expected ${ride.status}, found $liveStatus")
                    }
                    tx.update(
                        rideRef,
                        mapOf(
                            "status" to RideStatus.COMPLETED.name,
                            "actualDistanceKm" to actualDistanceKm,
                            "waitingMinutes" to waitingMinutes,
                            "finalFareMinor" to finalFareMinor,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.update(
                        availRef,
                        mapOf(
                            "status" to DriverOnlineStatus.ONLINE.name,
                            "activeRideId" to "",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    null
                }.await()

                val completedRide = ride.copy(
                    status = RideStatus.COMPLETED.name,
                    actualDistanceKm = actualDistanceKm,
                    waitingMinutes = waitingMinutes,
                    finalFareMinor = finalFareMinor
                )
                VeloInMemoryStore.activeRides.put(ride.id, completedRide)
                rideCasEngine.upsertIfNewer(ride.id, (rideCasEngine.get(ride.id)?.version ?: 0L) + 1L, System.currentTimeMillis(), completedRide)

                // Record immutable driver earnings ledger entry (separated from customer payment transactions)
                val breakdown = PricingEngine.calculateDriverEarnings(
                    grossFareMinor = finalFareMinor,
                    taxesMinor = taxMinor,
                    commissionPercent = commissionPercent
                )
                val earningId = "earn_${ride.id}"
                db.collection("driver_earnings").document(earningId).set(
                    mapOf(
                        "driverId" to uid,
                        "rideId" to ride.id,
                        "paymentId" to "",
                        "currencyCode" to ride.currencyCode,
                        "currencyDecimals" to ride.currencyDecimals,
                        "grossFareMinor" to breakdown.grossFareMinor,
                        "platformCommissionMinor" to breakdown.platformCommissionMinor,
                        "taxesMinor" to breakdown.taxesMinor,
                        "adjustmentsMinor" to 0L,
                        "refundsMinor" to 0L,
                        "netEarningsMinor" to breakdown.netDriverEarningsMinor,
                        "payoutStatus" to "PENDING",
                        "payoutId" to "",
                        "payoutReference" to "",
                        "payoutDateMs" to 0L,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()

                createNotification(
                    userId = ride.riderId,
                    eventType = "TRIP_COMPLETED",
                    idempotencyKey = "completed_${ride.id}",
                    titleAr = "اكتملت رحلتك",
                    titleEn = "Your trip is complete",
                    bodyAr = "الأجرة النهائية: ${ride.currencySymbol} ${finalFareMinor / 100}",
                    bodyEn = "Final fare: ${ride.currencySymbol} ${finalFareMinor / 100}",
                    rideId = ride.id,
                    channel = "FCM_PUSH"
                )
                createNotification(
                    userId = uid,
                    eventType = "TRIP_COMPLETED",
                    idempotencyKey = "completed_drv_${ride.id}",
                    titleAr = "تم إنهاء الرحلة وتسجيل الأرباح",
                    titleEn = "Trip completed & earnings logged",
                    bodyAr = "الأجرة الإجمالية: ${ride.currencySymbol} ${finalFareMinor / 100}",
                    bodyEn = "Total fare: ${ride.currencySymbol} ${finalFareMinor / 100}",
                    rideId = ride.id,
                    channel = "FCM_PUSH"
                )
            } else {
                val nextDriverStatus = when (targetStatus) {
                    RideStatus.DRIVER_ARRIVING, RideStatus.ARRIVED -> DriverOnlineStatus.ARRIVING.name
                    RideStatus.IN_PROGRESS -> DriverOnlineStatus.ON_TRIP.name
                    else -> DriverOnlineStatus.ONLINE.name
                }

                db.runTransaction { tx ->
                    val liveSnap = tx.get(rideRef)
                    val liveStatus = liveSnap.getString("status") ?: ""
                    if (liveStatus != ride.status) {
                        throw IllegalStateException("Conditional update conflict: expected ${ride.status}, found $liveStatus")
                    }
                    tx.update(
                        rideRef,
                        mapOf(
                            "status" to targetStatus.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.update(
                        availRef,
                        mapOf(
                            "status" to nextDriverStatus,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    null
                }.await()

                val updatedRide = ride.copy(status = targetStatus.name)
                VeloInMemoryStore.activeRides.put(ride.id, updatedRide)
                rideCasEngine.upsertIfNewer(ride.id, (rideCasEngine.get(ride.id)?.version ?: 0L) + 1L, System.currentTimeMillis(), updatedRide)

                when (targetStatus) {
                    RideStatus.DRIVER_ARRIVING -> {
                        createNotification(
                            userId = ride.riderId,
                            eventType = "DRIVER_ARRIVING",
                            idempotencyKey = "arriving_${ride.id}",
                            titleAr = "السائق في طريقه إليك",
                            titleEn = "Your driver is on the way",
                            bodyAr = "${ride.driverName ?: "السائق"} يتجه إلى موقع الانطلاق (${ride.pickupAddress})",
                            bodyEn = "${ride.driverName ?: "Driver"} is heading to ${ride.pickupAddress}",
                            rideId = ride.id,
                            channel = "FCM_PUSH"
                        )
                    }
                    RideStatus.ARRIVED -> {
                        createNotification(
                            userId = ride.riderId,
                            eventType = "DRIVER_ARRIVED",
                            idempotencyKey = "arrived_${ride.id}",
                            titleAr = "وصل السائق إلى نقطة الانطلاق",
                            titleEn = "Your driver has arrived",
                            bodyAr = "${ride.driverName ?: "السائق"} في انتظارك الآن",
                            bodyEn = "${ride.driverName ?: "Driver"} is waiting at pickup",
                            rideId = ride.id,
                            channel = "FCM_PUSH"
                        )
                    }
                    RideStatus.IN_PROGRESS -> {
                        createNotification(
                            userId = ride.riderId,
                            eventType = "TRIP_STARTED",
                            idempotencyKey = "started_${ride.id}",
                            titleAr = "بدأت رحلتك الآن",
                            titleEn = "Your trip has started",
                            bodyAr = "متجهون إلى ${ride.destinationAddress}",
                            bodyEn = "En route to ${ride.destinationAddress}",
                            rideId = ride.id,
                            channel = "FCM_PUSH"
                        )
                    }
                    else -> Unit
                }
            }

            recordStatusHistory(ride.id, uid, "DRIVER", ride.status, targetStatus.name, "Driver status transition")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, rideRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun cancelRide(
        ride: Ride,
        initiatorRole: String,
        reason: String,
        cancellationFeeMinor: Long
    ): Result<Unit> {
        val uid = requireUserId()
        val fsmCheck = RideStateMachine.transition(
            rideId = ride.id,
            currentStatus = RideStateMachine.parseStatus(ride.status),
            currentDriverId = ride.driverId,
            event = RideEvent.Cancel(initiatorRole, reason)
        )
        if (fsmCheck is TransitionResult.Rejected) {
            return Result.failure(IllegalStateException(fsmCheck.reason))
        }

        val rideRef = db.collection("rides").document(ride.id)
        val availRef = db.collection("driver_availability").document(uid)
        return try {
            db.runTransaction { tx ->
                val liveSnap = tx.get(rideRef)
                val liveStatus = liveSnap.getString("status") ?: ""
                if (liveStatus != ride.status) {
                    throw IllegalStateException("Conditional cancel conflict: ride changed to $liveStatus")
                }
                tx.update(
                    rideRef,
                    mapOf(
                        "status" to RideStatus.CANCELLED.name,
                        "cancellationInitiator" to initiatorRole,
                        "cancellationReason" to reason.take(240),
                        "cancellationFeeMinor" to cancellationFeeMinor.coerceAtLeast(0L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (ride.driverId == uid) {
                    tx.update(
                        availRef,
                        mapOf(
                            "status" to DriverOnlineStatus.ONLINE.name,
                            "activeRideId" to "",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                null
            }.await()

            val cancelledRide = ride.copy(status = RideStatus.CANCELLED.name)
            VeloInMemoryStore.activeRides.put(ride.id, cancelledRide)
            rideCasEngine.upsertIfNewer(ride.id, (rideCasEngine.get(ride.id)?.version ?: 0L) + 1L, System.currentTimeMillis(), cancelledRide)
            recordStatusHistory(ride.id, uid, initiatorRole, ride.status, RideStatus.CANCELLED.name, reason)
            val notifyRecipientId = when {
                initiatorRole.equals("RIDER", ignoreCase = true) && ride.driverId.isNotBlank() -> ride.driverId
                initiatorRole.equals("DRIVER", ignoreCase = true) -> ride.riderId
                else -> ""
            }
            if (notifyRecipientId.isNotBlank()) {
                createNotification(
                    userId = notifyRecipientId,
                    eventType = "RIDE_CANCELLED",
                    idempotencyKey = "cancelled_${ride.id}",
                    titleAr = "تم إلغاء الرحلة",
                    titleEn = "Ride cancelled",
                    bodyAr = "تم إلغاء الرحلة بواسطة ${if (initiatorRole == "RIDER") "الراكب" else "السائق"}",
                    bodyEn = "Ride cancelled by ${initiatorRole.lowercase()}: $reason",
                    rideId = ride.id,
                    channel = "FCM_PUSH"
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, rideRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun submitRating(
        ride: Ride,
        toUserId: String,
        fromRole: String,
        stars: Long,
        comment: String,
        tags: List<String> = emptyList(),
        raterName: String = ""
    ): Result<Unit> {
        val uid = requireUserId()
        if (ride.status != RideStatus.COMPLETED.name) {
            return Result.failure(IllegalStateException("Ratings can only be submitted for completed rides."))
        }
        val normalizedRole = fromRole.uppercase()
        if (normalizedRole != "RIDER" && normalizedRole != "DRIVER") {
            return Result.failure(IllegalArgumentException("Unsupported rater role: $fromRole"))
        }
        if (toUserId.isBlank() || toUserId == uid) {
            return Result.failure(IllegalArgumentException("Valid counterparty user ID is required."))
        }
        val ratingId = "rat_${ride.id}_${normalizedRole.lowercase()}"
        val ratingRef = db.collection("ratings").document(ratingId)
        val rideRef = db.collection("rides").document(ride.id)
        val cleanTags = tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        val cleanRaterName = raterName.trim().take(120)
        return try {
            val batch = db.batch()
            val payload = mutableMapOf<String, Any>(
                "rideId" to ride.id,
                "fromUserId" to uid,
                "toUserId" to toUserId,
                "fromRole" to normalizedRole,
                "stars" to stars.coerceIn(1L, 5L),
                "comment" to comment.trim().take(400),
                "tags" to cleanTags,
                "raterName" to cleanRaterName,
                "createdAt" to FieldValue.serverTimestamp()
            )
            batch.set(ratingRef, payload)
            val flagField = if (normalizedRole == "RIDER") "riderRated" else "driverRated"
            batch.update(
                rideRef,
                mapOf(flagField to true, "updatedAt" to FieldValue.serverTimestamp())
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ratingRef.path, auth)
            Result.failure(e)
        }
    }

    private suspend fun recordStatusHistory(
        rideId: String,
        actorId: String,
        actorRole: String,
        fromStatus: String,
        toStatus: String,
        note: String
    ) {
        try {
            val id = "hist_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            db.collection("ride_status_history").document(id).set(
                mapOf(
                    "rideId" to rideId,
                    "actorId" to actorId,
                    "actorRole" to actorRole,
                    "fromStatus" to fromStatus,
                    "toStatus" to toStatus,
                    "note" to note.take(300),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (_: Exception) {
            // Audit trail should not block primary flow if transient
        }
    }

    suspend fun createNotification(
        userId: String,
        eventType: String,
        idempotencyKey: String,
        titleAr: String,
        titleEn: String,
        bodyAr: String,
        bodyEn: String,
        rideId: String,
        channel: String
    ) {
        val docId = "notif_${idempotencyKey.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(100)}"
        try {
            val ref = db.collection("notifications").document(docId)
            if (!ref.get().await().exists()) {
                ref.set(
                    mapOf(
                        "userId" to userId,
                        "eventType" to eventType,
                        "idempotencyKey" to idempotencyKey.take(150),
                        "titleAr" to titleAr.take(160),
                        "titleEn" to titleEn.take(160),
                        "bodyAr" to bodyAr.take(500),
                        "bodyEn" to bodyEn.take(500),
                        "rideId" to rideId,
                        "isRead" to false,
                        "channel" to channel,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (_: Exception) {
            // Idempotent notification delivery best effort
        }
    }
}
