package com.bartixxx.oneplusarbchecker.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object SystemUtils {
    fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine()?.trim()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
