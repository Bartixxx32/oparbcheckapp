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
        
        // Share state needs to be managed here to pass to TopBar (action) and Screen (to update it)
        var shareText by mutableStateOf("")

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
                        MainTopAppBar(settingsRepo, onShare = {
                            if (shareText.isNotEmpty()) {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, null)
                                startActivity(shareIntent)
                            }
                        })
                    }
                ) { innerPadding ->
                    FusedStatusScreen(
                        modifier = Modifier.padding(innerPadding),
                        onUpdateShareText = { newText -> shareText = newText }
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
        val deviceData: DeviceData?
    ) : CheckResult()
    data class Error(val message: String) : CheckResult()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FusedStatusScreen(
    modifier: Modifier = Modifier,
    onUpdateShareText: (String) -> Unit
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
    val scope = rememberCoroutineScope()

    fun checkStatus() {
        scope.launch(Dispatchers.IO) {
            try {
                isLoading = true
                loadingMessage = context.getString(R.string.reading_info)
                // 1. Get Device Info
                val model = SystemUtils.getSystemProperty("ro.product.model") ?: context.getString(R.string.unknown)
                val version = SystemUtils.getSystemProperty("ro.build.display.id") ?: context.getString(R.string.unknown)

                loadingMessage = context.getString(R.string.fetching_db)
                // 2. Fetch Database
                val api = com.bartixxx.oneplusarbchecker.data.RetrofitInstance.api
                launch { try { api.recordHit() } catch (e: Exception) { e.printStackTrace() } }
                val database = api.getDatabase()
                val deviceData = database[model]
                currentDeviceData = deviceData

                if (deviceData != null) {
                    
                    var matchedVersion = deviceData.versions[version]
                    
                    if (matchedVersion == null) {
                         val key = deviceData.versions.keys.find { it.contains(version, ignoreCase = true) || version.contains(it, ignoreCase = true) }
                         if (key != null) {
                             matchedVersion = deviceData.versions[key]
                         }
                    }
                    
                    // Get current region
                    currentRegion = matchedVersion?.regions?.joinToString(", ")
                    
                    val shareMsg: String
                    var warningText: String? = null
                    val isFused: Boolean?
                    val statusText: String
                    
                    if (matchedVersion != null) {
                        val arb = matchedVersion.arb
                        if (matchedVersion.isHardcoded) {
                            isFused = null
                            statusText = context.getString(R.string.status_undetectable)
                            shareMsg = context.getString(R.string.share_msg_unknown, model)

                            // Check for future updates even for undetectable versions
                            val maxArb = deviceData.versions.values.filter { !it.isHardcoded }.maxOfOrNull { it.arb } ?: 0
                            if (maxArb > 0) {
                                warningText = context.getString(R.string.warning_future_update, maxArb)
                            }
                        } else if (arb > 0) {
                            isFused = true
                            statusText = context.getString(R.string.arb_index, arb)
                            shareMsg = context.getString(R.string.share_msg_fused, model, arb)
                        } else {
                            isFused = false
                            statusText = context.getString(R.string.arb_index, arb)
                            shareMsg = context.getString(R.string.share_msg_safe, model)
                            
                            // Check for future updates
                            val maxArb = deviceData.versions.values.filter { !it.isHardcoded }.maxOfOrNull { it.arb } ?: 0
                            if (maxArb > arb) {
                                warningText = context.getString(R.string.warning_future_update, maxArb)
                            }
                        }
                    } else {
                        statusText = context.getString(R.string.status_unknown_version)
                        shareMsg = context.getString(R.string.share_msg_unknown, model)
                        isFused = null
                    }
                    
                    val detailsText = context.getString(R.string.details_format, model, version, deviceData.deviceName)
                    onUpdateShareText(shareMsg)
                    
                    checkResult = CheckResult.Success(
                        isFused = isFused,
                        isUndetectable = matchedVersion?.isHardcoded == true,
                        statusText = statusText,
                        detailsText = detailsText,
                        warningText = warningText,
                        deviceData = deviceData
                    )
                    
                    // Haptic feedback based on result
                    when (isFused) {
                        true -> HapticUtils.vibrateWarning(context)
                        false -> HapticUtils.vibrateSuccess(context)
                        else -> HapticUtils.vibrateTick(context)
                    }
                    
                    // Update timestamp on successful manual check
                    settingsRepo.setLastCheckTimestamp(System.currentTimeMillis())

                } else {
                   val detailsText = context.getString(R.string.details_format_simple, model, version)
                   onUpdateShareText(context.getString(R.string.share_msg_unsupported, model))
                   checkResult = CheckResult.Success(
                       isFused = null,
                       isUndetectable = false,
                       statusText = context.getString(R.string.status_unsupported),
                       detailsText = detailsText,
                       warningText = null,
                       deviceData = null
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

    LaunchedEffect(Unit) {
        checkStatus()
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
        
        if (currentRegion != null) {
            Spacer(modifier = Modifier.height(8.dp))
            AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.region_label, currentRegion)) },
                leadingIcon = { Text("🌍", fontSize = 14.sp) }
            )
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
        
        if (lastCheckTime > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
            Text(
                text = stringResource(R.string.last_checked, dateFormat.format(java.util.Date(lastCheckTime))),
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(settingsRepo: SettingsRepository, onShare: () -> Unit) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = { HapticUtils.vibrateClick(context); onShare() }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
            }
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        settingsRepo.setCheckInterval(interval)
                        settingsRepo.setNotificationsEnabled(notifications)
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
                    Text(stringResource(R.string.enable_notifications))
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
