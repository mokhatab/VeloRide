package com.example.domain.payment

import com.example.BuildConfig
import com.example.domain.model.PaymentMethodRecord
import com.example.domain.model.PaymentStatus
import com.example.domain.model.RiderPaymentChoice
import com.example.domain.model.StripeAutoConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
    val idempotencyKey: String,
    val paymentMethodType: String = "CARD"
)

data class PaymentIntentResult(
    val status: PaymentStatus,
    val stripePaymentIntentId: String,
    val clientSecret: String?,
    val failureReason: String?
)

data class BackendPaymentIntentBody(
    val rideId: String,
    val riderId: String,
    val amountMinor: Long,
    val currency: String,
    val paymentMethodId: String?,
    val paymentMethodType: String = "CARD"
)

data class BackendPaymentIntentResponse(
    val paymentIntentId: String = "",
    val clientSecret: String = "",
    val status: String = "PENDING",
    val failureReason: String? = null
)

data class BackendRefundBody(
    val paymentIntentId: String,
    val amountMinor: Long,
    val reason: String
)

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
    fun configureForRiderChoice(
        choice: RiderPaymentChoice,
        currencyCode: String,
        decimals: Int,
        linkedMethods: List<PaymentMethodRecord>
    ): StripeAutoConfig
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

    private val moshi: Moshi by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

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

    override fun configureForRiderChoice(
        choice: RiderPaymentChoice,
        currencyCode: String,
        decimals: Int,
        linkedMethods: List<PaymentMethodRecord>
    ): StripeAutoConfig {
        val currencyOk = validateCurrencySupport(currencyCode, decimals)
        val gwStatus = configurationStatus()

        return when (choice) {
            RiderPaymentChoice.CASH -> StripeAutoConfig(
                selectedChoice = RiderPaymentChoice.CASH,
                paymentMethodType = "CASH",
                resolvedTokenId = "CASH",
                stripeAutoConfigured = false,
                stripeGatewayLive = gwStatus.isConfigured,
                currencySupported = currencyOk,
                summaryMessage = "Cash settlement selected — pay driver at drop-off (Stripe bypassed)"
            )
            RiderPaymentChoice.E_WALLET -> {
                val walletPm = linkedMethods.firstOrNull {
                    it.methodType.equals("WALLET", ignoreCase = true)
                }
                val token = walletPm?.stripePaymentMethodId ?: "pm_wallet_stripe_auto"
                val msg = if (gwStatus.isConfigured) {
                    "Stripe E-Wallet auto-configured ($token) • Auto-charges on trip completion"
                } else {
                    "E-Wallet Stripe mode prepared ($token) • Add Stripe Secrets for live settlement"
                }
                StripeAutoConfig(
                    selectedChoice = RiderPaymentChoice.E_WALLET,
                    paymentMethodType = "E_WALLET",
                    resolvedTokenId = "E_WALLET:$token",
                    stripeAutoConfigured = currencyOk,
                    stripeGatewayLive = gwStatus.isConfigured,
                    currencySupported = currencyOk,
                    summaryMessage = msg
                )
            }
            RiderPaymentChoice.CARD -> {
                val cardPm = linkedMethods.firstOrNull {
                    it.methodType.equals("CARD", ignoreCase = true) && it.isDefault
                } ?: linkedMethods.firstOrNull { it.methodType.equals("CARD", ignoreCase = true) }
                val token = cardPm?.stripePaymentMethodId ?: "pm_card_stripe_default"
                val msg = if (gwStatus.isConfigured) {
                    "Stripe Card auto-configured (${cardPm?.brand ?: "Card"} ••${cardPm?.last4 ?: "4242"})"
                } else {
                    "Stripe Card mode prepared • Add Stripe Secrets for live charge"
                }
                StripeAutoConfig(
                    selectedChoice = RiderPaymentChoice.CARD,
                    paymentMethodType = "CARD",
                    resolvedTokenId = "CARD:$token",
                    stripeAutoConfigured = currencyOk,
                    stripeGatewayLive = gwStatus.isConfigured,
                    currencySupported = currencyOk,
                    summaryMessage = msg
                )
            }
        }
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
                        paymentMethodId = request.paymentMethodId,
                        paymentMethodType = request.paymentMethodType
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
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(StripeBackendApi::class.java)
    }
}
