package com.example.ui.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.i18n.AppLanguage
import com.example.data.ai.AiGroundedResponse
import com.example.data.ai.AiGroundingMode
import com.example.data.ai.GroundedStreamEvent
import com.example.data.ai.VeloAiRepository
import com.example.domain.mapping.GeoPoint
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "VeloAiViewModel"

sealed interface AiQueryUiState {
    data object Idle : AiQueryUiState
    data object Loading : AiQueryUiState
    data class Streaming(val mode: AiGroundingMode, val text: String) : AiQueryUiState
    data class Success(val response: AiGroundedResponse) : AiQueryUiState
    data class Error(val message: String) : AiQueryUiState
}

sealed interface LiveVoiceUiState {
    data object Idle : LiveVoiceUiState
    data object Connecting : LiveVoiceUiState
    data class Active(val cityContext: String, val note: String? = null) : LiveVoiceUiState
    data class Error(val message: String) : LiveVoiceUiState
}

@OptIn(PublicPreviewAPI::class)
class VeloAiViewModel(
    private val aiRepository: VeloAiRepository = VeloAiRepository()
) : ViewModel() {

    private val _selectedMode = MutableStateFlow(AiGroundingMode.GOOGLE_MAPS)
    val selectedMode: StateFlow<AiGroundingMode> = _selectedMode.asStateFlow()

    private val _queryState = MutableStateFlow<AiQueryUiState>(AiQueryUiState.Idle)
    val queryState: StateFlow<AiQueryUiState> = _queryState.asStateFlow()

    private val _voiceState = MutableStateFlow<LiveVoiceUiState>(LiveVoiceUiState.Idle)
    val voiceState: StateFlow<LiveVoiceUiState> = _voiceState.asStateFlow()

    private var activeLiveSession: LiveSession? = null

    fun selectMode(mode: AiGroundingMode) {
        _selectedMode.value = mode
    }

    fun submitGroundedQuery(
        prompt: String,
        mode: AiGroundingMode = _selectedMode.value,
        pickupPoint: GeoPoint,
        language: AppLanguage
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            _queryState.value = AiQueryUiState.Loading
            try {
                when (mode) {
                    AiGroundingMode.GOOGLE_SEARCH -> {
                        withTimeout(60_000L) {
                            aiRepository.streamGoogleSearchQuery(trimmed).collect { event ->
                                when (event) {
                                    is GroundedStreamEvent.PartialText -> {
                                        _queryState.value = AiQueryUiState.Streaming(mode, event.accumulatedText)
                                    }
                                    is GroundedStreamEvent.Completed -> {
                                        _queryState.value = AiQueryUiState.Success(event.result)
                                    }
                                }
                            }
                        }
                    }

                    AiGroundingMode.GOOGLE_MAPS -> {
                        val bcp47 = when (language) {
                            AppLanguage.ARABIC -> "ar-EG"
                            AppLanguage.ENGLISH -> "en-US"
                            AppLanguage.GERMAN -> "de-DE"
                            AppLanguage.FRENCH -> "fr-FR"
                            AppLanguage.SPANISH -> "es-ES"
                            AppLanguage.ITALIAN -> "it-IT"
                            AppLanguage.RUSSIAN -> "ru-RU"
                        }
                        val result = withTimeout(60_000L) {
                            aiRepository.queryGoogleMaps(
                                prompt = trimmed,
                                latitude = pickupPoint.latitude,
                                longitude = pickupPoint.longitude,
                                languageCode = bcp47
                            )
                        }
                        _queryState.value = AiQueryUiState.Success(result)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI grounded query timed out", e)
                _queryState.value = AiQueryUiState.Error(
                    "Request timed out after 60 seconds. Please check connection and retry."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI grounded query failed", e)
                _queryState.value = AiQueryUiState.Error(
                    e.localizedMessage ?: "AI request failed."
                )
            }
        }
    }

    fun startVoiceConversation(cityContext: String, language: AppLanguage) {
        if (_voiceState.value is LiveVoiceUiState.Connecting || _voiceState.value is LiveVoiceUiState.Active) {
            return
        }
        viewModelScope.launch {
            _voiceState.value = LiveVoiceUiState.Connecting
            try {
                val session = withTimeout(30_000L) {
                    aiRepository.connectAndStartLiveVoice(
                        cityContext = cityContext,
                        languageLabel = language.nativeLabel
                    )
                }
                activeLiveSession = session
                _voiceState.value = LiveVoiceUiState.Active(cityContext = cityContext)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Live voice connection timed out", e)
                _voiceState.value = LiveVoiceUiState.Error(
                    "Voice session timed out while connecting."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live voice conversation failed", e)
                _voiceState.value = LiveVoiceUiState.Error(
                    e.localizedMessage ?: "Unable to start live voice conversation."
                )
            }
        }
    }

    fun sendVoiceContextPrompt(text: String) {
        val session = activeLiveSession ?: return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                withTimeout(20_000L) {
                    aiRepository.sendLiveContextMessage(session, trimmed)
                }
                val current = _voiceState.value
                if (current is LiveVoiceUiState.Active) {
                    _voiceState.value = current.copy(note = trimmed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending live message", e)
                _voiceState.value = LiveVoiceUiState.Error(
                    e.localizedMessage ?: "Failed sending voice context message."
                )
            }
        }
    }

    fun stopVoiceConversation() {
        val session = activeLiveSession
        activeLiveSession = null
        _voiceState.value = LiveVoiceUiState.Idle
        viewModelScope.launch {
            aiRepository.stopLiveSession(session)
        }
    }

    fun reportMicPermissionDenied(message: String) {
        _voiceState.value = LiveVoiceUiState.Error(message)
    }

    override fun onCleared() {
        val session = activeLiveSession
        activeLiveSession = null
        if (session != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                aiRepository.stopLiveSession(session)
            }
        }
        super.onCleared()
    }
}
