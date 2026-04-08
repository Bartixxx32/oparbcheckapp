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
            value
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prop $key", e)
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
}
