package com.example.domain.payment

import com.example.BuildConfig
import com.example.domain.model.PaymentStatus
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class StripeConfigurationStatus(
    val isConfigured: Boolean,
    val publishableKeyPresent: Boolean,
    val backendUrlPresent: Boolean,
    val message: String
)

data class PaymentIntentRequest(
    val rideId: String,
    val riderId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val paymentMethodId: String?,
    val idempotencyKey: String
)

data class PaymentIntentResult(
    val status: PaymentStatus,
    val stripePaymentIntentId: String,
    val clientSecret: String?,
    val failureReason: String?
)

@JsonClass(generateAdapter = true)
data class BackendPaymentIntentBody(
    val rideId: String,
    val riderId: String,
    val amountMinor: Long,
    val currency: String,
    val paymentMethodId: String?
)

@JsonClass(generateAdapter = true)
data class BackendPaymentIntentResponse(
    val paymentIntentId: String = "",
    val clientSecret: String = "",
    val status: String = "PENDING",
    val failureReason: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendRefundBody(
    val paymentIntentId: String,
    val amountMinor: Long,
    val reason: String
)

@JsonClass(generateAdapter = true)
data class BackendRefundResponse(
    val refundId: String = "",
    val status: String = "FAILED",
    val refundedAmountMinor: Long = 0L
)

interface StripeBackendApi {
    @POST("payments/create-intent")
    suspend fun createPaymentIntent(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: BackendPaymentIntentBody
    ): BackendPaymentIntentResponse

    @POST("payments/refund")
    suspend fun refundPayment(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: BackendRefundBody
    ): BackendRefundResponse
}

interface PaymentGateway {
    fun configurationStatus(): StripeConfigurationStatus
    fun validateCurrencySupport(currencyCode: String, decimals: Int): Boolean
    suspend fun createAndAuthorizePayment(request: PaymentIntentRequest): PaymentIntentResult
    suspend fun requestRefund(
        paymentIntentId: String,
        amountMinor: Long,
        idempotencyKey: String,
        reason: String
    ): BackendRefundResponse
}

class StripePaymentGateway(
    private val backendUrl: String = BuildConfig.STRIPE_BACKEND_URL,
    private val publishableKey: String = BuildConfig.STRIPE_PUBLISHABLE_KEY
) : PaymentGateway {

    private val supportedCurrencies = mapOf(
        "EGP" to 2,
        "SAR" to 2,
        "AED" to 2,
        "GBP" to 2,
        "USD" to 2,
        "EUR" to 2
    )

    override fun configurationStatus(): StripeConfigurationStatus {
        val hasKey = publishableKey.isNotBlank() && !publishableKey.contains("MY_STRIPE")
        val hasUrl = backendUrl.isNotBlank() && backendUrl.startsWith("https://")
        val ready = hasKey && hasUrl
        val msg = if (ready) {
            "Stripe Payment Gateway Active"
        } else {
            "Stripe server endpoint & publishable key are not configured. Payments remain inactive until verified."
        }
        return StripeConfigurationStatus(
            isConfigured = ready,
            publishableKeyPresent = hasKey,
            backendUrlPresent = hasUrl,
            message = msg
        )
    }

    override fun validateCurrencySupport(currencyCode: String, decimals: Int): Boolean {
        val expectedDecimals = supportedCurrencies[currencyCode.uppercase()] ?: return false
        return expectedDecimals == decimals
    }

    override suspend fun createAndAuthorizePayment(request: PaymentIntentRequest): PaymentIntentResult =
        withContext(Dispatchers.IO) {
            val status = configurationStatus()
            if (!status.isConfigured) {
                return@withContext PaymentIntentResult(
                    status = PaymentStatus.FAILED,
                    stripePaymentIntentId = "",
                    clientSecret = null,
                    failureReason = status.message
                )
            }
            if (!validateCurrencySupport(request.currencyCode, 2) || request.amountMinor <= 0L) {
                return@withContext PaymentIntentResult(
                    status = PaymentStatus.FAILED,
                    stripePaymentIntentId = "",
                    clientSecret = null,
                    failureReason = "Unsupported Stripe currency or invalid minor amount: ${request.currencyCode}"
                )
            }
            try {
                val api = buildApi()
                val res = api.createPaymentIntent(
                    idempotencyKey = request.idempotencyKey,
                    body = BackendPaymentIntentBody(
                        rideId = request.rideId,
                        riderId = request.riderId,
                        amountMinor = request.amountMinor,
                        currency = request.currencyCode.lowercase(),
                        paymentMethodId = request.paymentMethodId
                    )
                )
                val mappedStatus = PaymentStatus.entries.firstOrNull {
                    it.name.equals(res.status, ignoreCase = true)
                } ?: PaymentStatus.PENDING
                PaymentIntentResult(
                    status = mappedStatus,
                    stripePaymentIntentId = res.paymentIntentId,
                    clientSecret = res.clientSecret.takeIf { it.isNotBlank() },
                    failureReason = res.failureReason
                )
            } catch (e: Exception) {
                PaymentIntentResult(
                    status = PaymentStatus.FAILED,
                    stripePaymentIntentId = "",
                    clientSecret = null,
                    failureReason = e.localizedMessage ?: "Stripe backend request failed"
                )
            }
        }

    override suspend fun requestRefund(
        paymentIntentId: String,
        amountMinor: Long,
        idempotencyKey: String,
        reason: String
    ): BackendRefundResponse = withContext(Dispatchers.IO) {
        val status = configurationStatus()
        if (!status.isConfigured || paymentIntentId.isBlank()) {
            return@withContext BackendRefundResponse(
                refundId = "",
                status = "FAILED",
                refundedAmountMinor = 0L
            )
        }
        try {
            buildApi().refundPayment(
                idempotencyKey = idempotencyKey,
                body = BackendRefundBody(
                    paymentIntentId = paymentIntentId,
                    amountMinor = amountMinor,
                    reason = reason
                )
            )
        } catch (_: Exception) {
            BackendRefundResponse(refundId = "", status = "FAILED", refundedAmountMinor = 0L)
        }
    }

    private fun buildApi(): StripeBackendApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val normalizedBase = if (backendUrl.endsWith("/")) backendUrl else "$backendUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(StripeBackendApi::class.java)
    }
}
