package com.droidcast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.theme.DroidCastTheme
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusReady
import com.example.ui.theme.StatusWarning

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CastSessionCoordinator.initialize(this)
        handleDeepLinkIntent(intent)
        setContent {
            DroidCastTheme(darkTheme = true) {
                DroidCastApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "droidcast" && data.host == "connect") {
            val wsParam = data.getQueryParameter("ws")?.trim()
            if (!wsParam.isNullOrEmpty()) {
                CastSessionCoordinator.updateManualAddress(wsParam.removePrefix("ws://"))
                CastSessionCoordinator.connectToSignaling(wsParam)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CastSessionCoordinator.onConfigurationChanged()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroidCastApp(viewModel: CastViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var showSettingsSheet by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Whether RECORD_AUDIO / POST_NOTIFICATIONS were granted or denied,
        // proceed with starting casting (AudioCapturer gracefully reports Audio: unavailable if denied).
        viewModel.startCasting()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CastActionEvent.RequestMediaProjectionPermission -> {
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
        }
    }

    fun onStartCastingClick() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (state.includeSystemAudio &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.startCasting()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HeaderBar(
                    signalingState = state.signalingState,
                    justReconnected = state.justReconnected,
                    onRefreshClick = viewModel::refreshDiscovery,
                    onSettingsClick = { showSettingsSheet = true },
                )

                AnimatedVisibility(visible = state.error != null) {
                    state.error?.let { error ->
                        ErrorCard(
                            error = error,
                            onRetry = viewModel::retryConnection,
                            onDismiss = viewModel::dismissError,
                        )
                    }
                }

                when (val castStatus = state.castStatus) {
                    is CastStatus.Idle -> {
                        HeroCastCard(
                            canStart = state.signalingState is SignalingState.Connected && state.availableReceivers.isNotEmpty(),
                            selectedReceiver = state.availableReceivers.firstOrNull {
                                it.deviceId == state.selectedReceiverId
                            } ?: state.availableReceivers.firstOrNull(),
                            onStartCasting = ::onStartCastingClick,
                        )
                    }

                    is CastStatus.PairingPending -> {
                        PairingCodeCard(
                            status = castStatus,
                            onCancel = viewModel::stopCasting,
                        )
                    }

                    is CastStatus.WaitingMediaPermission,
                    is CastStatus.Starting,
                    is CastStatus.Casting,
                    is CastStatus.Reconnecting -> {
                        ActiveCastCard(
                            status = castStatus,
                            media = state.mediaAvailability,
                            telemetry = state.telemetry,
                            qualityPreset = state.qualityPreset,
                            onQualityChange = viewModel::setQualityPreset,
                            onStopCasting = viewModel::stopCasting,
                        )
                    }
                }

                ReceiversSection(
                    state = state,
                    onSelectReceiver = viewModel::selectReceiver,
                    onConnectEndpoint = viewModel::connectEndpoint,
                    onManualAddressChange = viewModel::onManualAddressChanged,
                    onConnectManual = viewModel::connectManual,
                )

                PrivacyFooter()
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = ObsidianSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            SettingsSheetContent(
                state = state,
                logs = logs,
                onQualityChange = viewModel::setQualityPreset,
                onIncludeSystemAudioChange = viewModel::setIncludeSystemAudio,
                onClose = { showSettingsSheet = false },
            )
        }
    }
}

@Composable
private fun HeaderBar(
    signalingState: SignalingState,
    justReconnected: Boolean,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                StatusPill(signalingState = signalingState, justReconnected = justReconnected)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("refresh_discovery_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh_discovery),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("settings_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(signalingState: SignalingState, justReconnected: Boolean) {
    val (dotColor, label) = when {
        justReconnected -> StatusReady to "Reconnected"
        signalingState is SignalingState.Connected -> StatusReady to "Online"
        signalingState is SignalingState.Connecting -> StatusWarning to "Connecting"
        signalingState is SignalingState.Reconnecting -> StatusWarning to "Reconnecting"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "Offline"
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HeroCastCard(
    canStart: Boolean,
    selectedReceiver: ReceiverPeer?,
    onStartCasting: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianBorder),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cast,
                        contentDescription = stringResource(R.string.cd_cast_icon),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column {
                    Text(
                        text = if (selectedReceiver != null) {
                            "Ready for ${selectedReceiver.name}"
                        } else {
                            "Waiting for laptop receiver"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (selectedReceiver != null) {
                            "Requires Android screen capture approval before streaming."
                        } else {
                            "Connect to your laptop receiver below to start casting."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = onStartCasting,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("start_casting_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Cast,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.start_casting),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun PairingCodeCard(
    status: CastStatus.PairingPending,
    onCancel: () -> Unit,
) {
    val formattedCode = if (status.pairingCode.length == 6) {
        "${status.pairingCode.substring(0, 3)} ${status.pairingCode.substring(3)}"
    } else {
        status.pairingCode
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.pairing_code_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = formattedCode,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                letterSpacing = 6.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("pairing_code_text"),
            )

            Text(
                text = "Enter this code on ${status.receiverName} to authorize casting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("cancel_pairing_button"),
                border = BorderStroke(1.dp, ObsidianBorder),
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveCastCard(
    status: CastStatus,
    media: MediaAvailability,
    telemetry: CastTelemetry,
    qualityPreset: QualityPreset,
    onQualityChange: (QualityPreset) -> Unit,
    onStopCasting: () -> Unit,
) {
    val receiverName = when (status) {
        is CastStatus.WaitingMediaPermission -> status.receiverName
        is CastStatus.Starting -> status.receiverName
        is CastStatus.Casting -> status.receiverName
        is CastStatus.Reconnecting -> status.receiverName
        else -> "My Laptop"
    }

    val statusText = when (status) {
        is CastStatus.WaitingMediaPermission -> "Approve Android screen capture…"
        is CastStatus.Starting -> "Starting WebRTC stream to $receiverName…"
        is CastStatus.Casting -> "Casting screen to $receiverName"
        is CastStatus.Reconnecting -> "Connection interrupted — reconnecting to $receiverName…"
        else -> ""
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CastConnected,
                    contentDescription = null,
                    tint = if (status is CastStatus.Reconnecting) StatusWarning else StatusReady,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (status is CastStatus.Casting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CapabilityChip(
                        icon = Icons.Filled.CheckCircle,
                        text = stringResource(R.string.video_available),
                        active = true,
                        modifier = Modifier.weight(1f),
                    )
                    CapabilityChip(
                        icon = if (media.audioAvailable) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        text = stringResource(
                            if (media.audioAvailable) R.string.audio_available else R.string.audio_unavailable
                        ),
                        active = media.audioAvailable,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!media.audioAvailable && media.audioReason.isNotBlank()) {
                    Text(
                        text = media.audioReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (telemetry.width > 0 || telemetry.targetBitrateKbps > 0) {
                    val qualityColor = when (telemetry.networkQuality) {
                        NetworkQualityLevel.EXCELLENT, NetworkQualityLevel.GOOD -> StatusReady
                        NetworkQualityLevel.FAIR -> StatusWarning
                        NetworkQualityLevel.POOR -> StatusError
                    }
                    Surface(
                        color = ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("adaptive_telemetry_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(qualityColor)
                                    )
                                    Text(
                                        text = "${telemetry.networkQuality.label} · ${telemetry.adaptiveTierLabel}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (telemetry.targetBitrateKbps > 0) {
                                    Text(
                                        text = "Cap ${telemetry.targetBitrateKbps} kbps",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            val resPart = if (telemetry.width > 0) {
                                "${telemetry.width}×${telemetry.height} · ${telemetry.fps} FPS"
                            } else {
                                "Initializing…"
                            }
                            val lossPart = String.format(java.util.Locale.US, "%.1f%% loss", telemetry.packetLossPercent)
                            val rttPart = telemetry.rttMs?.let { "${it} ms" } ?: "-- ms"
                            Text(
                                text = "$resPart · ${telemetry.bitrateKbps} kbps · $lossPart · $rttPart",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (qualityPreset == QualityPreset.ADAPTIVE && telemetry.adaptationReason.isNotBlank()) {
                                Text(
                                    text = telemetry.adaptationReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QualityPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = qualityPreset == preset,
                            onClick = { onQualityChange(preset) },
                            label = {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }

            Button(
                onClick = onStopCasting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusError,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("stop_casting_button"),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stop_casting),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun CapabilityChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = ObsidianSurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ObsidianBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) StatusReady else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReceiversSection(
    state: CastUiState,
    onSelectReceiver: (String) -> Unit,
    onConnectEndpoint: (DiscoveredEndpoint) -> Unit,
    onManualAddressChange: (String) -> Unit,
    onConnectManual: (String) -> Unit,
) {
    var addressDraft by remember { mutableStateOf(state.manualAddressInput) }
    val pendingLocalEdits = remember { ArrayDeque<String>() }
    LaunchedEffect(state.manualAddressInput) {
        val incoming = state.manualAddressInput
        if (pendingLocalEdits.contains(incoming)) {
            while (pendingLocalEdits.isNotEmpty() && pendingLocalEdits.first() != incoming) {
                pendingLocalEdits.removeFirst()
            }
            if (pendingLocalEdits.isNotEmpty()) {
                pendingLocalEdits.removeFirst()
            }
        } else if (incoming != addressDraft) {
            addressDraft = incoming
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.available_receivers),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (state.availableReceivers.isNotEmpty()) {
            state.availableReceivers.forEach { receiver ->
                val isSelected = receiver.deviceId == state.selectedReceiverId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectReceiver(receiver.deviceId) }
                        .testTag("receiver_item_${receiver.deviceId}"),
                    color = ObsidianSurface,
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else ObsidianBorder,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LaptopMac,
                                contentDescription = stringResource(R.string.cd_receiver_laptop),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Column {
                                Text(
                                    text = receiver.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(StatusReady)
                                    )
                                    Text(
                                        text = when (receiver.status) {
                                            "casting" -> stringResource(R.string.receiver_status_casting)
                                            else -> stringResource(R.string.receiver_status_ready)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    receiver.address?.let { addr ->
                                        Text(
                                            text = "· $addr",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (state.discoveredEndpoints.isNotEmpty()) {
            state.discoveredEndpoints.forEach { endpoint ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onConnectEndpoint(endpoint) },
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LaptopMac,
                                contentDescription = stringResource(R.string.cd_receiver_laptop),
                            )
                            Column {
                                Text(
                                    text = endpoint.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = "${endpoint.displayAddress} · ${endpoint.source}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.connect),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            Surface(
                color = ObsidianSurface,
                border = BorderStroke(1.dp, ObsidianBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (state.isScanning) {
                        stringResource(R.string.discovery_scanning)
                    } else {
                        stringResource(R.string.discovery_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Manual receiver address fallback (always available per Section 13)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianBorder),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.manual_connection_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = addressDraft,
                        onValueChange = { next ->
                            pendingLocalEdits.addLast(next)
                            if (pendingLocalEdits.size > 32) {
                                pendingLocalEdits.removeFirst()
                            }
                            addressDraft = next
                            onManualAddressChange(next)
                        },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.manual_connection_hint),
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        },
                        textStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onConnectManual(addressDraft) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = ObsidianBorder,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_address_input"),
                    )
                    Button(
                        onClick = { onConnectManual(addressDraft) },
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("connect_button"),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(text = stringResource(R.string.connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    error: DroidCastError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = StatusError,
                )
                Text(
                    text = error.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cancel))
                }
                if (error.canRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("error_retry_button"),
                    ) {
                        Text(text = stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.privacy_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheetContent(
    state: CastUiState,
    logs: List<DiagnosticLogEntry>,
    onQualityChange: (QualityPreset) -> Unit,
    onIncludeSystemAudioChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cast Settings & Diagnostics",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = onClose) {
                Text("Done")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.quality_label),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QualityPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.qualityPreset == preset,
                        onClick = { onQualityChange(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
        }

        Surface(
            color = ObsidianSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Stream phone audio",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Send media audio to the receiver and temporarily mute the phone's media output.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.includeSystemAudio,
                        onCheckedChange = onIncludeSystemAudioChange,
                        enabled = state.castStatus is CastStatus.Idle,
                    )
                }
                if (state.castStatus !is CastStatus.Idle) {
                    Text(
                        text = "This setting applies to the next casting session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Recent Logs",
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "No log events recorded yet.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        logs.take(30).forEach { entry ->
                            Text(
                                text = "${entry.timestamp} [${entry.level}] ${entry.tag}: ${entry.message}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
