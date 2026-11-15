package com.example.core.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface OfferNotificationAction {
    val offerId: String

    data class Accept(override val offerId: String) : OfferNotificationAction
    data class Reject(override val offerId: String) : OfferNotificationAction
}

object RideOfferActionBus {
    private val _actions = MutableSharedFlow<OfferNotificationAction>(extraBufferCapacity = 32)
    val actions: SharedFlow<OfferNotificationAction> = _actions.asSharedFlow()

    fun dispatch(action: OfferNotificationAction) {
        _actions.tryEmit(action)
    }
}

/**
 * Handles status-bar notification action taps ("ACCEPT" / "DECLINE") during the 30-second
 * dispatch window, immediately silencing the alert tone and routing the action to VeloRideViewModel.
 */
class RideOfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val offerId = intent.getStringExtra(EXTRA_OFFER_ID).orEmpty()
        if (offerId.isBlank()) return

        // Stop sound, vibration, and status bar notification in the same millisecond
        RideAlertPlayer.stopOfferAlertStatic(context, offerId)

        when (intent.action) {
            ACTION_ACCEPT_OFFER -> {
                RideOfferActionBus.dispatch(OfferNotificationAction.Accept(offerId))
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_OFFER_ID, offerId)
                }
                runCatching { context.startActivity(launchIntent) }
            }
            ACTION_REJECT_OFFER -> {
                RideOfferActionBus.dispatch(OfferNotificationAction.Reject(offerId))
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT_OFFER = "com.example.veloride.ACTION_ACCEPT_OFFER"
        const val ACTION_REJECT_OFFER = "com.example.veloride.ACTION_REJECT_OFFER"
        const val EXTRA_OFFER_ID = "extra_offer_id"
    }
}
