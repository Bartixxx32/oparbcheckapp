package com.bartixxx.oneplusarbchecker.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.delay

object SystemUtils {
    private const val TAG = "ARB_CHECKER"

    fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val value = reader.readLine()?.trim()
            Log.d(TAG, "getprop $key: $value")
            value?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prop $key", e)
            null
        }
    }

    /**
     * Reads a system property using reflection.
     * Works on most Android versions without root.
     */
    fun readSystemPropertyReflection(key: String): String? {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, key) as String
            value.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading prop via reflection: $key", e)
            null
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            val available = line?.contains("uid=0") == true
            Log.d(TAG, "isRootAvailable: $available (output: $line)")
            available
        } catch (e: Exception) {
            Log.d(TAG, "isRootAvailable: false (exception: ${e.message})")
            false
        }
    }

    fun runRootCommand(command: String): Boolean {
        return try {
            Log.d(TAG, "Executing root command: $command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val result = process.waitFor()
            Log.d(TAG, "Root command result: $result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $command", e)
            false
        }
    }

    fun getRootOutput(command: String): String? {
        return try {
            Log.d(TAG, "Executing root output command: $command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            val result = output.toString().trim()
            Log.d(TAG, "Root output length: ${result.length}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Root output command failed: $command", e)
            null
        }
    }

    fun getPartitionPath(name: String): String? {
        val suffix = getSystemProperty("ro.boot.slot_suffix") ?: ""
        val fullName = name + suffix
        Log.d(TAG, "Searching for partition: $fullName")
        
        val paths = arrayOf(
            "/dev/block/by-name/$fullName",
            "/dev/block/bootdevice/by-name/$fullName",
            "/dev/block/platform/soc/1d84000.ufshc/by-name/$fullName"
        )
        
        for (path in paths) {
            val check = getRootOutput("ls $path")
            if (check?.contains(path) == true) {
                Log.d(TAG, "Found partition at: $path")
                return path
            }
        }
        
        val found = getRootOutput("find /dev/block -name $fullName | head -n 1")
        Log.d(TAG, "Find fallback result: $found")
        if (!found.isNullOrBlank()) return found

        Log.e(TAG, "Partition $fullName NOT FOUND!")
        return null
    }

    fun hasBarometer(context: Context): Boolean {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val result = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        Log.d(TAG, "hasBarometer: $result")
        return result
    }

    /**
     * Checks if the device has physical eSIM hardware (eUICC).
     * Highly reliable for distinguishing Chinese OnePlus hardware (no eSIM) 
     * from Global hardware (has eSIM) in models from 2022 onwards.
     */
    fun hasEsimHardware(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val euiccManager = context.getSystemService(Context.EUICC_SERVICE) as? EuiccManager
        if (euiccManager?.isEnabled != true) {
            Log.d(TAG, "hasEsimHardware: false (disabled)")
            return false
        }
        Log.d(TAG, "hasEsimHardware: true (isEnabled)")
        return true
    }

    private fun hasEsimViaUiccCardsInfo(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cards = tm.uiccCardsInfo
        for (card in cards) {
            if (card.isEuicc) {
                Log.d(TAG, "hasEsimViaUiccCardsInfo: found eUICC card, id=${card.cardId}")
                return true
            }
        }
        Log.d(TAG, "hasEsimViaUiccCardsInfo: no eUICC card found (${cards.size} UICC cards)")
        return false
    }

    suspend fun hasEsimHardwareReliable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val euiccManager = context.getSystemService(Context.EUICC_SERVICE) as? EuiccManager ?: return false
        if (!euiccManager.isEnabled) {
            Log.d(TAG, "hasEsimHardwareReliable: false (disabled)")
            return false
        }

        // Try getUiccCardsInfo() — requires only READ_PHONE_STATE (not carrier privileges).
        // This enumerates all actual UICC cards (physical + eUICC) present on the device.
        // On converted OnePlus hardware, no eUICC card will be found even though
        // isEnabled returns true.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val hasEuiccCard = hasEsimViaUiccCardsInfo(context)
                Log.d(TAG, "hasEsimHardwareReliable: UiccCardsInfo says hasEuicc=$hasEuiccCard")
                return hasEuiccCard
            } catch (e: SecurityException) {
                Log.w(TAG, "hasEsimHardwareReliable: UiccCardsInfo blocked: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "hasEsimHardwareReliable: UiccCardsInfo failed", e)
            }
        }

        // Try to get EID — this actually queries the eUICC hardware.
        // On converted OnePlus devices, isEnabled may be true but
        // getting the EID will fail because no physical card exists.
        try {
            val eid = euiccManager.eid
            if (!eid.isNullOrBlank()) {
                Log.d(TAG, "hasEsimHardwareReliable: EID present, real eUICC")
                return true
            }
            Log.w(TAG, "hasEsimHardwareReliable: EID null/empty — no real eUICC")
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "hasEsimHardwareReliable: EID requires carrier privileges")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "hasEsimHardwareReliable: EID failed — no eUICC card", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "hasEsimHardwareReliable: EID check failed", e)
            return false
        }

        // Try TelephonyManager.getCardIdForDefaultEuicc().
        // On devices WITH real eSIM hardware, this returns a valid card ID (> 0).
        // On Chinese hardware with global firmware (converted), isEnabled returns true
        // (software quirk) but cardId returns -1 because no physical eUICC exists.
        // This is the definitive indicator — isEnabled=true + cardId=-1 = converted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val cardId = tm.getCardIdForDefaultEuicc()
                if (cardId > 0) {
                    Log.d(TAG, "hasEsimHardwareReliable: cardId valid=$cardId, real eUICC")
                    return true
                }
                Log.w(TAG, "hasEsimHardwareReliable: cardId=$cardId — no eUICC card detected")
                return false
            } catch (e: SecurityException) {
                Log.w(TAG, "hasEsimHardwareReliable: cardId needs privileged phone state")
            } catch (e: Exception) {
                Log.e(TAG, "hasEsimHardwareReliable: cardId check failed", e)
            }
        }

        // All verification methods exhausted, result is inconclusive.
        // Fall back to trusting isEnabled.
        Log.d(TAG, "hasEsimHardwareReliable: all verifications blocked, trusting isEnabled")
        return true
    }

    /**
     * Runs conversion detection with retry logic.
     * Gives Android's telephony/sensor stack time to initialize.
     * 
     * @param retryCount number of retries if detection indicates conversion-negative
     * @param retryDelayMs delay between retries in milliseconds
     * @return Pair(hasEsim, hasBarometer)
     */
    suspend fun detectHardwareWithRetry(
        context: Context,
        expectEsim: Boolean,
        expectBarometer: Boolean,
        retryCount: Int = 3,
        retryDelayMs: Long = 100L
    ): Pair<Boolean, Boolean> {
        repeat(retryCount) { attempt ->
            val hasEsim = hasEsimHardwareReliable(context)
            val hasBarometer = hasBarometer(context)

            // If detection matches expectations, or we detect features present — trust it immediately.
            // We only retry when we get "feature absent" because that might be a false negative.
            val esimOk = !expectEsim || hasEsim     // not expected, or detected
            val barometerOk = !expectBarometer || hasBarometer

            if (esimOk && barometerOk) {
                Log.d(TAG, "detectHardwareWithRetry: passed on attempt ${attempt + 1} " +
                        "(esim=$hasEsim, baro=$hasBarometer)")
                return Pair(hasEsim, hasBarometer)
            }

            Log.d(TAG, "detectHardwareWithRetry: attempt ${attempt + 1}/$retryCount " +
                    "got esim=$hasEsim (expect=$expectEsim), baro=$hasBarometer (expect=$expectBarometer), retrying...")

            if (attempt < retryCount - 1) {
                delay(retryDelayMs)
            }
        }

        // Final result after all retries exhausted — accept whatever we got
        val finalEsim = hasEsimHardwareReliable(context)
        val finalBaro = hasBarometer(context)
        Log.d(TAG, "detectHardwareWithRetry: exhausted retries, final: esim=$finalEsim, baro=$finalBaro")
        return Pair(finalEsim, finalBaro)
    }

    fun getWidevineInfo(): Pair<String, String>? {
        return try {
            val widevineUuid = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
            if (!android.media.MediaDrm.isCryptoSchemeSupported(widevineUuid)) {
                return Pair("Not Supported", "N/A")
            }
            val mediaDrm = android.media.MediaDrm(widevineUuid)
            
            val level = try {
                mediaDrm.getPropertyString("securityLevel")
            } catch (e: Exception) {
                try {
                    mediaDrm.getPropertyString("SecurityLevel")
                } catch (e2: Exception) {
                    "Unknown"
                }
            }

            val systemId = try {
                val bytes = mediaDrm.getPropertyByteArray("systemId")
                // Convert bytes to long manually to ensure correct value
                var longId = 0L
                for (b in bytes) {
                    longId = (longId shl 8) or (b.toLong() and 0xFF)
                }
                longId.toString()
            } catch (e: Exception) {
                try {
                    mediaDrm.getPropertyString("systemId")
                } catch (e2: Exception) {
                    "Error reading ID"
                }
            }

            mediaDrm.release()
            Pair(level, systemId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Widevine info", e)
            Pair("Error", "Error")
        }
    }

    fun getBootloaderDebugInfo(): String {
        val lockedProp = readSystemPropertyReflection("ro.boot.flash.locked") ?: "null"
        val bootState = readSystemPropertyReflection("ro.boot.verifiedbootstate") ?: "null"
        return "locked=$lockedProp, state=$bootState"
    }

    fun isBootloaderUnlocked(): Boolean {
        val lockedProp = readSystemPropertyReflection("ro.boot.flash.locked")
        if (lockedProp == "0") return true
        
        val bootState = readSystemPropertyReflection("ro.boot.verifiedbootstate")
        if (bootState == "orange" || bootState == "yellow") return true
        
        return false
    }
}
