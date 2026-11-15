package com.example.core.i18n

import androidx.compose.ui.unit.LayoutDirection
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

enum class AppLanguage(
    val code: String,
    val nativeLabel: String,
    val locale: Locale,
    val isRtl: Boolean
) {
    ARABIC("ar", "العربية", Locale("ar", "EG"), true),
    ENGLISH("en", "English", Locale.US, false),
    GERMAN("de", "Deutsch", Locale.GERMANY, false),
    FRENCH("fr", "Français", Locale.FRANCE, false),
    SPANISH("es", "Español", Locale("es", "ES"), false),
    ITALIAN("it", "Italiano", Locale.ITALY, false),
    RUSSIAN("ru", "Русский", Locale("ru", "RU"), false);

    val layoutDirection: LayoutDirection
        get() = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    companion object {
        val DEFAULT = ARABIC

        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
        }
    }
}

object LocaleFormatter {
    fun formatCurrencyMinor(
        amountMinor: Long,
        currencyCode: String,
        currencySymbol: String,
        decimals: Int,
        language: AppLanguage
    ): String {
        val divisor = BigDecimal.TEN.pow(decimals)
        val major = BigDecimal(amountMinor).divide(divisor)
        val nf = NumberFormat.getNumberInstance(language.locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return "$currencySymbol ${nf.format(major)} ($currencyCode)"
    }

    fun formatCompactCurrency(
        amountMinor: Long,
        currencySymbol: String,
        decimals: Int,
        language: AppLanguage
    ): String {
        val divisor = BigDecimal.TEN.pow(decimals)
        val major = BigDecimal(amountMinor).divide(divisor)
        val nf = NumberFormat.getNumberInstance(language.locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return "$currencySymbol ${nf.format(major)}"
    }

    fun formatDistanceKm(km: Double, language: AppLanguage): String {
        val nf = NumberFormat.getNumberInstance(language.locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        val unit = if (language == AppLanguage.ARABIC) "كم" else "km"
        return "${nf.format(km)} $unit"
    }

    fun formatDateTime(epochMs: Long, language: AppLanguage): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, language.locale)
        return df.format(Date(epochMs))
    }
}
