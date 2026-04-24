package com.bartixxx.oneplusarbchecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bartixxx.oneplusarbchecker.data.*
import com.bartixxx.oneplusarbchecker.ui.theme.AmIFusedTheme
import com.bartixxx.oneplusarbchecker.worker.ArbCheckWorker
import com.bartixxx.oneplusarbchecker.utils.HapticUtils
import com.bartixxx.oneplusarbchecker.utils.SystemUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission status handled
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val settingsRepo = SettingsRepository(this)
        
        // Schedule work only if NOT first run (otherwise wizard handles it)
        lifecycleScope.launch {
             if (!settingsRepo.firstRunFlow.first()) {
                 val interval = settingsRepo.checkIntervalFlow.first()
                 scheduleBackgroundWork(interval)
             }
        }

        setContent {
            AmIFusedTheme {
                val firstRun by settingsRepo.firstRunFlow.collectAsState(initial = false)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        MainTopAppBar(settingsRepo)
                    }
                ) { innerPadding ->
                    FusedStatusScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                    
                    if (firstRun) {
                        WelcomeDialog(settingsRepo) { interval, notifications ->
                            scheduleBackgroundWork(interval)
                            if (notifications) {
                                checkNotificationPermission()
                            }
                        }
                    }
                }
            }
        }
    }

    fun scheduleBackgroundWork(intervalHours: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ArbCheckWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "arb_check_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    public fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

// Sealed class for clean state representation in AnimatedContent
private sealed class CheckResult {
    data object Loading : CheckResult()
    data class Success(
        val isFused: Boolean?,
        val isUndetectable: Boolean,
        val statusText: String,
        val detailsText: String,
        val warningText: String?,
        val deviceData: DeviceData?,
        val rootArb: Int? = null,
        val hasBarometer: Boolean = true,
        val widevineInfo: Pair<String, String>? = null,
        val isBootloaderUnlocked: Boolean = false,
        val bootloaderDebugInfo: String = "",
        val model: String = ""
    ) : CheckResult()
    data class Error(val message: String) : CheckResult()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FusedStatusScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    
    var checkResult by remember { mutableStateOf<CheckResult>(CheckResult.Loading) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf(context.getString(R.string.initializing)) }
    var currentDeviceData by remember { mutableStateOf<DeviceData?>(null) }
    var currentRegion by remember { mutableStateOf<String?>(null) }
    
    val sheetState = rememberModalBottomSheetState()
    var showHistorySheet by remember { mutableStateOf(false) }
    
    val lastCheckTime by settingsRepo.lastCheckTimestampFlow.collectAsState(initial = 0L)
    val firstRun by settingsRepo.firstRunFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    fun checkStatus() {
        scope.launch(Dispatchers.IO) {
            try {
                isLoading = true
                loadingMessage = context.getString(R.string.reading_info)
                
                // Ensure Installation ID exists
                var installId = settingsRepo.installationIdFlow.first()
                if (installId == null) {
                    installId = java.util.UUID.randomUUID().toString()
                    settingsRepo.setInstallationId(installId)
                }
                
                // 1. Get Device Info
                val model = SystemUtils.getSystemProperty("ro.product.model") ?: context.getString(R.string.unknown)
                val version = SystemUtils.getSystemProperty("ro.build.display.id") ?: context.getString(R.string.unknown)

                val rootModeEnabled = settingsRepo.rootModeEnabledFlow.first()
                var rootArb: Int? = null
                
                if (rootModeEnabled && SystemUtils.isRootAvailable()) {
                    loadingMessage = context.getString(R.string.checking_root)
                    // Try xbl_config then xbl
                    val partitionPath = SystemUtils.getPartitionPath("xbl_config") 
                        ?: SystemUtils.getPartitionPath("xbl")
                        
                    if (partitionPath != null) {
                        val tempFile = File(context.cacheDir, "xbl_img.img")
                        val success = SystemUtils.runRootCommand("dd if=$partitionPath of=${tempFile.absolutePath} bs=4096 count=1024")
                        if (success && tempFile.exists()) {
                            rootArb = com.bartixxx.oneplusarbchecker.utils.ArbExtractor.extractArbFromImage(tempFile)
                            tempFile.delete()
                        }
                    }
                }

                loadingMessage = context.getString(R.string.fetching_db)
                // 2. Fetch Database
                val api = com.bartixxx.oneplusarbchecker.data.RetrofitInstance.api
                val database = api.getDatabase()
                val deviceData = database[model]
                currentDeviceData = deviceData

                // Determine conversion status for telemetry
                val hasBarometer = SystemUtils.hasBarometer(context)
                val widevineInfo = SystemUtils.getWidevineInfo()
                val isBootloaderUnlocked = SystemUtils.isBootloaderUnlocked()
                
                val isBarometerRelevant = model.contains("PLK", ignoreCase = true) || 
                                         model.contains("PJZ", ignoreCase = true) || 
                                         model.contains("PJE", ignoreCase = true) || 
                                         model.contains("CPH274", ignoreCase = true) || 
                                         model.contains("CPH28", ignoreCase = true) || 
                                         (deviceData?.deviceName?.contains("OnePlus 15", ignoreCase = true) == true) ||
                                         (deviceData?.deviceName?.contains("OnePlus 13", ignoreCase = true) == true) ||
                                         (deviceData?.deviceName?.contains("OnePlus 12R", ignoreCase = true) == true)

                val isWidevineSuspicious = widevineInfo != null && (
                    (widevineInfo.first != "L1" && !isBootloaderUnlocked) || 
                    (widevineInfo.second != "Unknown" && widevineInfo.second != "N/A" && widevineInfo.second.length > 6)
                )
                val isConverted = (!hasBarometer && isBarometerRelevant) || isWidevineSuspicious

                launch { 
                    try { 
                        api.recordHit(
                            installId = installId,
                            model = model,
                            version = version,
                            isConverted = isConverted,
                            isManual = true
                        ) 
                    } catch (e: Exception) { 
                        // Silent failure for production
                    } 
                }

                if (deviceData != null || rootArb != null) {
                    
                    var matchedVersion = deviceData?.versions?.get(version)
                    
                    if (matchedVersion == null && deviceData != null) {
                         val key = deviceData.versions.keys.find { it.contains(version, ignoreCase = true) || version.contains(it, ignoreCase = true) }
                         if (key != null) {
                             matchedVersion = deviceData.versions[key]
                         }
                    }
                    
                    // Get current region
                    currentRegion = matchedVersion?.regions?.joinToString(", ")
                    
                    var warningText: String? = null
                    var isFused: Boolean?
                    var statusText: String
                    
                    if (rootArb != null) {
                        val dbArbText = if (matchedVersion != null) context.getString(R.string.db_label, matchedVersion.arb) else ""
                        statusText = "ARB: $rootArb$dbArbText"
                        isFused = rootArb > 0
                        
                        // Compare with DB if available
                        if (matchedVersion != null && matchedVersion.arb != rootArb) {
                             warningText = context.getString(R.string.arb_mismatch_warning, matchedVersion.arb, rootArb)
                        }
                    } else if (matchedVersion != null) {
                        val arb = matchedVersion.arb
                        if (matchedVersion.isHardcoded) {
                            isFused = null
                            statusText = context.getString(R.string.status_undetectable)

                            // Check for future updates even for undetectable versions
                            val maxArb = deviceData?.versions?.values?.filter { !it.isHardcoded }?.maxOfOrNull { it.arb } ?: 0
                            if (maxArb > 0) {
                                warningText = context.getString(R.string.warning_future_update, maxArb)
                            }
                        } else if (arb > 0) {
                            isFused = true
                            statusText = context.getString(R.string.arb_index, arb)
                        } else {
                            isFused = false
                            statusText = context.getString(R.string.arb_index, arb)
                            
                            // Check for future updates
                            val maxArb = deviceData?.versions?.values?.filter { !it.isHardcoded }?.maxOfOrNull { it.arb } ?: 0
                            if (maxArb > arb) {
                                warningText = context.getString(R.string.warning_future_update, maxArb)
                            }
                        }
                    } else {
                        statusText = context.getString(R.string.status_unknown_version)
                        isFused = null
                    }
                    
                    val detailsText = if (deviceData != null) {
                        context.getString(R.string.details_format, model, version, deviceData.deviceName)
                    } else {
                        context.getString(R.string.details_format_simple, model, version)
                    }

                    // Check if root was actually available if mode was enabled (even if we have DB data)
                    if (rootModeEnabled && !SystemUtils.isRootAvailable()) {
                        val rootWarning = context.getString(R.string.root_access_denied_desc)
                        warningText = if (warningText == null) rootWarning else "$warningText\n\n$rootWarning"
                    }

                    val hasBarometer = SystemUtils.hasBarometer(context)
                    val widevineInfo = SystemUtils.getWidevineInfo()
                    val isBootloaderUnlocked = SystemUtils.isBootloaderUnlocked()
                    val bootloaderDebugInfo = SystemUtils.getBootloaderDebugInfo()

                    checkResult = CheckResult.Success(
                        isFused = isFused,
                        isUndetectable = matchedVersion?.isHardcoded == true,
                        statusText = statusText,
                        detailsText = detailsText,
                        warningText = warningText,
                        deviceData = deviceData,
                        rootArb = rootArb,
                        hasBarometer = hasBarometer,
                        widevineInfo = widevineInfo,
                        isBootloaderUnlocked = isBootloaderUnlocked,
                        bootloaderDebugInfo = bootloaderDebugInfo,
                        model = model
                    )
                    
                    // Update timestamp on successful manual check
                    settingsRepo.setLastCheckTimestamp(System.currentTimeMillis())
                    
                    // Save last known ARB for background monitoring
                    val finalArb = rootArb ?: matchedVersion?.arb ?: -1
                    if (finalArb != -1) {
                        settingsRepo.setLastKnownArb(finalArb)
                    }
                    settingsRepo.setLastKnownBuildId(version)

                    // Haptic feedback based on result
                    when (isFused) {
                        true -> HapticUtils.vibrateWarning(context)
                        false -> HapticUtils.vibrateSuccess(context)
                        else -> HapticUtils.vibrateTick(context)
                    }

                } else {
                    val detailsText = context.getString(R.string.details_format_simple, model, version)
                    
                    var warningText: String? = null
                    // Check if root was actually available if mode was enabled
                    if (rootModeEnabled && !SystemUtils.isRootAvailable()) {
                        warningText = context.getString(R.string.root_access_denied_desc)
                    }

                    val hasBarometer = SystemUtils.hasBarometer(context)
                    val widevineInfo = SystemUtils.getWidevineInfo()
                    val isBootloaderUnlocked = SystemUtils.isBootloaderUnlocked()
                    val bootloaderDebugInfo = SystemUtils.getBootloaderDebugInfo()

                    checkResult = CheckResult.Success(
                        isFused = null,
                        isUndetectable = false,
                        statusText = context.getString(R.string.status_unsupported),
                        detailsText = detailsText,
                        warningText = warningText,
                        deviceData = null,
                        hasBarometer = hasBarometer,
                        widevineInfo = widevineInfo,
                        isBootloaderUnlocked = isBootloaderUnlocked,
                        bootloaderDebugInfo = bootloaderDebugInfo,
                        model = model
                    )
                }

            } catch (e: Exception) {
                checkResult = CheckResult.Error(e.message ?: context.getString(R.string.unknown_error))
                HapticUtils.vibrateError(context)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(firstRun) {
        if (firstRun == false) {
            checkStatus()
        }
    }

    // Pull-to-refresh
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = {
            HapticUtils.vibrateClick(context)
            checkStatus()
        },
        modifier = modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedContent(
                    targetState = checkResult,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(400)) +
                            slideInVertically(animationSpec = tween(400)) { it / 4 })
                            .togetherWith(
                                fadeOut(animationSpec = tween(200)) +
                                    slideOutVertically(animationSpec = tween(200)) { -it / 4 }
                            )
                    },
                    contentAlignment = Alignment.Center,
                    label = "status_transition"
                ) { result ->
                    when (result) {
                        is CheckResult.Loading -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center

                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(text = loadingMessage, fontSize = 18.sp)
                            }
                        }
                        is CheckResult.Success -> {
                            StatusContent(
                                isFused = result.isFused,
                                isUndetectable = result.isUndetectable,
                                statusText = result.statusText,
                                detailsText = result.detailsText,
                                warningText = result.warningText,
                                deviceData = result.deviceData,
                                currentRegion = currentRegion,
                                lastCheckTime = lastCheckTime,
                                isRootVerified = result.rootArb != null,
                                hasBarometer = result.hasBarometer,
                                widevineInfo = result.widevineInfo,
                                isBootloaderUnlocked = result.isBootloaderUnlocked,
                                bootloaderDebugInfo = result.bootloaderDebugInfo,
                                model = result.model,
                                onShowHistory = { showHistorySheet = true }
                            )
                        }
                        is CheckResult.Error -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.error_prefix),
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = result.message,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    HapticUtils.vibrateClick(context)
                    checkStatus()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                 Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.check_now))
            }
            
            if (showHistorySheet && currentDeviceData != null) {
                HistoryBottomSheet(
                    deviceData = currentDeviceData!!,
                    currentRegion = currentRegion,
                    sheetState = sheetState,
                    onDismiss = { showHistorySheet = false }
                )
            }
        }
    }
} 

