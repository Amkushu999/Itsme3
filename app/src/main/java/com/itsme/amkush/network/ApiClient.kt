package com.itsme.amkush.network

import android.content.Context
import com.google.gson.GsonBuilder
import com.itsme.amkush.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // NOTE: Dev LAN IP — intentionally kept for local debugging. Change before release.
    private const val BASE_URL = "http://0.0.0.0:5000/"
    private const val TIMEOUT_SECONDS = 30L

    // BUG FIX: Correct double-checked locking (DCL) pattern for JVM.
    // Rule: @Volatile alone is insufficient — you still need a synchronized second check
    // so that two threads racing through the first null-check both see the final value
    // once only one of them has constructed and published the instance.
    @Volatile private var retrofit: Retrofit? = null

    private fun getClient(context: Context? = null): Retrofit {
        // Fast path — already initialised (volatile read, no lock)
        retrofit?.let { return it }

        // Slow path — first-time init, serialised
        return synchronized(this) {
            // Second check: another thread may have initialised between our first read and lock acquire
            retrofit?.let { return it }

            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val gson = GsonBuilder()
                .setLenient()
                .create()

            val instance = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()

            retrofit = instance
            instance
        }
    }

    fun getApiService(context: Context? = null): ApiService {
        return getClient(context).create(ApiService::class.java)
    }

    fun getBaseUrl(): String = BASE_URL
}
