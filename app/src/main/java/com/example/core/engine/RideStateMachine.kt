package com.example.core.engine

import com.example.domain.model.Ride
import com.example.domain.model.RideStatus
import java.util.Collections

sealed interface RideEvent {
    data object StartSearch : RideEvent
    data class DispatchOffer(val round: Int) : RideEvent
    data class DriverAccept(val driverId: String) : RideEvent
    data object DriverMarkArriving : RideEvent
    data object DriverArrive : RideEvent
    data object StartTrip : RideEvent
    data object CompleteTrip : RideEvent
    data object MarkNoDriver : RideEvent
    data object RetrySearch : RideEvent
    data object ExpireRide : RideEvent
    data class Cancel(val actorRole: String, val reason: String) : RideEvent
}

data class StateTransitionRecord(
    val rideId: String,
    val fromStatus: RideStatus,
    val toStatus: RideStatus,
    val eventName: String,
    val actorNote: String,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed interface TransitionResult {
    data class Transitioned(val record: StateTransitionRecord) : TransitionResult
    data class Rejected(
        val fromStatus: RideStatus,
        val event: RideEvent,
        val reason: String
    ) : TransitionResult
}

/**
 * Deterministic Finite State Machine governing the complete Ride & Dispatch lifecycle.
 * Prevents illegal jumps (e.g. COMPLETED -> IN_PROGRESS, or double driver acceptance).
 */
object RideStateMachine {

    val ALLOWED_TRANSITIONS: Map<RideStatus, Set<RideStatus>> = mapOf(
        RideStatus.REQUESTED to setOf(
            RideStatus.SEARCHING_FOR_DRIVER,
            RideStatus.OFFERED,
            RideStatus.CANCELLED,
            RideStatus.EXPIRED
        ),
        RideStatus.SEARCHING_FOR_DRIVER to setOf(
            RideStatus.SEARCHING_FOR_DRIVER,
            RideStatus.OFFERED,
            RideStatus.ACCEPTED,
            RideStatus.NO_DRIVER_AVAILABLE,
            RideStatus.CANCELLED,
            RideStatus.EXPIRED
        ),
        RideStatus.OFFERED to setOf(
            RideStatus.SEARCHING_FOR_DRIVER,
            RideStatus.OFFERED,
            RideStatus.ACCEPTED,
            RideStatus.NO_DRIVER_AVAILABLE,
            RideStatus.CANCELLED,
            RideStatus.EXPIRED
        ),
        RideStatus.ACCEPTED to setOf(
            RideStatus.DRIVER_ARRIVING,
            RideStatus.ARRIVED,
            RideStatus.CANCELLED
        ),
        RideStatus.DRIVER_ARRIVING to setOf(
            RideStatus.ARRIVED,
            RideStatus.CANCELLED
        ),
        RideStatus.ARRIVED to setOf(
            RideStatus.IN_PROGRESS,
            RideStatus.CANCELLED
        ),
        RideStatus.IN_PROGRESS to setOf(
            RideStatus.COMPLETED
        ),
        RideStatus.NO_DRIVER_AVAILABLE to setOf(
            RideStatus.SEARCHING_FOR_DRIVER,
            RideStatus.CANCELLED
        ),
        RideStatus.COMPLETED to emptySet(),
        RideStatus.CANCELLED to emptySet(),
        RideStatus.EXPIRED to emptySet()
    )

    private val recentAuditLog = Collections.synchronizedList(ArrayList<StateTransitionRecord>())

    fun parseStatus(raw: String): RideStatus =
        RideStatus.entries.firstOrNull { it.name == raw } ?: RideStatus.REQUESTED

    fun allowedNextStates(current: RideStatus): Set<RideStatus> =
        ALLOWED_TRANSITIONS[current].orEmpty()

    fun transition(
        rideId: String,
        currentStatus: RideStatus,
        currentDriverId: String,
        event: RideEvent
    ): TransitionResult {
        val targetStatus = when (event) {
            RideEvent.StartSearch -> RideStatus.SEARCHING_FOR_DRIVER
            is RideEvent.DispatchOffer -> RideStatus.OFFERED
            is RideEvent.DriverAccept -> {
                if (event.driverId.isBlank()) {
                    return TransitionResult.Rejected(currentStatus, event, "Driver ID cannot be blank")
                }
                if (currentDriverId.isNotBlank()) {
                    return TransitionResult.Rejected(
                        currentStatus,
                        event,
                        "Ride already assigned to driver $currentDriverId"
                    )
                }
                RideStatus.ACCEPTED
            }
            RideEvent.DriverMarkArriving -> RideStatus.DRIVER_ARRIVING
            RideEvent.DriverArrive -> RideStatus.ARRIVED
            RideEvent.StartTrip -> RideStatus.IN_PROGRESS
            RideEvent.CompleteTrip -> RideStatus.COMPLETED
            RideEvent.MarkNoDriver -> RideStatus.NO_DRIVER_AVAILABLE
            RideEvent.RetrySearch -> RideStatus.SEARCHING_FOR_DRIVER
            RideEvent.ExpireRide -> RideStatus.EXPIRED
            is RideEvent.Cancel -> RideStatus.CANCELLED
        }

        val allowed = ALLOWED_TRANSITIONS[currentStatus].orEmpty()
        if (targetStatus !in allowed) {
            return TransitionResult.Rejected(
                fromStatus = currentStatus,
                event = event,
                reason = "Illegal state transition: $currentStatus -> $targetStatus for ${event::class.simpleName}"
            )
        }

        val record = StateTransitionRecord(
            rideId = rideId,
            fromStatus = currentStatus,
            toStatus = targetStatus,
            eventName = event::class.simpleName ?: "Event",
            actorNote = when (event) {
                is RideEvent.DispatchOffer -> "Round ${event.round}"
                is RideEvent.DriverAccept -> "Assigned driver ${event.driverId}"
                is RideEvent.Cancel -> "${event.actorRole}: ${event.reason}"
                else -> targetStatus.name
            }
        )
        synchronized(recentAuditLog) {
            recentAuditLog.add(0, record)
            if (recentAuditLog.size > 100) {
                recentAuditLog.removeAt(recentAuditLog.lastIndex)
            }
        }
        return TransitionResult.Transitioned(record)
    }

    fun validateDirectTransition(
        ride: Ride,
        targetStatus: RideStatus
    ): TransitionResult {
        val current = parseStatus(ride.status)
        val event = when (targetStatus) {
            RideStatus.SEARCHING_FOR_DRIVER ->
                if (current == RideStatus.NO_DRIVER_AVAILABLE) RideEvent.RetrySearch else RideEvent.StartSearch
            RideStatus.OFFERED -> RideEvent.DispatchOffer(ride.dispatchRound.toInt())
            RideStatus.ACCEPTED -> RideEvent.DriverAccept(ride.driverId.ifBlank { "pending" })
            RideStatus.DRIVER_ARRIVING -> RideEvent.DriverMarkArriving
            RideStatus.ARRIVED -> RideEvent.DriverArrive
            RideStatus.IN_PROGRESS -> RideEvent.StartTrip
            RideStatus.COMPLETED -> RideEvent.CompleteTrip
            RideStatus.NO_DRIVER_AVAILABLE -> RideEvent.MarkNoDriver
            RideStatus.EXPIRED -> RideEvent.ExpireRide
            RideStatus.CANCELLED -> RideEvent.Cancel("SYSTEM", "Status update")
            RideStatus.REQUESTED -> return TransitionResult.Rejected(current, RideEvent.StartSearch, "Cannot revert to REQUESTED")
        }
        return transition(
            rideId = ride.id,
            currentStatus = current,
            currentDriverId = if (targetStatus == RideStatus.ACCEPTED) "" else ride.driverId,
            event = event
        )
    }

    fun recentTransitions(): List<StateTransitionRecord> =
        synchronized(recentAuditLog) { recentAuditLog.toList() }
}
