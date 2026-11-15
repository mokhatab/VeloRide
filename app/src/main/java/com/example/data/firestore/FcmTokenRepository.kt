package com.example.data.firestore

import android.content.Context
import android.os.Build
import com.example.R
import com.example.domain.model.UserFcmToken
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
import java.security.MessageDigest

class FcmTokenRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before managing FCM tokens.")
    }

    fun observeUserFcmTokens(userId: String): Flow<List<UserFcmToken>> = flow {
        val col = db.collection("users").document(userId).collection("fcm_tokens")
        emitAll(
            col.snapshots()
                .map { snap ->
                    snap.toObjects(UserFcmToken::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val fe = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fe == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(fe ?: error, OperationType.LIST, col.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertFcmToken(
        token: String,
        activeMode: String,
        preferredLanguage: String,
        notificationsEnabled: Boolean,
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120),
        appVersion: String = "1.0"
    ): Result<UserFcmToken> {
        val uid = requireUserId()
        val trimmedToken = token.trim()
        if (trimmedToken.length < 10) {
            return Result.failure(IllegalArgumentException("FCM token must be at least 10 characters."))
        }

        val tokenId = deterministicTokenId(trimmedToken)
        val docRef = db.collection("users").document(uid)
            .collection("fcm_tokens").document(tokenId)

        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "token" to trimmedToken.take(512),
                        "deviceModel" to deviceModel.ifBlank { "Android Device" }.take(120),
                        "appVersion" to appVersion.ifBlank { "1.0" }.take(40),
                        "activeMode" to activeMode,
                        "preferredLanguage" to preferredLanguage,
                        "notificationsEnabled" to notificationsEnabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "token" to trimmedToken.take(512),
                        "platform" to "ANDROID",
                        "deviceModel" to deviceModel.ifBlank { "Android Device" }.take(120),
                        "appVersion" to appVersion.ifBlank { "1.0" }.take(40),
                        "activeMode" to activeMode,
                        "preferredLanguage" to preferredLanguage,
                        "notificationsEnabled" to notificationsEnabled,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val saved = docRef.get().await()
                .toObject(UserFcmToken::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserFcmToken(
                    id = tokenId,
                    userId = uid,
                    token = trimmedToken,
                    deviceModel = deviceModel,
                    appVersion = appVersion,
                    activeMode = activeMode,
                    preferredLanguage = preferredLanguage,
                    notificationsEnabled = notificationsEnabled
                )
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateNotificationPreference(
        tokenId: String,
        notificationsEnabled: Boolean
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("fcm_tokens").document(tokenId)
        return try {
            docRef.update(
                mapOf(
                    "notificationsEnabled" to notificationsEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun deleteFcmToken(tokenId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("fcm_tokens").document(tokenId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            Result.failure(e)
        }
    }

    companion object {
        fun deterministicTokenId(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
            return "tok_$hex"
        }
    }
}
