package com.example.data.firestore

import android.content.Context
import com.example.R
import com.example.domain.model.AccountMode
import com.example.domain.model.DriverOnlineStatus
import com.example.domain.model.PaymentMethodRecord
import com.example.domain.model.SavedLocation
import com.example.domain.model.UserAccount
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

class UserRepository(
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
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserAccount(userId: String): Flow<UserAccount?> = flow {
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserAccount(userId: String): UserAccount? {
        val docRef = db.collection("users").document(userId)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(UserAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            throw e
        }
    }

    suspend fun registerOrEnsureUser(
        fullName: String,
        mobileNumber: String,
        countryCode: String,
        preferredLanguage: String,
        wantsDriverOnboarding: Boolean
    ): UserAccount {
        val uid = requireUserId()
        val user = auth.currentUser!!
        val docRef = db.collection("users").document(uid)
        val existing = getUserAccount(uid)
        if (existing != null) return existing

        val payload = mapOf(
            "userId" to uid,
            "fullName" to fullName.trim().ifBlank { user.displayName ?: "VeloRide User" },
            "mobileNumber" to mobileNumber.trim(),
            "email" to (user.email ?: "$uid@veloride.app"),
            "photoUrl" to user.photoUrl?.toString(),
            "countryCode" to countryCode.uppercase().take(4),
            "preferredLanguage" to preferredLanguage,
            "activeMode" to AccountMode.RIDER.name,
            "hasRiderProfile" to true,
            "hasDriverProfile" to wantsDriverOnboarding,
            "isActive" to true,
            "isSuspended" to false,
            "emailVerified" to user.isEmailVerified,
            "phoneVerified" to mobileNumber.isNotBlank(),
            "riderRatingAvg" to 5.0,
            "riderRatingCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>

        try {
            docRef.set(payload).await()
            return getUserAccount(uid) ?: UserAccount(userId = uid, fullName = fullName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateProfile(
        fullName: String,
        mobileNumber: String,
        countryCode: String,
        preferredLanguage: String,
        photoUrl: String?
    ) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val updates = mutableMapOf<String, Any>(
            "fullName" to fullName.trim(),
            "mobileNumber" to mobileNumber.trim(),
            "countryCode" to countryCode.uppercase().take(4),
            "preferredLanguage" to preferredLanguage,
            "phoneVerified" to mobileNumber.isNotBlank(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!photoUrl.isNullOrBlank()) {
            updates["photoUrl"] = photoUrl.take(500)
        }
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updatePreferredLanguage(languageCode: String) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        try {
            docRef.update(
                mapOf(
                    "preferredLanguage" to languageCode,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun markHasDriverProfile() {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        try {
            docRef.update(
                mapOf(
                    "hasDriverProfile" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    /**
     * Enforces Section 4 Switching Rules:
     * - Rider -> Driver requires APPROVED driver profile (enforced on both client & Firestore rules).
     * - Driver -> Rider requires driver to be OFFLINE and not on an active trip.
     */
    suspend fun switchAccountMode(targetMode: AccountMode): Result<Unit> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val driverProfileRef = db.collection("driver_profiles").document(uid)
        val availabilityRef = db.collection("driver_availability").document(uid)

        return try {
            if (targetMode == AccountMode.DRIVER) {
                val driverSnap = driverProfileRef.get().await()
                val approval = driverSnap.getString("approvalStatus")
                if (!driverSnap.exists() || approval != "APPROVED") {
                    return Result.failure(
                        IllegalStateException("Driver approval required before switching to Driver mode.")
                    )
                }
            } else {
                val availSnap = availabilityRef.get().await()
                if (availSnap.exists()) {
                    val status = availSnap.getString("status") ?: DriverOnlineStatus.OFFLINE.name
                    val activeRide = availSnap.getString("activeRideId") ?: ""
                    if (activeRide.isNotBlank() || status in listOf("ACCEPTED", "ARRIVING", "ON_TRIP", "OFFER_RECEIVED")) {
                        return Result.failure(
                            IllegalStateException("Cannot switch to Rider mode during an active ride or offer.")
                        )
                    }
                    if (status == DriverOnlineStatus.ONLINE.name) {
                        return Result.failure(
                            IllegalStateException("Please go offline before switching to Rider mode.")
                        )
                    }
                }
            }

            userRef.update(
                mapOf(
                    "activeMode" to targetMode.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path, auth)
            Result.failure(e)
        }
    }

    fun observeSavedLocations(userId: String): Flow<List<SavedLocation>> = flow {
        val col = db.collection("users").document(userId).collection("saved_locations")
        emitAll(
            col.snapshots()
                .map { it.toObjects(SavedLocation::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, col.path, auth)
                    throw error
                }
        )
    }

    suspend fun addSavedLocation(
        label: String,
        address: String,
        latitude: Double,
        longitude: Double,
        countryCode: String,
        city: String
    ) {
        val uid = requireUserId()
        val id = "loc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("saved_locations").document(id)
        val payload = mapOf(
            "userId" to uid,
            "label" to label.trim().take(60),
            "address" to address.trim().take(240),
            "latitude" to latitude,
            "longitude" to longitude,
            "countryCode" to countryCode.uppercase().take(4),
            "city" to city.trim().take(80),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteSavedLocation(locationId: String) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("saved_locations").document(locationId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    fun observePaymentMethods(userId: String): Flow<List<PaymentMethodRecord>> = flow {
        val col = db.collection("users").document(userId).collection("payment_methods")
        emitAll(
            col.snapshots()
                .map { it.toObjects(PaymentMethodRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    handleFirestoreError(fe ?: error, OperationType.LIST, col.path, auth)
                    throw error
                }
        )
    }

    suspend fun addTokenizedPaymentMethod(
        brand: String,
        last4: String,
        expiryMonth: Long,
        expiryYear: Long,
        stripeTokenId: String,
        methodType: String = "CARD"
    ) {
        val uid = requireUserId()
        require(last4.matches(Regex("^[0-9]{4}$"))) { "Only last 4 digits may be stored; never raw card numbers." }
        val id = "pm_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val docRef = db.collection("users").document(uid).collection("payment_methods").document(id)
        val payload = mapOf(
            "userId" to uid,
            "brand" to brand.take(30),
            "last4" to last4,
            "expiryMonth" to expiryMonth.coerceIn(1L, 12L),
            "expiryYear" to expiryYear.coerceIn(2024L, 2099L),
            "stripePaymentMethodId" to stripeTokenId.ifBlank { "pm_tok_$id" },
            "methodType" to methodType,
            "isDefault" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun isUserAdmin(userId: String): Boolean {
        val email = auth.currentUser?.email
        val verified = auth.currentUser?.isEmailVerified == true
        if (verified && email == "moh.khattab23@gmail.com") return true
        val docRef = db.collection("admins").document(userId)
        return try {
            docRef.get().await().exists()
        } catch (_: Exception) {
            false
        }
    }
}
