package com.bartixxx.oneplusarbchecker.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitInstance {
    private const val BASE_URL = "https://oneplusantiroll.netlify.app/"

    val api: AmIFusedApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AmIFusedApi::class.java)
    }
}
