package com.example.data.firestore

import android.content.Context
import com.example.R
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
    private val mappingProvider: MappingProvider = OsrmMappingProvider()
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        mappingProvider = OsrmMappingProvider(context.applicationContext)
    )

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
            val rankedDrivers = candidatesSnap
                .toObjects(DriverAvailability::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter { driver ->
                    driver.driverId != uid &&
                        driver.activeRideId.isBlank() &&
                        driver.locationTimestampMs >= minFreshTs &&
                        driver.driverId !in previouslyOfferedDriverIds
                }
                .map { driver ->
                    val driverPoint = GeoPoint(driver.latitude, driver.longitude)
                    val straightKm = mappingProvider.haversineKm(driverPoint, pickup)
                    driver to straightKm
                }
                .filter { (_, km) -> km <= radiusKm }
                .sortedWith(
                    compareBy<Pair<DriverAvailability, Double>> { it.second }
                        .thenByDescending { it.first.locationTimestampMs }
                )
                .take(5)

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
                    channel = "PUSH"
                )
            }

            db.collection("rides").document(ride.id).update(
                mapOf(
                    "dispatchRound" to round.toLong(),
                    "status" to RideStatus.OFFERED.name,
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
            db.collection("rides").document(rideId).update(
                mapOf(
                    "status" to RideStatus.NO_DRIVER_AVAILABLE.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            recordStatusHistory(rideId, uid, "SYSTEM", RideStatus.OFFERED.name, RideStatus.NO_DRIVER_AVAILABLE.name, "All 3 dispatch rounds exhausted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "rides/$rideId", auth)
        }
    }

    suspend fun retryDispatch(rideId: String) {
        val uid = requireUserId()
        try {
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
            db.runTransaction { tx ->
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
                if (currentRideDriver.isNotBlank() || currentRideStatus !in listOf(
                        RideStatus.SEARCHING_FOR_DRIVER.name,
                        RideStatus.OFFERED.name
                    )
                ) {
                    throw IllegalStateException("Another driver has already accepted this ride.")
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
                null
            }.await()

            recordStatusHistory(
                offer.rideId,
                uid,
                "DRIVER",
                RideStatus.OFFERED.name,
                RideStatus.ACCEPTED.name,
                "Driver accepted atomically"
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
                channel = "PUSH"
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

    suspend fun invalidateCompetingOffers(rideId: String, riderId: String) {
        try {
            val snap = db.collection("ride_offers")
                .whereEqualTo("riderId", riderId)
                .whereEqualTo("rideId", rideId)
                .whereEqualTo("status", OfferStatus.PENDING.name)
                .get()
                .await()
            for (doc in snap.documents) {
                doc.reference.update(
                    mapOf(
                        "status" to OfferStatus.CANCELLED_BY_OTHER.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
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

                rideRef.update(
                    mapOf(
                        "status" to RideStatus.COMPLETED.name,
                        "actualDistanceKm" to actualDistanceKm,
                        "waitingMinutes" to waitingMinutes,
                        "finalFareMinor" to finalFareMinor,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()

                availRef.update(
                    mapOf(
                        "status" to DriverOnlineStatus.ONLINE.name,
                        "activeRideId" to "",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()

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
                    channel = "PUSH"
                )
            } else {
                rideRef.update(
                    mapOf(
                        "status" to targetStatus.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()

                val nextDriverStatus = when (targetStatus) {
                    RideStatus.DRIVER_ARRIVING, RideStatus.ARRIVED -> DriverOnlineStatus.ARRIVING.name
                    RideStatus.IN_PROGRESS -> DriverOnlineStatus.ON_TRIP.name
                    else -> DriverOnlineStatus.ONLINE.name
                }
                availRef.update(
                    mapOf(
                        "status" to nextDriverStatus,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()

                if (targetStatus == RideStatus.ARRIVED) {
                    createNotification(
                        userId = ride.riderId,
                        eventType = "DRIVER_ARRIVED",
                        idempotencyKey = "arrived_${ride.id}",
                        titleAr = "وصل السائق إلى نقطة الانطلاق",
                        titleEn = "Your driver has arrived",
                        bodyAr = "${ride.driverName ?: "السائق"} في انتظارك الآن",
                        bodyEn = "${ride.driverName ?: "Driver"} is waiting at pickup",
                        rideId = ride.id,
                        channel = "PUSH"
                    )
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
        val rideRef = db.collection("rides").document(ride.id)
        return try {
            rideRef.update(
                mapOf(
                    "status" to RideStatus.CANCELLED.name,
                    "cancellationInitiator" to initiatorRole,
                    "cancellationReason" to reason.take(240),
                    "cancellationFeeMinor" to cancellationFeeMinor.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (ride.driverId == uid) {
                db.collection("driver_availability").document(uid).update(
                    mapOf(
                        "status" to DriverOnlineStatus.ONLINE.name,
                        "activeRideId" to "",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            recordStatusHistory(ride.id, uid, initiatorRole, ride.status, RideStatus.CANCELLED.name, reason)
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
        comment: String
    ): Result<Unit> {
        val uid = requireUserId()
        val ratingId = "rat_${ride.id}_${fromRole.lowercase()}"
        val ratingRef = db.collection("ratings").document(ratingId)
        return try {
            ratingRef.set(
                mapOf(
                    "rideId" to ride.id,
                    "fromUserId" to uid,
                    "toUserId" to toUserId,
                    "fromRole" to fromRole,
                    "stars" to stars.coerceIn(1L, 5L),
                    "comment" to comment.trim().take(400),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            val flagField = if (fromRole == "RIDER") "riderRated" else "driverRated"
            db.collection("rides").document(ride.id).update(
                mapOf(flagField to true, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
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
