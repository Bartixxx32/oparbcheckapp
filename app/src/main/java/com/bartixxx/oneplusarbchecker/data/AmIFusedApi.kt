package com.bartixxx.oneplusarbchecker.data

import okhttp3.ResponseBody
import retrofit2.http.GET

interface AmIFusedApi {
    @GET("database.json")
    suspend fun getDatabase(): Map<String, DeviceData>

    @GET("https://oparb.bartixxx.workers.dev/hit")
    suspend fun recordHit(): ResponseBody
}
