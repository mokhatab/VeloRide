package com.example.data.firestore

import android.content.Context
import com.example.R
import com.example.domain.mapping.GeoPoint
import com.example.domain.model.DriverApprovalStatus
import com.example.domain.model.DriverAvailability
import com.example.domain.model.DriverDocument
import com.example.domain.model.DriverDocumentType
import com.example.domain.model.DriverOnlineStatus
import com.example.domain.model.DriverProfile
import com.example.domain.model.VehicleCategory
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

class DriverRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Driver must be signed in with Google.")
    }

    fun observeDriverProfile(driverId: String): Flow<DriverProfile?> = flow {
        val docRef = db.collection("driver_profiles").document(driverId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == driverId) {
                        handleFirestoreError(fe ?: error, OperationType.GET, docRef.path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeDriverDocuments(driverId: String): Flow<List<DriverDocument>> = flow {
        val col = db.collection("driver_documents").whereEqualTo("driverId", driverId)
        emitAll(
            col.snapshots()
                .map { it.toObjects(DriverDocument::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_documents", auth)
                    throw error
                }
        )
    }

    fun observeOwnAvailability(driverId: String): Flow<DriverAvailability?> = flow {
        val docRef = db.collection("driver_availability").document(driverId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(DriverAvailability::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
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

    fun observeAssignedDriverAvailability(driverId: String): Flow<DriverAvailability?> =
        observeOwnAvailability(driverId)

    /**
     * Observes online & approved drivers for dispatch & map visualization.
     * Query matches firestore.rules allow list: approvalStatus == APPROVED && status == ONLINE.
     */
    fun observeOnlineApprovedDrivers(): Flow<List<DriverAvailability>> = flow {
        val query = db.collection("driver_availability")
            .whereEqualTo("approvalStatus", DriverApprovalStatus.APPROVED.name)
            .whereEqualTo("status", DriverOnlineStatus.ONLINE.name)
        emitAll(
            query.snapshots()
                .map { it.toObjects(DriverAvailability::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, "driver_availability", auth)
                    throw error
                }
        )
    }

    suspend fun getEligibleOnlineDrivers(
        category: VehicleCategory,
        gpsFreshnessMaxSeconds: Long
    ): List<DriverAvailability> {
        return try {
            val snap = db.collection("driver_availability")
                .whereEqualTo("approvalStatus", DriverApprovalStatus.APPROVED.name)
                .whereEqualTo("status", DriverOnlineStatus.ONLINE.name)
                .whereEqualTo("vehicleCategory", category.name)
                .get()
                .await()
            val minValidTs = System.currentTimeMillis() - (gpsFreshnessMaxSeconds * 1000L)
            snap.toObjects(DriverAvailability::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                .filter { it.activeRideId.isBlank() && it.locationTimestampMs >= minValidTs }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "driver_availability", auth)
            emptyList()
        }
    }

    suspend fun saveDriverOnboarding(
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
    ): Result<DriverProfile> {
        val uid = requireUserId()
        val email = auth.currentUser?.email ?: "$uid@veloride.app"
        val profileRef = db.collection("driver_profiles").document(uid)
        val availRef = db.collection("driver_availability").document(uid)

        return try {
            val existingSnap = profileRef.get().await()
            val targetStatus = if (submitForReview) {
                DriverApprovalStatus.UNDER_REVIEW.name
            } else {
                DriverApprovalStatus.DOCUMENTS_PENDING.name
            }

            if (!existingSnap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "fullName" to fullName.trim(),
                    "mobileNumber" to mobileNumber.trim(),
                    "email" to email,
                    "nationalId" to nationalId.trim(),
                    "licenceNumber" to licenceNumber.trim(),
                    "licenceExpiry" to licenceExpiry.trim(),
                    "profilePhotoUrl" to profilePhotoUrl,
                    "vehicleMake" to vehicleMake.trim(),
                    "vehicleModel" to vehicleModel.trim(),
                    "vehicleYear" to vehicleYear,
                    "vehicleCategory" to vehicleCategory.name,
                    "vehicleColor" to vehicleColor.trim(),
                    "registrationNumber" to registrationNumber.trim(),
                    "plateNumber" to plateNumber.trim(),
                    "vehiclePhotoUrls" to vehiclePhotoUrls.take(5),
                    "approvalStatus" to targetStatus,
                    "driverRatingAvg" to 5.0,
                    "driverRatingCount" to 0L,
                    "totalTrips" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                profileRef.set(payload).await()

                val availPayload = mapOf(
                    "driverId" to uid,
                    "displayName" to fullName.trim().substringBefore(" ").ifBlank { "Driver" },
                    "vehicleMake" to vehicleMake.trim(),
                    "vehicleModel" to vehicleModel.trim(),
                    "vehicleColor" to vehicleColor.trim(),
                    "plateNumber" to plateNumber.trim(),
                    "vehicleCategory" to vehicleCategory.name,
                    "approvalStatus" to targetStatus,
                    "status" to DriverOnlineStatus.OFFLINE.name,
                    "latitude" to 30.0444,
                    "longitude" to 31.2357,
                    "heading" to 0.0,
                    "locationTimestampMs" to System.currentTimeMillis(),
                    "activeRideId" to "",
                    "countryCode" to "EG",
                    "city" to "Cairo",
                    "driverRatingAvg" to 5.0,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                availRef.set(availPayload).await()
            } else {
                val existingStatus = existingSnap.getString("approvalStatus") ?: DriverApprovalStatus.REGISTERED.name
                val nextStatus = if (existingStatus == DriverApprovalStatus.APPROVED.name) {
                    DriverApprovalStatus.APPROVED.name
                } else {
                    targetStatus
                }
                val updates = mutableMapOf<String, Any>(
                    "fullName" to fullName.trim(),
                    "mobileNumber" to mobileNumber.trim(),
                    "nationalId" to nationalId.trim(),
                    "licenceNumber" to licenceNumber.trim(),
                    "licenceExpiry" to licenceExpiry.trim(),
                    "vehicleMake" to vehicleMake.trim(),
                    "vehicleModel" to vehicleModel.trim(),
                    "vehicleYear" to vehicleYear,
                    "vehicleCategory" to vehicleCategory.name,
                    "vehicleColor" to vehicleColor.trim(),
                    "registrationNumber" to registrationNumber.trim(),
                    "plateNumber" to plateNumber.trim(),
                    "vehiclePhotoUrls" to vehiclePhotoUrls.take(5),
                    "approvalStatus" to nextStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!profilePhotoUrl.isNullOrBlank()) updates["profilePhotoUrl"] = profilePhotoUrl
                profileRef.update(updates).await()
            }

            // Flag user as having a driver profile
            db.collection("users").document(uid).update(
                mapOf("hasDriverProfile" to true, "updatedAt" to FieldValue.serverTimestamp())
            ).await()

            val updated = profileRef.get().await()
                .toObject(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: DriverProfile(userId = uid, fullName = fullName)
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, profileRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun uploadDriverDocument(
        documentType: DriverDocumentType,
        countryCode: String,
        documentNumber: String,
        expiryDate: String,
        imageUri: String
    ): Result<Unit> {
        val uid = requireUserId()
        val docId = "${uid}_${documentType.name.lowercase()}"
        val docRef = db.collection("driver_documents").document(docId)
        return try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val payload = mapOf(
                    "driverId" to uid,
                    "documentType" to documentType.name,
                    "countryCode" to countryCode.uppercase().take(4),
                    "documentNumber" to documentNumber.trim(),
                    "expiryDate" to expiryDate.trim(),
                    "imageUri" to imageUri.take(500),
                    "verificationStatus" to "PENDING",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                docRef.update(
                    mapOf(
                        "documentNumber" to documentNumber.trim(),
                        "expiryDate" to expiryDate.trim(),
                        "imageUri" to imageUri.take(500),
                        "verificationStatus" to "PENDING",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun setDriverOnlineStatus(
        goOnline: Boolean,
        point: GeoPoint,
        countryCode: String,
        city: String
    ): Result<Unit> {
        val uid = requireUserId()
        val availRef = db.collection("driver_availability").document(uid)
        val profileRef = db.collection("driver_profiles").document(uid)

        return try {
            val profile = profileRef.get().await()
                .toObject(DriverProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Driver profile not found."))

            if (goOnline && profile.approvalStatus != DriverApprovalStatus.APPROVED.name) {
                return Result.failure(
                    IllegalStateException("Only APPROVED drivers can go online (current: ${profile.approvalStatus}).")
                )
            }

            val targetStatus = if (goOnline) DriverOnlineStatus.ONLINE.name else DriverOnlineStatus.OFFLINE.name
            val existingAvail = availRef.get().await()
            val nowMs = System.currentTimeMillis()
            val prevLocTs = existingAvail.getLong("locationTimestampMs") ?: 0L
            val safeTs = maxOf(nowMs, prevLocTs)

            if (!existingAvail.exists()) {
                availRef.set(
                    mapOf(
                        "driverId" to uid,
                        "displayName" to profile.fullName.substringBefore(" ").ifBlank { "Driver" },
                        "vehicleMake" to profile.vehicleMake,
                        "vehicleModel" to profile.vehicleModel,
                        "vehicleColor" to profile.vehicleColor,
                        "plateNumber" to profile.plateNumber,
                        "vehicleCategory" to profile.vehicleCategory,
                        "approvalStatus" to profile.approvalStatus,
                        "status" to targetStatus,
                        "latitude" to point.latitude,
                        "longitude" to point.longitude,
                        "heading" to 0.0,
                        "locationTimestampMs" to safeTs,
                        "activeRideId" to "",
                        "countryCode" to countryCode,
                        "city" to city,
                        "driverRatingAvg" to profile.driverRatingAvg,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                availRef.update(
                    mapOf(
                        "status" to targetStatus,
                        "latitude" to point.latitude,
                        "longitude" to point.longitude,
                        "locationTimestampMs" to safeTs,
                        "countryCode" to countryCode,
                        "city" to city,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, availRef.path, auth)
            Result.failure(e)
        }
    }

    /**
     * Monotonic GPS update: rejects invalid coordinates or delayed older timestamps
     * both locally and via Firestore security rules.
     */
    suspend fun updateLiveLocation(
        point: GeoPoint,
        heading: Double,
        speedKmh: Double,
        locationTimestampMs: Long,
        activeRideId: String = ""
    ): Result<Unit> {
        if (!point.isValid()) {
            return Result.failure(IllegalArgumentException("Invalid GPS coordinates"))
        }
        val uid = requireUserId()
        val availRef = db.collection("driver_availability").document(uid)
        return try {
            val snap = availRef.get().await()
            if (!snap.exists()) return Result.failure(IllegalStateException("Availability record missing"))
            val prevTs = snap.getLong("locationTimestampMs") ?: 0L
            if (locationTimestampMs < prevTs) {
                return Result.failure(IllegalStateException("Outdated GPS update rejected ($locationTimestampMs < $prevTs)"))
            }
            availRef.update(
                mapOf(
                    "latitude" to point.latitude,
                    "longitude" to point.longitude,
                    "heading" to heading.coerceIn(0.0, 360.0),
                    "locationTimestampMs" to locationTimestampMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            // Record historical GPS breadcrumb with controlled access
            val histId = "loc_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            db.collection("driver_location_history").document(histId).set(
                mapOf(
                    "driverId" to uid,
                    "rideId" to activeRideId,
                    "latitude" to point.latitude,
                    "longitude" to point.longitude,
                    "speedKmh" to speedKmh.coerceIn(0.0, 300.0),
                    "recordedAtMs" to locationTimestampMs,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, availRef.path, auth)
            Result.failure(e)
        }
    }
}
