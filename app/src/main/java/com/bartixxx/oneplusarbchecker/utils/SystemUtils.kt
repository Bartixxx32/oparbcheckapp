package com.bartixxx.oneplusarbchecker.utils

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

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

    fun hasBarometer(context: android.content.Context): Boolean {
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        return sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) != null
    }

    /**
     * Checks if the device has physical eSIM hardware (eUICC).
     * Highly reliable for distinguishing Chinese OnePlus hardware (no eSIM) 
     * from Global hardware (has eSIM) in models from 2022 onwards.
     */
    fun hasEsimHardware(context: android.content.Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return false
        val euiccManager = context.getSystemService(android.content.Context.EUICC_SERVICE) as? android.telephony.euicc.EuiccManager
        return euiccManager?.isEnabled == true
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
