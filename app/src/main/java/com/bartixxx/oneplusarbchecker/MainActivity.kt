package com.bartixxx.oneplusarbchecker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bartixxx.oneplusarbchecker.data.AmIFusedApi
import com.bartixxx.oneplusarbchecker.data.SettingsRepository
import com.bartixxx.oneplusarbchecker.ui.theme.AmIFusedTheme
import com.bartixxx.oneplusarbchecker.worker.ArbCheckWorker
import com.bartixxx.oneplusarbchecker.data.DeviceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
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
        kotlinx.coroutines.GlobalScope.launch {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FusedStatusScreen(
    modifier: Modifier = Modifier,
    onUpdateShareText: (String) -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    
    var statusText by remember { mutableStateOf(context.getString(R.string.checking)) }
    var detailsText by remember { mutableStateOf("") }

    var isFused by remember { mutableStateOf<Boolean?>(null) }
    var isLoading by remember { mutableStateOf(false) } // Default false, explicit check triggers true
    var loadingMessage by remember { mutableStateOf(context.getString(R.string.initializing)) }
    var warningText by remember { mutableStateOf<String?>(null) }
    var currentDeviceData by remember { mutableStateOf<DeviceData?>(null) }
    
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
                val model = getSystemProperty("ro.product.model") ?: context.getString(R.string.unknown)
                val version = getSystemProperty("ro.build.display.id") ?: context.getString(R.string.unknown)

                loadingMessage = context.getString(R.string.fetching_db)
                // 2. Fetch Database
                val database = com.bartixxx.oneplusarbchecker.data.RetrofitInstance.api.getDatabase()
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
                    
                    val shareMsg: String
                    if (matchedVersion != null) {
                        val arb = matchedVersion.arb
                        if (arb > 0) {
                            isFused = true
                            statusText = context.getString(R.string.arb_index, arb)
                            shareMsg = context.getString(R.string.share_msg_fused, model, arb)
                        } else {
                            isFused = false
                            statusText = context.getString(R.string.arb_index, arb)
                            shareMsg = context.getString(R.string.share_msg_safe, model)
                            
                            // Check for future updates
                            val maxArb = deviceData.versions.values.maxOfOrNull { it.arb } ?: 0
                            if (maxArb > arb) {
                                warningText = context.getString(R.string.warning_future_update, maxArb)
                            } else {
                                warningText = null
                            }
                        }
                    } else {
                        statusText = context.getString(R.string.status_unknown_version)
                        shareMsg = context.getString(R.string.share_msg_unknown, model)
                        isFused = null
                    }
                    detailsText = context.getString(R.string.details_format, model, version, deviceData.deviceName)
                    
                    onUpdateShareText(shareMsg)
                    
                    // Update timestamp on successful manual check
                    settingsRepo.setLastCheckTimestamp(System.currentTimeMillis())

                } else {
                   statusText = context.getString(R.string.status_unsupported)
                   detailsText = context.getString(R.string.details_format_simple, model, version)
                   isFused = null
                   onUpdateShareText(context.getString(R.string.share_msg_unsupported, model))
                }

            } catch (e: Exception) {
                statusText = context.getString(R.string.error_prefix)
                detailsText = e.message ?: context.getString(R.string.unknown_error)
                isFused = null
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        checkStatus()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = loadingMessage, fontSize = 18.sp)
            } else {
                val bigText = when (isFused) {
                    true -> stringResource(R.string.big_text_fused)
                    false -> stringResource(R.string.big_text_safe)
                    else -> stringResource(R.string.big_text_unknown)
                }
                
                val textColor = when (isFused) {
                    true -> MaterialTheme.colorScheme.error
                    false -> Color(0xFF4CAF50) // Standard Green
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Text(
                    text = bigText,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    lineHeight = 44.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = statusText, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                
                if (warningText != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = warningText!!,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Text(text = detailsText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                
                if (currentDeviceData != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = { showHistorySheet = true }) {
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
        
        FloatingActionButton(
            onClick = { checkStatus() },
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
                sheetState = sheetState,
                onDismiss = { showHistorySheet = false }
            )
        }
    }
} 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(settingsRepo: SettingsRepository, onShare: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }

    androidx.compose.material3.TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
            }
            IconButton(onClick = { showSettings = true }) {
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
                    RadioButton(selected = interval == 1L, onClick = { interval = 1L })
                    Text(stringResource(R.string.interval_1h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 6L, onClick = { interval = 6L })
                    Text(stringResource(R.string.interval_6h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 12L, onClick = { interval = 12L })
                    Text(stringResource(R.string.interval_12h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == 24L, onClick = { interval = 24L })
                    Text(stringResource(R.string.interval_24h))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = notifications,
                        onCheckedChange = { notifications = it }
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
                    RadioButton(selected = currentInterval == 1L, onClick = { scope.launch { settingsRepo.setCheckInterval(1L); (context as MainActivity).scheduleBackgroundWork(1L) } })
                    Text(stringResource(R.string.interval_1h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 6L, onClick = { scope.launch { settingsRepo.setCheckInterval(6L); (context as MainActivity).scheduleBackgroundWork(6L) } })
                    Text(stringResource(R.string.interval_6h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 12L, onClick = { scope.launch { settingsRepo.setCheckInterval(12L); (context as MainActivity).scheduleBackgroundWork(12L) } })
                    Text(stringResource(R.string.interval_12h))
                }
                 Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentInterval == 24L, onClick = { scope.launch { settingsRepo.setCheckInterval(24L); (context as MainActivity).scheduleBackgroundWork(24L) } })
                    Text(stringResource(R.string.interval_24h))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = notificationsEnabled,
                        onCheckedChange = { enabled ->
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

fun getSystemProperty(key: String): String? {
    try {
        val process = Runtime.getRuntime().exec("getprop $key")
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        return reader.readLine()?.trim()
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryBottomSheet(
    deviceData: DeviceData,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        val sortedVersions = deviceData.versions.entries.sortedByDescending { it.key }
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp) // Extra padding for navigation bar
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
                    val arbColor = if (data.arb > 0) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                    
                    ListItem(
                        headlineContent = { 
                            Text(
                                text = version,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            ) 
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.arb_index, data.arb),
                                color = arbColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
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