@Composable
private fun StatusContent(
    isFused: Boolean?,
    isUndetectable: Boolean,
    statusText: String,
    detailsText: String,
    warningText: String?,
    deviceData: DeviceData?,
    currentRegion: String?,
    lastCheckTime: Long,
    isRootVerified: Boolean,
    hasBarometer: Boolean,
    widevineInfo: Pair<String, String>?,
    isBootloaderUnlocked: Boolean,
    bootloaderDebugInfo: String,
    model: String,
    onShowHistory: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val bigText = when {
            isUndetectable -> stringResource(R.string.big_text_undetectable)
            isFused == true -> stringResource(R.string.big_text_fused)
            isFused == false -> stringResource(R.string.big_text_safe)
            else -> stringResource(R.string.big_text_unknown)
        }
        
        val textColor = when {
            isUndetectable -> Color(0xFFFF9800) // Orange
            isFused == true -> MaterialTheme.colorScheme.error
            isFused == false -> Color(0xFF4CAF50)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Text(
            text = bigText,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            lineHeight = 44.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = statusText, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        
        if (isRootVerified || deviceData != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isRootVerified) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.verified_root)) },
                        leadingIcon = { Text("🛡️", fontSize = 14.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                            leadingIconContentColor = MaterialTheme.colorScheme.primary
                        )
                    )
                } else if (deviceData != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.verified_db)) },
                        leadingIcon = { Text("ℹ️", fontSize = 14.sp) }
                    )
                }

                if (currentRegion != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.region_label, currentRegion)) },
                        leadingIcon = { Text("🌍", fontSize = 14.sp) }
                    )
                }
            }
        }
        
        if (warningText != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = warningText,
                    color = Color(0xFFE65100),
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(text = detailsText, textAlign = TextAlign.Center)
        
        if (deviceData != null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = {
                HapticUtils.vibrateClick(context)
                onShowHistory()
            }) {
                Text(stringResource(R.string.view_history))
            }
        }

        val isBarometerRelevant = model.contains("PLK", ignoreCase = true) || // OP13/15 CN
                                 model.contains("PJZ", ignoreCase = true) || // OP13 CN
                                 model.contains("PJE", ignoreCase = true) || // Ace 3 CN
                                 model.contains("CPH274", ignoreCase = true) || // OP13 Global/IN
                                 model.contains("CPH28", ignoreCase = true) || // Potential OP13/12R
                                 (deviceData?.deviceName?.contains("OnePlus 15", ignoreCase = true) == true) ||
                                 (deviceData?.deviceName?.contains("OnePlus 13", ignoreCase = true) == true) ||
                                 (deviceData?.deviceName?.contains("OnePlus 12R", ignoreCase = true) == true)

        val isWidevineSuspicious = widevineInfo != null && (
            (widevineInfo.first != "L1" && !isBootloaderUnlocked) || 
            (widevineInfo.second != "Unknown" && widevineInfo.second != "N/A" && widevineInfo.second.length > 6)
        )
        
        val hasConversionSymptoms = (!hasBarometer && isBarometerRelevant) || isWidevineSuspicious
        
        if (hasConversionSymptoms) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚠️ " + stringResource(R.string.conversion_warning_title),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (!hasBarometer && isBarometerRelevant) {
                        Text(
                            text = "• " + stringResource(R.string.conversion_warning_barometer),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    if (isWidevineSuspicious && widevineInfo != null) {
                        val reason = if (widevineInfo.first != "L1" && !isBootloaderUnlocked) {
                            stringResource(R.string.conversion_warning_widevine_level, widevineInfo.first)
                        } else if (widevineInfo.second.length > 6) {
                            stringResource(R.string.conversion_warning_widevine_id)
                        } else {
                            null
                        }
                        
                        if (reason != null) {
                            Text(
                                text = "• $reason",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.conversion_warning_footer),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
                }
            }
        }
        
        if (lastCheckTime > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
            Text(
                text = stringResource(R.string.last_checked, dateFormat.format(java.util.Date(lastCheckTime))),
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 32.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.data_source_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isRootVerified) 
                    stringResource(R.string.data_source_description_root) 
                else 
                    stringResource(R.string.data_source_description_db),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                lineHeight = 16.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(settingsRepo: SettingsRepository) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = { HapticUtils.vibrateClick(context); showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
    )

    if (showSettings) {
        SettingsDialog(settingsRepo) {
            showSettings = false
        }
    }
}

