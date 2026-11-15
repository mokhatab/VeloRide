package com.example.core.push

import android.content.Context
import android.util.Log
import com.example.data.firestore.FcmTokenRepository
import com.example.domain.model.AccountMode
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.tasks.await

data class FcmSyncResult(
    val token: String = "",
    val subscribedTopics: List<String> = emptyList(),
    val syncedToFirestore: Boolean = false,
    val autoInitEnabled: Boolean = true,
    val statusNote: String = ""
)

class FcmPushCoordinator(private val context: Context) {

    @Volatile
    private var lastSyncedToken: String? = null

    fun cacheToken(token: String) {
        if (token.isNotBlank()) {
            lastSyncedToken = token
        }
    }

    fun setAutoInitEnabled(enabled: Boolean) {
        runCatching {
            Firebase.messaging.isAutoInitEnabled = enabled
        }
    }

    suspend fun fetchCurrentToken(): String? {
        val fetched = runCatching {
            Firebase.messaging.token.await()
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (fetched != null) {
            lastSyncedToken = fetched
        }
        return fetched ?: lastSyncedToken
    }

    suspend fun syncTopics(
        uid: String,
        activeMode: String,
        countryCode: String,
        notificationsEnabled: Boolean
    ): List<String> {
        val userTopic = userTopicName(uid)
        val driverZoneTopic = driverZoneTopicName(countryCode)
        val activeTopics = mutableListOf<String>()

        if (notificationsEnabled) {
            runCatching { Firebase.messaging.subscribeToTopic(userTopic).await() }
                .onSuccess { activeTopics.add(userTopic) }
                .onFailure { Log.w(TAG, "Topic subscribe failed [$userTopic]: ${it.message}") }

            if (activeMode == AccountMode.DRIVER.name) {
                runCatching { Firebase.messaging.subscribeToTopic(driverZoneTopic).await() }
                    .onSuccess { activeTopics.add(driverZoneTopic) }
                    .onFailure { Log.w(TAG, "Topic subscribe failed [$driverZoneTopic]: ${it.message}") }
            } else {
                runCatching { Firebase.messaging.unsubscribeFromTopic(driverZoneTopic).await() }
            }
        } else {
            runCatching { Firebase.messaging.unsubscribeFromTopic(userTopic).await() }
            runCatching { Firebase.messaging.unsubscribeFromTopic(driverZoneTopic).await() }
        }

        return activeTopics
    }

    suspend fun syncRegistration(
        uid: String,
        activeMode: String,
        countryCode: String,
        preferredLanguage: String,
        notificationsEnabled: Boolean,
        tokenRepository: FcmTokenRepository,
        explicitToken: String? = null
    ): FcmSyncResult {
        TripStatusNotificationHelper.ensureChannel(context)
        setAutoInitEnabled(notificationsEnabled)

        val token = explicitToken?.takeIf { it.isNotBlank() } ?: fetchCurrentToken()
        val topics = syncTopics(uid, activeMode, countryCode, notificationsEnabled)

        if (token.isNullOrBlank()) {
            return FcmSyncResult(
                token = "",
                subscribedTopics = topics,
                syncedToFirestore = false,
                autoInitEnabled = notificationsEnabled,
                statusNote = "FCM registration waiting for Google Play Services token"
            )
        }

        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")

        val saveResult = tokenRepository.upsertFcmToken(
            token = token,
            activeMode = activeMode,
            preferredLanguage = preferredLanguage,
            notificationsEnabled = notificationsEnabled,
            appVersion = appVersion
        )

        return if (saveResult.isSuccess) {
            FcmSyncResult(
                token = token,
                subscribedTopics = topics,
                syncedToFirestore = true,
                autoInitEnabled = notificationsEnabled,
                statusNote = "FCM active • ${topics.size} topic(s) subscribed"
            )
        } else {
            FcmSyncResult(
                token = token,
                subscribedTopics = topics,
                syncedToFirestore = false,
                autoInitEnabled = notificationsEnabled,
                statusNote = saveResult.exceptionOrNull()?.localizedMessage ?: "FCM token sync pending"
            )
        }
    }

    companion object {
        private const val TAG = "VeloFcmCoordinator"

        fun userTopicName(uid: String): String {
            val clean = uid.replace(Regex("[^a-zA-Z0-9\\-_.~%]"), "_").take(80)
            return "veloride_user_$clean"
        }

        fun driverZoneTopicName(countryCode: String): String {
            val cleanCountry = countryCode.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "eg" }
            return "veloride_drivers_$cleanCountry"
        }
    }
}
