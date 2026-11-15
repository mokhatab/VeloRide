package com.example.data.firestore

import android.content.Context
import com.example.R
import com.example.domain.model.AppNotification
import com.example.domain.model.AuditLog
import com.example.domain.model.ComplaintRecord
import com.example.domain.model.DriverApprovalStatus
import com.example.domain.model.DriverDocument
import com.example.domain.model.DriverEarning
import com.example.domain.model.DriverPayout
import com.example.domain.model.DriverProfile
import com.example.domain.model.OperationalConfig
import com.example.domain.model.PaymentRecord
import com.example.domain.model.PaymentStatus
import com.example.domain.model.PricingPolicy
import com.example.domain.model.Ride
import com.example.domain.model.UserAccount
import com.example.domain.payment.PaymentGateway
import com.example.domain.payment.PaymentIntentRequest
import com.example.domain.payment.StripePaymentGateway
import com.example.domain.pricing.MarketCatalog
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

class PricingAndAdminRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val paymentGateway: PaymentGateway = StripePaymentGateway()
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google.")
    }

    fun observePricingPolicies(): Flow<List<PricingPolicy>> = flow {
        val col = db.collection("pricing_policies")
        emitAll(
            col.snapshots()
                .map { snap ->
                    val list = snap.toObjects(PricingPolicy::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    list.ifEmpty { MarketCatalog.defaultPoliciesForAllMarkets() }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, col.path, auth)
                    emit(MarketCatalog.defaultPoliciesForAllMarkets())
                }
        )
    }

    fun observeOperationalConfig(): Flow<OperationalConfig> = flow {
        val docRef = db.collection("operational_configs").document("global")
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(OperationalConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?: OperationalConfig(id = "global")
                    } else {
                        OperationalConfig(id = "global")
                    }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.GET, docRef.path, auth)
                    emit(OperationalConfig(id = "global"))
                }
        )
    }

    fun observeNotifications(userId: String): Flow<List<AppNotification>> = flow {
        val query = db.collection("notifications").whereEqualTo("userId", userId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(AppNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "notifications", auth)
                    throw error
                }
        )
    }

    fun observeDriverEarnings(driverId: String): Flow<List<DriverEarning>> = flow {
        val query = db.collection("driver_earnings").whereEqualTo("driverId", driverId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(DriverEarning::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_earnings", auth)
                    throw error
                }
        )
    }

    fun observeDriverPayouts(driverId: String): Flow<List<DriverPayout>> = flow {
        val query = db.collection("driver_payouts").whereEqualTo("driverId", driverId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(DriverPayout::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_payouts", auth)
                    throw error
                }
        )
    }

    fun observeRiderPayments(riderId: String): Flow<List<PaymentRecord>> = flow {
        val query = db.collection("payments").whereEqualTo("riderId", riderId)
        emitAll(
            query.snapshots()
                .map { snap ->
                    snap.toObjects(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "payments", auth)
                    throw error
                }
        )
    }

    /**
     * Initiates a ride payment using StripePaymentGateway.
     * Never fakes a SUCCEEDED payment when Stripe backend is not configured.
     */
    suspend fun initiateRidePayment(
        ride: Ride,
        paymentMethodId: String?,
        paymentMethodType: String
    ): Result<PaymentRecord> {
        val uid = requireUserId()
        val amountMinor = if (ride.finalFareMinor > 0L) ride.finalFareMinor else ride.estimatedFareMinor
        val idempotencyKey = "pay_ride_${ride.id}_${uid.take(8)}"
        val paymentId = "pay_${ride.id}"
        val paymentRef = db.collection("payments").document(paymentId)

        return try {
            val existing = paymentRef.get().await()
            if (existing.exists()) {
                val record = existing.toObject(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)!!
                if (record.status == PaymentStatus.SUCCEEDED.name) {
                    return Result.success(record)
                }
            }

            val gatewayResult = paymentGateway.createAndAuthorizePayment(
                PaymentIntentRequest(
                    rideId = ride.id,
                    riderId = uid,
                    amountMinor = amountMinor,
                    currencyCode = ride.currencyCode,
                    paymentMethodId = paymentMethodId,
                    idempotencyKey = idempotencyKey
                )
            )

            // Client rules permit creating PENDING, REQUIRES_ACTION, or FAILED only.
            // Verified SUCCEEDED confirmations arrive via server webhook / admin reconciliation.
            val persistedStatus = when (gatewayResult.status) {
                PaymentStatus.REQUIRES_ACTION -> PaymentStatus.REQUIRES_ACTION.name
                PaymentStatus.FAILED -> PaymentStatus.FAILED.name
                else -> PaymentStatus.PENDING.name
            }

            val conversion = PricingEngine.convertForSettlement(
                amountMinor = amountMinor,
                originalCurrency = ride.currencyCode,
                settlementCurrency = ride.currencyCode,
                rate = 1.0,
                source = "local_settlement"
            )

            val receiptNumber = "VR-${ride.countryCode}-${ride.id.takeLast(6).uppercase()}"
            val payload = mapOf(
                "rideId" to ride.id,
                "riderId" to uid,
                "driverId" to ride.driverId,
                "idempotencyKey" to idempotencyKey,
                "stripePaymentIntentId" to gatewayResult.stripePaymentIntentId,
                "paymentMethodType" to paymentMethodType,
                "currencyCode" to ride.currencyCode,
                "currencyDecimals" to ride.currencyDecimals,
                "amountMinor" to amountMinor,
                "refundedAmountMinor" to 0L,
                "platformFeeMinor" to (amountMinor * 15L / 100L),
                "taxMinor" to 0L,
                "conversionRate" to conversion.conversionRate,
                "settlementCurrency" to conversion.settlementCurrency,
                "settlementAmountMinor" to conversion.settlementAmountMinor,
                "conversionTimestampMs" to conversion.conversionTimestampMs,
                "conversionSource" to conversion.conversionSource,
                "status" to persistedStatus,
                "receiptNumber" to receiptNumber,
                "failureReason" to gatewayResult.failureReason,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            if (!existing.exists()) {
                paymentRef.set(payload).await()
            }

            // Immutable transaction log entry
            val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            db.collection("payment_transactions").document(txId).set(
                mapOf(
                    "paymentId" to paymentId,
                    "rideId" to ride.id,
                    "riderId" to uid,
                    "eventType" to "PAYMENT_INITIATED",
                    "amountMinor" to amountMinor,
                    "currencyCode" to ride.currencyCode,
                    "stripeEventId" to gatewayResult.stripePaymentIntentId,
                    "idempotencyKey" to idempotencyKey,
                    "status" to persistedStatus,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val saved = paymentRef.get().await()
                .toObject(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: PaymentRecord(id = paymentId, status = persistedStatus, failureReason = gatewayResult.failureReason)
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, paymentRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun submitComplaint(rideId: String, category: String, description: String): Result<Unit> {
        val uid = requireUserId()
        val id = "cmp_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val ref = db.collection("complaints").document(id)
        return try {
            ref.set(
                mapOf(
                    "userId" to uid,
                    "rideId" to rideId,
                    "category" to category.take(60),
                    "description" to description.trim().take(1000),
                    "status" to "OPEN",
                    "adminNotes" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path, auth)
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(notificationId: String) {
        try {
            db.collection("notifications").document(notificationId).update(
                mapOf("isRead" to true, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------------------
    // Admin Control Panel Operations (Protected by isAdmin() in firestore.rules)
    // -------------------------------------------------------------------------

    suspend fun seedDefaultPricingPoliciesIfAdmin(): Result<Unit> {
        val uid = requireUserId()
        return try {
            val defaults = MarketCatalog.defaultPoliciesForAllMarkets()
            for (policy in defaults) {
                val ref = db.collection("pricing_policies").document(policy.id)
                if (!ref.get().await().exists()) {
                    ref.set(
                        mapOf(
                            "countryCode" to policy.countryCode,
                            "countryName" to policy.countryName,
                            "city" to policy.city,
                            "currencyCode" to policy.currencyCode,
                            "currencySymbol" to policy.currencySymbol,
                            "currencyDecimals" to policy.currencyDecimals,
                            "serviceCategory" to policy.serviceCategory,
                            "isTaxiReference" to policy.isTaxiReference,
                            "baseFareMinor" to policy.baseFareMinor,
                            "includedDistanceKm" to policy.includedDistanceKm,
                            "pricePerKmMinor" to policy.pricePerKmMinor,
                            "waitingChargePerMinMinor" to policy.waitingChargePerMinMinor,
                            "minimumFareMinor" to policy.minimumFareMinor,
                            "bookingFeeMinor" to policy.bookingFeeMinor,
                            "taxPercentage" to policy.taxPercentage,
                            "airportSurchargeMinor" to policy.airportSurchargeMinor,
                            "nightSurchargeMinor" to policy.nightSurchargeMinor,
                            "trafficSurchargeMinor" to policy.trafficSurchargeMinor,
                            "surgeMultiplier" to policy.surgeMultiplier,
                            "effectiveStartMs" to policy.effectiveStartMs,
                            "effectiveEndMs" to policy.effectiveEndMs,
                            "pricingVersion" to policy.pricingVersion,
                            "isActive" to policy.isActive,
                            "updatedByAdminId" to uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "pricing_policies", auth)
            Result.failure(e)
        }
    }

    fun observeAllUsersAdmin(): Flow<List<UserAccount>> = flow {
        emitAll(
            db.collection("users").snapshots()
                .map { it.toObjects(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "users", auth)
                    throw error
                }
        )
    }

    fun observeAllDriversAdmin(): Flow<List<DriverProfile>> = flow {
        emitAll(
            db.collection("driver_profiles").snapshots()
                .map { it.toObjects(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_profiles", auth)
                    throw error
                }
        )
    }

    fun observeAllDocumentsAdmin(): Flow<List<DriverDocument>> = flow {
        emitAll(
            db.collection("driver_documents").snapshots()
                .map { it.toObjects(DriverDocument::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_documents", auth)
                    throw error
                }
        )
    }

    fun observeAllRidesAdmin(): Flow<List<Ride>> = flow {
        emitAll(
            db.collection("rides").snapshots()
                .map { snap ->
                    snap.toObjects(Ride::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "rides", auth)
                    throw error
                }
        )
    }

    fun observeAllPaymentsAdmin(): Flow<List<PaymentRecord>> = flow {
        emitAll(
            db.collection("payments").snapshots()
                .map { it.toObjects(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "payments", auth)
                    throw error
                }
        )
    }

    fun observeAllEarningsAdmin(): Flow<List<DriverEarning>> = flow {
        emitAll(
            db.collection("driver_earnings").snapshots()
                .map { it.toObjects(DriverEarning::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_earnings", auth)
                    throw error
                }
        )
    }

    fun observeAllComplaintsAdmin(): Flow<List<ComplaintRecord>> = flow {
        emitAll(
            db.collection("complaints").snapshots()
                .map { it.toObjects(ComplaintRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "complaints", auth)
                    throw error
                }
        )
    }

    fun observeAuditLogsAdmin(): Flow<List<AuditLog>> = flow {
        emitAll(
            db.collection("audit_logs").snapshots()
                .map { snap ->
                    snap.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "audit_logs", auth)
                    throw error
                }
        )
    }

    suspend fun adminSetDriverApproval(
        driverId: String,
        newStatus: DriverApprovalStatus,
        reason: String? = null
    ): Result<Unit> {
        val adminId = requireUserId()
        return try {
            val profileRef = db.collection("driver_profiles").document(driverId)
            val prevStatus = profileRef.get().await().getString("approvalStatus") ?: "UNKNOWN"

            val updates = mutableMapOf<String, Any>(
                "approvalStatus" to newStatus.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (reason != null) updates["rejectionReason"] = reason.take(300)
            profileRef.update(updates).await()

            val availRef = db.collection("driver_availability").document(driverId)
            if (availRef.get().await().exists()) {
                val availUpdates = mutableMapOf<String, Any>(
                    "approvalStatus" to newStatus.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (newStatus != DriverApprovalStatus.APPROVED) {
                    availUpdates["status"] = "OFFLINE"
                }
                availRef.update(availUpdates).await()
            }

            writeAuditLog(
                actorId = adminId,
                action = "DRIVER_APPROVAL_CHANGE",
                targetCollection = "driver_profiles",
                targetId = driverId,
                previousValue = prevStatus,
                newValue = "${newStatus.name} ${reason.orEmpty()}".trim()
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "driver_profiles/$driverId", auth)
            Result.failure(e)
        }
    }

    suspend fun adminVerifyDocument(docId: String, status: String, notes: String): Result<Unit> {
        val adminId = requireUserId()
        return try {
            val ref = db.collection("driver_documents").document(docId)
            ref.update(
                mapOf(
                    "verificationStatus" to status,
                    "reviewNotes" to notes.take(300),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAuditLog(adminId, "DOCUMENT_VERIFICATION", "driver_documents", docId, "PENDING", "$status: $notes")
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "driver_documents/$docId", auth)
            Result.failure(e)
        }
    }

    suspend fun adminSetUserSuspended(userId: String, suspended: Boolean): Result<Unit> {
        val adminId = requireUserId()
        return try {
            db.collection("users").document(userId).update(
                mapOf(
                    "isSuspended" to suspended,
                    "isActive" to !suspended,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAuditLog(adminId, "USER_SUSPEND_TOGGLE", "users", userId, (!suspended).toString(), suspended.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$userId", auth)
            Result.failure(e)
        }
    }

    suspend fun adminUpdatePricingPolicy(
        policy: PricingPolicy,
        newBaseFareMinor: Long,
        newPricePerKmMinor: Long,
        newWaitingMinor: Long,
        newMinFareMinor: Long,
        newSurgeMultiplier: Double
    ): Result<Unit> {
        val adminId = requireUserId()
        val ref = db.collection("pricing_policies").document(policy.id)
        return try {
            val exists = ref.get().await().exists()
            val nextVersion = policy.pricingVersion + 1L
            val prevSummary = "v${policy.pricingVersion} base=${policy.baseFareMinor} km=${policy.pricePerKmMinor} surge=${policy.surgeMultiplier}"
            val newSummary = "v$nextVersion base=$newBaseFareMinor km=$newPricePerKmMinor surge=$newSurgeMultiplier"

            if (!exists) {
                ref.set(
                    mapOf(
                        "countryCode" to policy.countryCode,
                        "countryName" to policy.countryName,
                        "city" to policy.city,
                        "currencyCode" to policy.currencyCode,
                        "currencySymbol" to policy.currencySymbol,
                        "currencyDecimals" to policy.currencyDecimals,
                        "serviceCategory" to policy.serviceCategory,
                        "isTaxiReference" to policy.isTaxiReference,
                        "baseFareMinor" to newBaseFareMinor,
                        "includedDistanceKm" to policy.includedDistanceKm,
                        "pricePerKmMinor" to newPricePerKmMinor,
                        "waitingChargePerMinMinor" to newWaitingMinor,
                        "minimumFareMinor" to newMinFareMinor,
                        "bookingFeeMinor" to policy.bookingFeeMinor,
                        "taxPercentage" to policy.taxPercentage,
                        "airportSurchargeMinor" to policy.airportSurchargeMinor,
                        "nightSurchargeMinor" to policy.nightSurchargeMinor,
                        "trafficSurchargeMinor" to policy.trafficSurchargeMinor,
                        "surgeMultiplier" to newSurgeMultiplier,
                        "effectiveStartMs" to policy.effectiveStartMs,
                        "effectiveEndMs" to policy.effectiveEndMs,
                        "pricingVersion" to nextVersion,
                        "isActive" to policy.isActive,
                        "updatedByAdminId" to adminId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.update(
                    mapOf(
                        "baseFareMinor" to newBaseFareMinor,
                        "pricePerKmMinor" to newPricePerKmMinor,
                        "waitingChargePerMinMinor" to newWaitingMinor,
                        "minimumFareMinor" to newMinFareMinor,
                        "surgeMultiplier" to newSurgeMultiplier.coerceIn(1.0, 10.0),
                        "pricingVersion" to nextVersion,
                        "updatedByAdminId" to adminId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            writeAuditLog(adminId, "PRICING_UPDATE", "pricing_policies", policy.id, prevSummary, newSummary)
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path, auth)
            Result.failure(e)
        }
    }

    suspend fun adminUpdateOperationalConfig(config: OperationalConfig): Result<Unit> {
        val adminId = requireUserId()
        val ref = db.collection("operational_configs").document("global")
        return try {
            val payload = mapOf(
                "dispatchRound1RadiusKm" to config.dispatchRound1RadiusKm,
                "dispatchRound2RadiusKm" to config.dispatchRound2RadiusKm,
                "dispatchRound3RadiusKm" to config.dispatchRound3RadiusKm,
                "offerDurationSeconds" to config.offerDurationSeconds,
                "gpsFreshnessMaxSeconds" to config.gpsFreshnessMaxSeconds,
                "cancellationFreeWindowSeconds" to config.cancellationFreeWindowSeconds,
                "cancellationFeeMinor" to config.cancellationFeeMinor,
                "platformCommissionPercent" to config.platformCommissionPercent,
                "updatedByAdminId" to adminId,
                "createdAt" to (config.createdAt ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            writeAuditLog(
                adminId,
                "OPERATIONAL_CONFIG_UPDATE",
                "operational_configs",
                "global",
                "config",
                "r1=${config.dispatchRound1RadiusKm}km, r2=${config.dispatchRound2RadiusKm}km, r3=${config.dispatchRound3RadiusKm}km, offer=${config.offerDurationSeconds}s"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, ref.path, auth)
            Result.failure(e)
        }
    }

    suspend fun adminRecordManualPayout(earning: DriverEarning, reference: String): Result<Unit> {
        val adminId = requireUserId()
        val payoutId = "pay_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        return try {
            db.collection("driver_payouts").document(payoutId).set(
                mapOf(
                    "driverId" to earning.driverId,
                    "adminId" to adminId,
                    "amountMinor" to earning.netEarningsMinor.coerceAtLeast(1L),
                    "currencyCode" to earning.currencyCode,
                    "payoutMethod" to "MANUAL_BANK_TRANSFER",
                    "payoutReference" to reference.ifBlank { "MAN-$payoutId" },
                    "status" to "COMPLETED",
                    "notes" to "Manual bank settlement recorded by admin",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("driver_earnings").document(earning.id).update(
                mapOf(
                    "payoutStatus" to "PAID_MANUAL",
                    "payoutId" to payoutId,
                    "payoutReference" to reference.ifBlank { "MAN-$payoutId" },
                    "payoutDateMs" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAuditLog(adminId, "MANUAL_PAYOUT", "driver_earnings", earning.id, earning.payoutStatus, "PAID_MANUAL ($payoutId)")
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "driver_payouts/$payoutId", auth)
            Result.failure(e)
        }
    }

    suspend fun adminResolveComplaint(complaintId: String, status: String, notes: String): Result<Unit> {
        val adminId = requireUserId()
        return try {
            db.collection("complaints").document(complaintId).update(
                mapOf(
                    "status" to status,
                    "adminNotes" to notes.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAuditLog(adminId, "COMPLAINT_UPDATE", "complaints", complaintId, "OPEN", "$status: $notes")
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "complaints/$complaintId", auth)
            Result.failure(e)
        }
    }

    private suspend fun writeAuditLog(
        actorId: String,
        action: String,
        targetCollection: String,
        targetId: String,
        previousValue: String,
        newValue: String
    ) {
        val logId = "aud_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        db.collection("audit_logs").document(logId).set(
            mapOf(
                "actorId" to actorId,
                "actorEmail" to (auth.currentUser?.email ?: actorId),
                "action" to action,
                "targetCollection" to targetCollection,
                "targetId" to targetId,
                "previousValueSummary" to previousValue.take(500),
                "newValueSummary" to newValue.take(500),
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }
}
