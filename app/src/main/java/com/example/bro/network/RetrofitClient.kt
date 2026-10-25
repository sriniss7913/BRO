package com.example.bro.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private const val DEFAULT_GROQ_URL = "https://api.groq.com/openai/"
    const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/"

    private val logger = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logger)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    val api: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl(DEFAULT_GROQ_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }

    val openRouterApi: GroqApi by lazy {
        createCustomApi(DEFAULT_OPENROUTER_URL)
    }

    fun createCustomApi(baseUrl: String): GroqApi {
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }
}