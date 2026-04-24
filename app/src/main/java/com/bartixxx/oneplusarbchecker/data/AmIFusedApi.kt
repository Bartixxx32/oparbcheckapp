package com.bartixxx.oneplusarbchecker.data

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface AmIFusedApi {
    @GET("database.json")
    suspend fun getDatabase(): Map<String, DeviceData>

    @GET("https://oparb.bartixxx.workers.dev/hit")
    suspend fun recordHit(
        @Query("install_id") installId: String? = null,
        @Query("model") model: String? = null,
        @Query("version") version: String? = null,
        @Query("is_converted") isConverted: Boolean? = null,
        @Query("is_manual") isManual: Boolean = false
    ): ResponseBody

    @GET("https://api.github.com/repos/Bartixxx32/oparbcheckapp/releases/latest")
    suspend fun getLatestRelease(): GitHubRelease
}