@Composable
fun WelcomeDialog(settingsRepo: SettingsRepository, onFinished: (Long, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Local state for the wizard before saving
    var interval by remember { mutableStateOf(1L) }
    var notifications by remember { mutableStateOf(true) }
    var rootMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val available = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SystemUtils.isRootAvailable()
        }
        rootMode = available
    }

    AlertDialog(
        onDismissRequest = { /* Prevent dismissal */ },
        title = { Text(stringResource(R.string.welcome_title)) },
        text = {
            Column {
                Text(stringResource(R.string.welcome_subtitle))
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(stringResource(R.string.interval_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.interval_subtitle), fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 1L, onClick = { HapticUtils.vibrateTick(context); interval = 1L })
                    Text(stringResource(R.string.interval_1h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 6L, onClick = { HapticUtils.vibrateTick(context); interval = 6L })
                    Text(stringResource(R.string.interval_6h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 12L, onClick = { HapticUtils.vibrateTick(context); interval = 12L })
                    Text(stringResource(R.string.interval_12h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 24L, onClick = { HapticUtils.vibrateTick(context); interval = 24L })
                    Text(stringResource(R.string.interval_24h))
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = notifications,
                        onCheckedChange = { HapticUtils.vibrateTick(context); notifications = it }
                    )
                    Column {
                         Text(stringResource(R.string.enable_notifications))
                         Text(stringResource(R.string.notification_subtitle), fontSize = 12.sp, color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                var isRootAvailable by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    isRootAvailable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SystemUtils.isRootAvailable()
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (isRootAvailable) 1f else 0.5f)
                ) {
                    Checkbox(
                        checked = rootMode,
                        enabled = isRootAvailable,
                        onCheckedChange = { HapticUtils.vibrateTick(context); rootMode = it }
                    )
                    Column {
                        Text(stringResource(R.string.enable_root))
                        Text(
                            text = if (isRootAvailable) stringResource(R.string.enable_root_subtitle) else stringResource(R.string.root_not_available),
                            fontSize = 12.sp,
                            color = if (isRootAvailable) Color.Gray else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        settingsRepo.setCheckInterval(interval)
                        settingsRepo.setNotificationsEnabled(notifications)
                        settingsRepo.setRootModeEnabled(rootMode)
                        settingsRepo.setFirstRunCompleted()
                        onFinished(interval, notifications)
                    }
                }
            ) {
                Text(stringResource(R.string.get_started))
            }
        }
    )
}

@Composable
fun SettingsDialog(settingsRepo: SettingsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val currentInterval by settingsRepo.checkIntervalFlow.collectAsState(initial = 1L)
    val notificationsEnabled by settingsRepo.notificationsEnabledFlow.collectAsState(initial = false)
    val rootModeEnabled by settingsRepo.rootModeEnabledFlow.collectAsState(initial = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Text(stringResource(R.string.interval_title), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 1L, onClick = { HapticUtils.vibrateTick(context); scope.launch { settingsRepo.setCheckInterval(1L); (context as MainActivity).scheduleBackgroundWork(1L) } })
                    Text(stringResource(R.string.interval_1h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 6L, onClick = { HapticUtils.vibrateTick(context); scope.launch { settingsRepo.setCheckInterval(6L); (context as MainActivity).scheduleBackgroundWork(6L) } })
                    Text(stringResource(R.string.interval_6h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 12L, onClick = { HapticUtils.vibrateTick(context); scope.launch { settingsRepo.setCheckInterval(12L); (context as MainActivity).scheduleBackgroundWork(12L) } })
                    Text(stringResource(R.string.interval_12h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 24L, onClick = { HapticUtils.vibrateTick(context); scope.launch { settingsRepo.setCheckInterval(24L); (context as MainActivity).scheduleBackgroundWork(24L) } })
                    Text(stringResource(R.string.interval_24h))
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = notificationsEnabled,
                        onCheckedChange = { enabled ->
                            HapticUtils.vibrateTick(context)
                            scope.launch { 
                                settingsRepo.setNotificationsEnabled(enabled)
                                if (enabled) {
                                    (context as MainActivity).checkNotificationPermission() // Re-request if re-enabling
                                }
                            }
                        }
                    )
                    Column {
                        Text(stringResource(R.string.enable_notifications))
                        Text(stringResource(R.string.notification_subtitle), fontSize = 12.sp, color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                var isRootAvailable by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    isRootAvailable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SystemUtils.isRootAvailable()
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (isRootAvailable) 1f else 0.5f)
                ) {
                    Checkbox(
                        checked = rootModeEnabled,
                        enabled = isRootAvailable,
                        onCheckedChange = { enabled ->
                            HapticUtils.vibrateTick(context)
                            scope.launch { settingsRepo.setRootModeEnabled(enabled) }
                        }
                    )
                    Column {
                        Text(stringResource(R.string.enable_root))
                        Text(
                            text = if (isRootAvailable) stringResource(R.string.enable_root_subtitle) else stringResource(R.string.root_not_available),
                            fontSize = 12.sp,
                            color = if (isRootAvailable) Color.Gray else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryBottomSheet(
    deviceData: DeviceData,
    currentRegion: String?,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    val sortedVersions = deviceData.versions.entries
        .sortedByDescending { it.value.firstSeen ?: it.key }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
                fontWeight = FontWeight.Bold
            )
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(sortedVersions) { (version, data) ->
                    val arbColor = when {
                        data.isHardcoded -> Color(0xFFFF9800)
                        data.arb > 0 -> MaterialTheme.colorScheme.error
                        else -> Color(0xFF4CAF50)
                    }

                    val arbText = if (data.isHardcoded) {
                        stringResource(R.string.arb_undetectable)
                    } else {
                        stringResource(R.string.arb_index, data.arb)
                    }
                    
                    ListItem(
                        headlineContent = { 
                            Text(
                                text = version,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            ) 
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    text = arbText,
                                    color = arbColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Region chips
                                    data.regions.forEach { region ->
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(region, fontSize = 11.sp) },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                    
                                    // Status badge
                                    val statusColor = when (data.status) {
                                        "current" -> Color(0xFF4CAF50)
                                        "archived" -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    val statusLabel = when (data.status) {
                                        "current" -> stringResource(R.string.status_current)
                                        "archived" -> stringResource(R.string.status_archived)
                                        else -> data.status
                                    }
                                    Text(
                                        text = "• $statusLabel",
                                        fontSize = 11.sp,
                                        color = statusColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                
                                // First seen date
                                if (data.firstSeen != null) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.first_seen_label, data.firstSeen),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
