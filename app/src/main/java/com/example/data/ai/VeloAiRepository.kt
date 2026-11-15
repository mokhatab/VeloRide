package com.example.data.ai

import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LatLng
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.RetrievalConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.retrievalConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "VeloAiRepository"

enum class AiGroundingMode {
    GOOGLE_MAPS,
    GOOGLE_SEARCH
}

data class WebSourceAttribution(
    val title: String,
    val uri: String
)

data class MapsSourceAttribution(
    val title: String,
    val uri: String,
    val placeId: String?
)

data class AiGroundedResponse(
    val mode: AiGroundingMode,
    val text: String,
    val webSources: List<WebSourceAttribution> = emptyList(),
    val mapsSources: List<MapsSourceAttribution> = emptyList(),
    val searchEntryPointHtml: String? = null
)

sealed interface GroundedStreamEvent {
    data class PartialText(val accumulatedText: String) : GroundedStreamEvent
    data class Completed(val result: AiGroundedResponse) : GroundedStreamEvent
}

class VeloAiRepository {

    companion object {
        const val MODEL_GROUNDING = "gemini-3.8-flash"
        const val MODEL_LIVE = "gemini-3.1-flash-live-preview"
    }

    private val searchModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_GROUNDING,
            tools = listOf(Tool.googleSearch()),
            systemInstruction = content {
                text(
                    "You are VeloRide Live Mobility Concierge. Help riders and drivers with accurate, " +
                        "concise, up-to-date information on local traffic, airport terminals, local transport fees, " +
                        "weather, and city events. Respond in the user's language."
                )
            }
        )
    }

    fun streamGoogleSearchQuery(prompt: String): Flow<GroundedStreamEvent> = flow {
        val textBuilder = StringBuilder()
        val webSources = linkedMapOf<String, WebSourceAttribution>()
        var renderedHtml: String? = null

        searchModel.generateContentStream(prompt).collect { chunk ->
            val chunkText = chunk.text.orEmpty()
            if (chunkText.isNotEmpty()) {
                textBuilder.append(chunkText)
                emit(GroundedStreamEvent.PartialText(textBuilder.toString()))
            }

            val groundingMetadata = chunk.candidates.firstOrNull()?.groundingMetadata
            groundingMetadata?.searchEntryPoint?.renderedContent?.let { html ->
                if (html.isNotBlank()) renderedHtml = html
            }
            groundingMetadata?.groundingChunks?.forEach { gChunk ->
                val web = gChunk.web
                val uri = web?.uri.orEmpty()
                if (uri.isNotBlank()) {
                    val title = web?.title?.takeIf { it.isNotBlank() } ?: uri
                    webSources[uri] = WebSourceAttribution(title = title, uri = uri)
                }
            }
        }

        val finalText = textBuilder.toString().trim()
        if (finalText.isBlank()) {
            throw IllegalStateException("Model returned an empty response.")
        }

        emit(
            GroundedStreamEvent.Completed(
                AiGroundedResponse(
                    mode = AiGroundingMode.GOOGLE_SEARCH,
                    text = finalText,
                    webSources = webSources.values.toList(),
                    searchEntryPointHtml = renderedHtml
                )
            )
        )
    }

    suspend fun queryGoogleMaps(
        prompt: String,
        latitude: Double,
        longitude: Double,
        languageCode: String
    ): AiGroundedResponse {
        val retrievalConfig = retrievalConfig {
            latLng = LatLng(latitude = latitude, longitude = longitude)
            this.languageCode = languageCode
        }
        val toolConfig = ToolConfig(retrievalConfig = retrievalConfig)
        val mapsModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_GROUNDING,
            toolConfig = toolConfig,
            tools = listOf(Tool.googleMaps()),
            systemInstruction = content {
                text(
                    "You are VeloRide Place & Route Assistant. Use Google Maps grounding near the provided coordinates " +
                        "to recommend real places, landmarks, pickup points, airports, cafes, and neighborhoods. " +
                        "Include helpful practical pickup tips and respond in the user's language."
                )
            }
        )

        val response = mapsModel.generateContent(prompt)
        val text = response.text?.trim().orEmpty()
        if (text.isBlank()) {
            throw IllegalStateException("Model returned an empty response.")
        }

        val mapsSources = mutableListOf<MapsSourceAttribution>()
        val seenUris = hashSetOf<String>()
        val groundingMetadata = response.candidates.firstOrNull()?.groundingMetadata
        groundingMetadata?.groundingChunks?.forEach { gChunk ->
            val maps = gChunk.maps
            if (maps != null) {
                val uri = maps.uri.orEmpty()
                val title = maps.title?.takeIf { it.isNotBlank() } ?: "Google Maps"
                val key = uri.ifBlank { maps.placeId ?: title }
                if (seenUris.add(key)) {
                    mapsSources.add(
                        MapsSourceAttribution(
                            title = title,
                            uri = uri,
                            placeId = maps.placeId
                        )
                    )
                }
            }
        }

        return AiGroundedResponse(
            mode = AiGroundingMode.GOOGLE_MAPS,
            text = text,
            mapsSources = mapsSources
        )
    }

    @OptIn(PublicPreviewAPI::class)
    @SuppressLint("MissingPermission")
    suspend fun connectAndStartLiveVoice(
        cityContext: String,
        languageLabel: String
    ): LiveSession {
        val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = MODEL_LIVE,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice("FENRIR"))
            },
            systemInstruction = content {
                text(
                    "You are VeloRide Voice Copilot helping a user in $cityContext. " +
                        "Prefer speaking in $languageLabel unless the user speaks another language. " +
                        "Keep replies warm, helpful, and brief for hands-free ride or driving use."
                )
            }
        )

        val session = liveModel.connect()
        try {
            session.startAudioConversation()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start live audio conversation", e)
            runCatching { session.close() }
            throw e
        }
        return session
    }

    @OptIn(PublicPreviewAPI::class)
    suspend fun sendLiveContextMessage(session: LiveSession, text: String) {
        session.send(
            content = content("user") { text(text) },
            turnComplete = true
        )
    }

    @OptIn(PublicPreviewAPI::class)
    suspend fun stopLiveSession(session: LiveSession?) {
        if (session == null) return
        runCatching { session.stopAudioConversation() }
        runCatching { session.close() }
    }
}
