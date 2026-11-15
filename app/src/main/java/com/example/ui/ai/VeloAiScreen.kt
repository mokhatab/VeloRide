package com.example.ui.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.StringsProvider
import com.example.data.ai.AiGroundingMode
import com.example.data.ai.MapsSourceAttribution
import com.example.domain.mapping.GeoPoint

@Composable
fun VeloAiScreen(
    language: AppLanguage,
    cityName: String,
    pickupPoint: GeoPoint,
    selectedMode: AiGroundingMode,
    queryState: AiQueryUiState,
    voiceState: LiveVoiceUiState,
    aiViewModel: VeloAiViewModel,
    onApplyPlaceAsDestinationQuery: (String) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val strings = remember(language) { StringsProvider.get(language) }
    var promptText by remember { mutableStateOf("") }
    var voiceNudgeText by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            aiViewModel.startVoiceConversation(cityName, language)
        } else {
            aiViewModel.reportMicPermissionDenied(strings.micPermissionRequired)
        }
    }

    val isDark = isSystemInDarkTheme()
    val pulseTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Real-Time Bidirectional Voice Copilot Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                1.dp,
                if (voiceState is LiveVoiceUiState.Active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = strings.liveVoiceTitle,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = strings.liveVoiceTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = strings.liveVoiceSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val isActive = voiceState is LiveVoiceUiState.Active
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .scale(if (isActive) pulseScale else 1f)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (voiceState is LiveVoiceUiState.Connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.GraphicEq else Icons.Default.Mic,
                                    contentDescription = strings.liveVoiceTitle,
                                    tint = if (isActive) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    when (voiceState) {
                        is LiveVoiceUiState.Connecting -> {
                            Text(
                                text = strings.connectingVoice,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is LiveVoiceUiState.Active -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${strings.voiceActiveStatus} (${voiceState.cityContext})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = voiceNudgeText,
                                    onValueChange = { voiceNudgeText = it },
                                    label = { Text(strings.askAiHint) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("live_voice_context_input")
                                )
                                FilledTonalButton(
                                    onClick = {
                                        aiViewModel.sendVoiceContextPrompt(voiceNudgeText)
                                        voiceNudgeText = ""
                                    },
                                    enabled = voiceNudgeText.isNotBlank(),
                                    modifier = Modifier
                                        .height(52.dp)
                                        .testTag("live_voice_send_context_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.askAiButton)
                                }
                            }
                        }
                        is LiveVoiceUiState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = voiceState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .testTag("live_voice_error_banner")
                                )
                            }
                        }
                        LiveVoiceUiState.Idle -> Unit
                    }

                    val voiceInFlight = voiceState is LiveVoiceUiState.Active || voiceState is LiveVoiceUiState.Connecting
                    Button(
                        onClick = {
                            if (voiceInFlight) {
                                aiViewModel.stopVoiceConversation()
                            } else {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    aiViewModel.startVoiceConversation(cityName, language)
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (voiceInFlight) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("live_voice_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (voiceInFlight) Icons.Default.CallEnd else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (voiceInFlight) strings.stopVoiceCall else strings.startVoiceCall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Grounded AI Copilot: Google Maps Grounding & Google Search Grounding
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = strings.aiCopilotTitle,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = strings.aiCopilotTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = selectedMode == AiGroundingMode.GOOGLE_MAPS,
                onClick = { aiViewModel.selectMode(AiGroundingMode.GOOGLE_MAPS) },
                label = { Text(strings.mapsGroundingTab) },
                leadingIcon = {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_mode_maps")
            )
            FilterChip(
                selected = selectedMode == AiGroundingMode.GOOGLE_SEARCH,
                onClick = { aiViewModel.selectMode(AiGroundingMode.GOOGLE_SEARCH) },
                label = { Text(strings.searchGroundingTab) },
                leadingIcon = {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_mode_search")
            )
        }

        // Context-aware starter prompts
        val quickPrompts = remember(selectedMode, cityName, language) {
            if (selectedMode == AiGroundingMode.GOOGLE_MAPS) {
                if (language == AppLanguage.ARABIC) {
                    listOf(
                        "أفضل مقاهي ونقاط التقاء قريبة من موقعي في $cityName",
                        "بوابات الاستقبال في أقرب مطار في $cityName",
                        "معالم سياحية ومولات مشهورة قريبة من نقطة الانطلاق"
                    )
                } else {
                    listOf(
                        "Best cafes and safe pickup spots near me in $cityName",
                        "Nearest airport terminal pickup zones in $cityName",
                        "Top landmarks and malls near my pickup point"
                    )
                }
            } else {
                if (language == AppLanguage.ARABIC) {
                    listOf(
                        "حالة المرور والطرق الرئيسية اليوم في $cityName",
                        "توقعات الطقس وتأثيرها على الرحلات في $cityName",
                        "إرشادات المطار ورسوم التنقل الحالية في $cityName"
                    )
                } else {
                    listOf(
                        "Live traffic and major road closures today in $cityName",
                        "Today's weather and airport conditions in $cityName",
                        "Current local taxi and airport surcharge updates in $cityName"
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickPrompts.forEachIndexed { idx, suggestion ->
                AssistChip(
                    onClick = {
                        promptText = suggestion
                        aiViewModel.submitGroundedQuery(
                            prompt = suggestion,
                            mode = selectedMode,
                            pickupPoint = pickupPoint,
                            language = language
                        )
                    },
                    label = { Text(suggestion, maxLines = 1) },
                    modifier = Modifier.testTag("ai_quick_prompt_$idx")
                )
            }
        }

        // Prompt Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text(strings.askAiHint) },
                maxLines = 3,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_prompt_input")
            )
            Button(
                onClick = {
                    aiViewModel.submitGroundedQuery(
                        prompt = promptText,
                        mode = selectedMode,
                        pickupPoint = pickupPoint,
                        language = language
                    )
                },
                enabled = promptText.isNotBlank() && queryState !is AiQueryUiState.Loading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .height(56.dp)
                    .testTag("ai_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.askAiButton)
            }
        }

        // Response & Grounding Attributions Area
        when (queryState) {
            AiQueryUiState.Idle -> {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (selectedMode == AiGroundingMode.GOOGLE_MAPS) {
                                "${strings.mapsGroundingTab} (${cityName})"
                            } else {
                                "${strings.searchGroundingTab} (${cityName})"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.askAiHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AiQueryUiState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("ai_loading_spinner"))
                }
            }

            is AiQueryUiState.Streaming -> {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearBadge(
                            label = if (queryState.mode == AiGroundingMode.GOOGLE_MAPS) "Google Maps" else strings.searchGroundingTab
                        )
                        Text(
                            text = queryState.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("ai_streaming_text")
                        )
                    }
                }
            }

            is AiQueryUiState.Error -> {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = queryState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag("ai_error_message")
                    )
                }
            }

            is AiQueryUiState.Success -> {
                val res = queryState.response
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = res.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("ai_response_text")
                        )

                        // Google Maps Grounding Sources + Official Google Maps Attribution
                        if (res.mapsSources.isNotEmpty()) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = strings.sourcesTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                // Mandatory unmodified Google Maps text attribution guidelines:
                                // Exact text "Google Maps", SansSerif/Roboto, Normal style, 400 weight,
                                // 12-16sp, single line, non-translated, accessible contrast.
                                Text(
                                    text = "Google Maps",
                                    fontFamily = FontFamily.SansSerif,
                                    fontStyle = FontStyle.Normal,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF5E5E5E),
                                    modifier = Modifier.testTag("google_maps_attribution_text")
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                res.mapsSources.forEachIndexed { idx, place ->
                                    MapsPlaceSourceCard(
                                        place = place,
                                        useAsDestinationLabel = strings.useAsDestination,
                                        onOpenUri = {
                                            if (place.uri.isNotBlank()) {
                                                uriHandler.openUri(place.uri)
                                            }
                                        },
                                        onUseAsDestination = {
                                            onApplyPlaceAsDestinationQuery(place.title)
                                        },
                                        testTag = "maps_source_card_$idx"
                                    )
                                }
                            }
                        }

                        // Google Search Grounding Sources & Search EntryPoint Widget
                        if (res.webSources.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = strings.sourcesTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                res.webSources.forEachIndexed { idx, web ->
                                    OutlinedButton(
                                        onClick = { uriHandler.openUri(web.uri) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("search_source_link_$idx")
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = web.title,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = web.title,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        res.searchEntryPointHtml?.takeIf { it.isNotBlank() }?.let { html ->
                            SearchSuggestionsWebView(html = html)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LinearBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MapsPlaceSourceCard(
    place: MapsSourceAttribution,
    useAsDestinationLabel: String,
    onOpenUri: () -> Unit,
    onUseAsDestination: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = place.title,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                if (place.uri.isNotBlank()) {
                    Text(
                        text = "maps.google.com",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FilledTonalButton(
                onClick = onUseAsDestination,
                modifier = Modifier.height(40.dp)
            ) {
                Text(useAsDestinationLabel, style = MaterialTheme.typography.labelMedium)
            }
            if (place.uri.isNotBlank()) {
                OutlinedButton(
                    onClick = onOpenUri,
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = place.title,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionsWebView(html: String) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL("https://www.google.com", html, "text/html", "UTF-8", null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag("google_search_suggestions_widget")
    )
}
