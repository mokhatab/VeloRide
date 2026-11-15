package com.example.core.maps

import com.example.BuildConfig

object MapsKeyHelper {
    fun isRealMapsKey(key: String = BuildConfig.MAPS_API_KEY): Boolean {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.equals("null", ignoreCase = true)) return false
        if (trimmed.startsWith("MY_MAPS")) return false
        return true
    }
}
