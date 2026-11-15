package com.example.ui.admin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firestore.PricingAndAdminRepository
import com.example.domain.model.AuditLog
import com.example.domain.model.ComplaintRecord
import com.example.domain.model.DriverApprovalStatus
import com.example.domain.model.DriverDocument
import com.example.domain.model.DriverEarning
import com.example.domain.model.DriverProfile
import com.example.domain.model.OperationalConfig
import com.example.domain.model.PaymentRecord
import com.example.domain.model.PricingPolicy
import com.example.domain.model.Ride
import com.example.domain.model.UserAccount
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdminViewModel(
    private val adminRepository: PricingAndAdminRepository
) : ViewModel() {

    private val _adminMessage = MutableStateFlow<String?>(null)
    val adminMessage: StateFlow<String?> = _adminMessage.asStateFlow()

    val users: StateFlow<List<UserAccount>> = adminRepository.observeAllUsersAdmin()
        .catch { logError("users", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val drivers: StateFlow<List<DriverProfile>> = adminRepository.observeAllDriversAdmin()
        .catch { logError("drivers", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val documents: StateFlow<List<DriverDocument>> = adminRepository.observeAllDocumentsAdmin()
        .catch { logError("documents", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val rides: StateFlow<List<Ride>> = adminRepository.observeAllRidesAdmin()
        .catch { logError("rides", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val payments: StateFlow<List<PaymentRecord>> = adminRepository.observeAllPaymentsAdmin()
        .catch { logError("payments", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val earnings: StateFlow<List<DriverEarning>> = adminRepository.observeAllEarningsAdmin()
        .catch { logError("earnings", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val complaints: StateFlow<List<ComplaintRecord>> = adminRepository.observeAllComplaintsAdmin()
        .catch { logError("complaints", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val auditLogs: StateFlow<List<AuditLog>> = adminRepository.observeAuditLogsAdmin()
        .catch { logError("auditLogs", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun clearMessage() {
        _adminMessage.value = null
    }

    fun approveDriver(driverId: String) {
        viewModelScope.launch {
            adminRepository.adminSetDriverApproval(driverId, DriverApprovalStatus.APPROVED)
                .onSuccess { _adminMessage.value = "Driver approved and activated." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun rejectDriver(driverId: String, reason: String) {
        viewModelScope.launch {
            adminRepository.adminSetDriverApproval(driverId, DriverApprovalStatus.REJECTED, reason)
                .onSuccess { _adminMessage.value = "Driver rejected." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun suspendDriver(driverId: String, reason: String) {
        viewModelScope.launch {
            adminRepository.adminSetDriverApproval(driverId, DriverApprovalStatus.SUSPENDED, reason)
                .onSuccess { _adminMessage.value = "Driver suspended." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun verifyDocument(docId: String, approved: Boolean, notes: String) {
        viewModelScope.launch {
            val status = if (approved) "VERIFIED" else "REJECTED"
            adminRepository.adminVerifyDocument(docId, status, notes)
                .onSuccess { _adminMessage.value = "Document marked $status." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun toggleUserSuspension(userId: String, suspend: Boolean) {
        viewModelScope.launch {
            adminRepository.adminSetUserSuspended(userId, suspend)
                .onSuccess { _adminMessage.value = if (suspend) "Account suspended." else "Account activated." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun updatePricingPolicy(
        policy: PricingPolicy,
        baseMinor: Long,
        perKmMinor: Long,
        waitingMinor: Long,
        minFareMinor: Long,
        surge: Double
    ) {
        viewModelScope.launch {
            adminRepository.adminUpdatePricingPolicy(
                policy, baseMinor, perKmMinor, waitingMinor, minFareMinor, surge
            ).onSuccess {
                _adminMessage.value = "Pricing v${policy.pricingVersion + 1} saved & audited."
            }.onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun updateOperationalConfig(config: OperationalConfig) {
        viewModelScope.launch {
            adminRepository.adminUpdateOperationalConfig(config)
                .onSuccess { _adminMessage.value = "Operational configuration updated & audited." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun recordManualPayout(earning: DriverEarning, reference: String) {
        viewModelScope.launch {
            adminRepository.adminRecordManualPayout(earning, reference)
                .onSuccess { _adminMessage.value = "Manual payout recorded." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    fun resolveComplaint(complaintId: String, status: String, notes: String) {
        viewModelScope.launch {
            adminRepository.adminResolveComplaint(complaintId, status, notes)
                .onSuccess { _adminMessage.value = "Complaint updated." }
                .onFailure { _adminMessage.value = it.localizedMessage }
        }
    }

    private fun logError(tag: String, error: Throwable) {
        val fe = generateSequence(error) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        if (fe == null && error is CancellationException) throw error
        Log.w("AdminVM", "Admin stream error [$tag]", fe ?: error)
    }
}
