package com.bartixxx.oneplusarbchecker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bartixxx.oneplusarbchecker.MainActivity
import com.bartixxx.oneplusarbchecker.R
import com.bartixxx.oneplusarbchecker.data.AmIFusedApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedReader
import java.io.InputStreamReader

import kotlinx.coroutines.flow.first

class ArbCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val settingsRepo = com.bartixxx.oneplusarbchecker.data.SettingsRepository(applicationContext)
            // Check if notifications are enabled
            val notificationsEnabled = settingsRepo.notificationsEnabledFlow.first()
            
            if (!notificationsEnabled) {
                return Result.success() // Skip work if notifications disabled
            }

            val model = getSystemProperty("ro.product.model") ?: return Result.success()
            val version = getSystemProperty("ro.build.display.id") ?: return Result.success()

            val retrofit = Retrofit.Builder()
                .baseUrl("https://oneplusantiroll.netlify.app/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val api = retrofit.create(AmIFusedApi::class.java)
            val database = api.getDatabase()

            val deviceData = database[model] ?: return Result.success()
            
            // Find current version ARB
            var matchedVersion = deviceData.versions[version]
            if (matchedVersion == null) {
                 val key = deviceData.versions.keys.find { it.contains(version, ignoreCase = true) || version.contains(it, ignoreCase = true) }
                 if (key != null) {
                     matchedVersion = deviceData.versions[key]
                 }
            }
            
            val currentArb = matchedVersion?.arb ?: 0
            
            // Check for max ARB
            val maxArb = deviceData.versions.values.maxOfOrNull { it.arb } ?: 0

            if (maxArb > currentArb) {
                sendNotification(maxArb)
            }
            
            settingsRepo.setLastCheckTimestamp(System.currentTimeMillis())

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun sendNotification(maxArb: Int) {
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

        val title = applicationContext.getString(R.string.notification_title)
        val content = applicationContext.getString(R.string.notification_content, maxArb)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Assuming default icon exists
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun getSystemProperty(key: String): String? {
        try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            return reader.readLine()?.trim()
        } catch (e: Exception) {
            return null
        }
    }
}
