package com.example.domain

import com.example.core.i18n.AppLanguage
import com.example.core.i18n.StringsProvider
import com.example.core.maps.MapsKeyHelper
import com.example.data.ai.AiGroundingMode
import com.example.data.ai.VeloAiRepository
import com.example.domain.mapping.GeoPoint
import com.example.ui.ai.AiQueryUiState
import com.example.ui.ai.LiveVoiceUiState
import com.example.ui.ai.VeloAiViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAndMapsUnitTest {

    @Test
    fun `maps key placeholder guard rejects default and blank values`() {
        assertFalse(MapsKeyHelper.isRealMapsKey(""))
        assertFalse(MapsKeyHelper.isRealMapsKey("   "))
        assertFalse(MapsKeyHelper.isRealMapsKey("null"))
        assertFalse(MapsKeyHelper.isRealMapsKey("MY_MAPS_API_KEY"))
        assertTrue(MapsKeyHelper.isRealMapsKey("AIzaSyDummyValidLookingTestKey12345"))
    }

    @Test
    fun `ai repository targets supported firebase ai logic models`() {
        assertEquals("gemini-3.8-flash", VeloAiRepository.MODEL_GROUNDING)
        assertEquals("gemini-3.1-flash-live-preview", VeloAiRepository.MODEL_LIVE)
    }

    @Test
    fun `all 7 languages include complete ai and voice translations`() {
        AppLanguage.entries.forEach { lang ->
            val s = StringsProvider.get(lang)
            assertTrue("Missing navAiCopilot for $lang", s.navAiCopilot.isNotBlank())
            assertTrue("Missing liveVoiceTitle for $lang", s.liveVoiceTitle.isNotBlank())
            assertTrue("Missing startVoiceCall for $lang", s.startVoiceCall.isNotBlank())
            assertTrue("Missing stopVoiceCall for $lang", s.stopVoiceCall.isNotBlank())
            assertTrue("Missing mapsGroundingTab for $lang", s.mapsGroundingTab.isNotBlank())
            assertTrue("Missing searchGroundingTab for $lang", s.searchGroundingTab.isNotBlank())
            assertTrue("Missing googleMapsSecretHint for $lang", s.googleMapsSecretHint.isNotBlank())
        }
    }

    @Test
    fun `ai viewmodel updates mode and surfaces mic permission denial`() {
        val vm = VeloAiViewModel()
        assertEquals(AiGroundingMode.GOOGLE_MAPS, vm.selectedMode.value)
        vm.selectMode(AiGroundingMode.GOOGLE_SEARCH)
        assertEquals(AiGroundingMode.GOOGLE_SEARCH, vm.selectedMode.value)

        // Blank prompt must remain idle
        vm.submitGroundedQuery("   ", AiGroundingMode.GOOGLE_SEARCH, GeoPoint(30.0444, 31.2357), AppLanguage.ENGLISH)
        assertEquals(AiQueryUiState.Idle, vm.queryState.value)

        // Mic denial surfaces transparent error message
        vm.reportMicPermissionDenied("Microphone permission required")
        val voice = vm.voiceState.value
        assertTrue(voice is LiveVoiceUiState.Error)
        assertEquals("Microphone permission required", (voice as LiveVoiceUiState.Error).message)
    }
}
