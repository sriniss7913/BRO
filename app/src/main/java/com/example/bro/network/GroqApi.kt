package com.example.bro.network


import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GroqApi {

    @POST("v1/chat/completions")
    suspend fun chat(

        @Header("Authorization")
        authorization: String,

        @Body
        request: GroqRequest

    ): Response<GroqResponse>
}