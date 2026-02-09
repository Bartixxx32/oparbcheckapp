package com.bartixxx.oneplusarbchecker.data

import retrofit2.http.GET

interface AmIFusedApi {
    @GET("database.json")
    suspend fun getDatabase(): Map<String, DeviceData>
}
