package com.orbitplayer.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Net {
    const val USER_AGENT = "OrbitPlayer/0.2 (Linux; Android)"

    // A realistic Chrome-on-Android identity. Many hosts put an anti-bot / "robot check" in front of
    // the site that blocks requests not looking like a browser; presenting as Chrome gets the device
    // API through it. Applied to every call to our server (register/config/heartbeat/ws).
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val browserHeaders = okhttp3.Interceptor { chain ->
        val b = chain.request().newBuilder()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
        chain.proceed(b.build())
    }

    /** Za komunikaciju sa našim serverom */
    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(browserHeaders)
        .build()

    /** Za IPTV servere: duži timeout, jer liste i TV vodič umeju da budu ogromni */
    val iptv: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
            } else chain.proceed(req)
        }
        .build()

    fun get(client: OkHttpClient, url: String): Response {
        val res = client.newCall(Request.Builder().url(url).get().build()).execute()
        if (!res.isSuccessful) {
            val code = res.code
            res.close()
            throw IOException("HTTP $code")
        }
        return res
    }

    fun getString(client: OkHttpClient, url: String): String = get(client, url).use { it.body?.string() ?: "" }
}
