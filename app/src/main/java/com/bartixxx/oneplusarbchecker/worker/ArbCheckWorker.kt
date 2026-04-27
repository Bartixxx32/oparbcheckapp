package com.bartixxx.oneplusarbchecker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bartixxx.oneplusarbchecker.MainActivity
import com.bartixxx.oneplusarbchecker.R
import com.bartixxx.oneplusarbchecker.data.*
import com.bartixxx.oneplusarbchecker.utils.SystemUtils
import com.bartixxx.oneplusarbchecker.utils.ArbExtractor
import kotlinx.coroutines.flow.first

class ArbCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ARB_CHECKER"
        private var appUpdateNotifiedThisBoot = false
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "ArbCheckWorker: Starting background check...")
        return try {
            val settingsRepo = SettingsRepository(applicationContext)
            val notificationsEnabled = settingsRepo.notificationsEnabledFlow.first()
            val rootModeEnabled = settingsRepo.rootModeEnabledFlow.first()
            val lastKnownArb = settingsRepo.lastKnownArbFlow.first()
            val lastKnownBuildId = settingsRepo.lastKnownBuildIdFlow.first()
            val appUpdatesEnabled = settingsRepo.appUpdatesEnabledFlow.first()
            
            // Check for App Update first (GitHub)
            if (appUpdatesEnabled && !appUpdateNotifiedThisBoot) {
                try {
                    val api = RetrofitInstance.api
                    val latestRelease = api.getLatestRelease()
                    val currentVersion = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName ?: "0.0.0"
                    
                    val latestClean = latestRelease.tagName.replace("v", "").trim()
                    val currentClean = currentVersion.replace("v", "").trim()
                    
                    if (latestClean != currentClean && isVersionNewer(currentClean, latestClean)) {
                        sendAppUpdateNotification(latestRelease.tagName)
                        appUpdateNotifiedThisBoot = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "App update check failed in background", e)
                }
            }

            if (!notificationsEnabled) {
                Log.d(TAG, "ArbCheckWorker: Notifications disabled, skipping.")
                return Result.success()
            }

            val model = SystemUtils.getSystemProperty("ro.product.model") ?: "Unknown"
            val version = SystemUtils.getSystemProperty("ro.build.display.id") ?: "Unknown"
            Log.d(TAG, "ArbCheckWorker: Device=$model, Build=$version")

            var currentArb = -1
            val buildChanged = version != lastKnownBuildId
            Log.d(TAG, "ArbCheckWorker: Build changed: $buildChanged (Last: $lastKnownBuildId)")

            // 1. Try Root ONLY IF build changed to avoid frequent "su" notifications
            if (rootModeEnabled && buildChanged && SystemUtils.isRootAvailable()) {
                Log.d(TAG, "ArbCheckWorker: Root mode active and build changed, attempting extraction...")
                
                // Try xbl_config then xbl
                val partitionPath = SystemUtils.getPartitionPath("xbl_config") 
                    ?: SystemUtils.getPartitionPath("xbl")
                
                if (partitionPath != null) {
                    val tempFile = java.io.File(applicationContext.cacheDir, "xbl_bg.img")
                    val success = SystemUtils.runRootCommand("dd if=$partitionPath of=${tempFile.absolutePath} bs=4096 count=1024")
                    if (success && tempFile.exists()) {
                        currentArb = ArbExtractor.extractArbFromImage(tempFile) ?: -1
                        Log.d(TAG, "ArbCheckWorker: Root extraction result: $currentArb from $partitionPath")
                        tempFile.delete()
                    }
                } else {
                    Log.e(TAG, "ArbCheckWorker: Neither xbl_config nor xbl partitions found!")
                }
            } else if (rootModeEnabled && !buildChanged) {
                Log.d(TAG, "ArbCheckWorker: Root mode active but build NOT changed, skipping root check.")
            }

            // 2. Fetch Database
            Log.d(TAG, "ArbCheckWorker: Fetching database...")
            val api = RetrofitInstance.api
            
            // Telemetry
            val installId = settingsRepo.installationIdFlow.first()
            val hasBarometer = SystemUtils.hasBarometer(applicationContext)
            val widevineInfo = SystemUtils.getWidevineInfo()
            val isBootloaderUnlocked = SystemUtils.isBootloaderUnlocked()
            
            val database = api.getDatabase()
            val deviceData = database[model]

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

            val telemetryEnabled = settingsRepo.telemetryEnabledFlow.first()

            // If we didn't use root, use DB as fallback
            var matchedVersion: VersionData? = null
            if (deviceData != null) {
                matchedVersion = deviceData.versions[version]
                if (matchedVersion == null) {
                    val key = deviceData.versions.keys.find { it.contains(version, ignoreCase = true) || version.contains(it, ignoreCase = true) }
                    if (key != null) {
                        matchedVersion = deviceData.versions[key]
                        Log.d(TAG, "ArbCheckWorker: Partial match found: $key")
                    }
                }
            }
            
            val variant = matchedVersion?.regions?.joinToString("/") ?: "Unknown"

            if (telemetryEnabled) {
                try {
                    api.recordHit(
                        installId = installId,
                        model = model,
                        version = version,
                        variant = variant,
                        isConverted = isConverted,
                        isManual = false
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Hit record failed", e)
                }
            }

            if (currentArb == -1 && matchedVersion != null) {
                Log.d(TAG, "ArbCheckWorker: Root failed or not used, using DB value for $version")
                currentArb = matchedVersion.arb
            }

            Log.d(TAG, "ArbCheckWorker: Final ARB calculation: Current=$currentArb, LastKnown=$lastKnownArb")

            // 3. Logic: If currentArb > lastKnownArb, we have an increase!
            if (lastKnownArb != -1 && currentArb > lastKnownArb) {
                Log.i(TAG, "ArbCheckWorker: ARB INCREASE DETECTED! $lastKnownArb -> $currentArb")
                sendNotification(
                    applicationContext.getString(R.string.notification_arb_increase_title),
                    applicationContext.getString(R.string.notification_arb_increase_content, lastKnownArb, currentArb)
                )
            } 
            else if (deviceData != null) {
                val maxArbInDb = deviceData.versions.values
                    .filter { !it.isHardcoded }
                    .maxOfOrNull { it.arb } ?: 0

                if (maxArbInDb > 0 && maxArbInDb > currentArb && currentArb != -1) {
                    Log.i(TAG, "ArbCheckWorker: Higher ARB available in DB: $maxArbInDb > $currentArb")
                    sendNotification(
                        applicationContext.getString(R.string.notification_title),
                        applicationContext.getString(R.string.notification_content, maxArbInDb)
                    )
                }
            }
            
            // Update last known state
            if (currentArb != -1) {
                settingsRepo.setLastKnownArb(currentArb)
            }
            settingsRepo.setLastKnownBuildId(version)
            settingsRepo.setLastCheckTimestamp(System.currentTimeMillis())

            Log.d(TAG, "ArbCheckWorker: Work completed successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ArbCheckWorker: Error during work", e)
            Result.retry()
        }
    }

    private fun sendNotification(title: String, content: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "arb_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = applicationContext.getString(R.string.notification_channel_name)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun sendAppUpdateNotification(tagName: String) {
        val title = applicationContext.getString(R.string.update_available_title)
        val content = applicationContext.getString(R.string.update_available_msg, tagName)
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "app_update_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "App Updates"
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification)
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        return try {
            val currentParts = current.split(".").map { it.filter { c -> c.isDigit() }.toInt() }
            val latestParts = latest.split(".").map { it.filter { c -> c.isDigit() }.toInt() }
            
            for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
                val curr = currentParts.getOrNull(i) ?: 0
                val lat = latestParts.getOrNull(i) ?: 0
                if (lat > curr) return true
                if (lat < curr) return false
            }
            false
        } catch (e: Exception) {
            latest != current
        }
    }
}
