package com.orbitplayer.tools

import com.orbitplayer.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class SpeedResult(
    val downloadMbps: Double = 0.0,
    val pingMs: Long = 0,
    val jitterMs: Long = 0,
    val source: String = "",
    val error: String = "",
    /** Speed from the user's own IPTV server (one live channel), -1 = not measured */
    val providerMbps: Double = -1.0,
    val providerPingMs: Long = -1,
    val providerError: String = "",
)

/**
 * Speed test: response time, then download speed over 4 parallel connections.
 * Several public test files are tried in turn, so one blocked server doesn't break the test.
 * Optionally also measures how fast a channel comes from the user's IPTV server.
 */
object SpeedTest {
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val PING_URLS = listOf(
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://speed.cloudflare.com/__down?bytes=0",
    )

    private val DOWNLOAD_URLS = listOf(
        "Cloudflare" to "https://speed.cloudflare.com/__down?bytes=200000000",
        "OVH" to "https://proof.ovh.net/files/100Mb.dat",
        "Hetzner" to "https://fsn1-speed.hetzner.com/100MB.bin",
        "Tele2" to "http://speedtest.tele2.net/100MB.zip",
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(url: String, ua: String = BROWSER_UA) = Request.Builder().url(url).header("User-Agent", ua).header("Cache-Control", "no-cache").build()

    /** @param onProgress (phase: ping | download | provider | done, current speed in Mb/s) */
    suspend fun run(durationMs: Long = 8_000, providerUrl: String? = null, onProgress: (String, Double) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        // 1. response time
        onProgress("ping", 0.0)
        val pings = ArrayList<Long>()
        for (url in PING_URLS) {
            pings.clear()
            repeat(5) {
                runCatching {
                    val t = System.nanoTime()
                    client.newCall(request(url)).execute().use { r -> if (r.code >= 500) error("HTTP ${r.code}") }
                    pings += (System.nanoTime() - t) / 1_000_000
                }
            }
            if (pings.size >= 3) break
        }
        val samples = if (pings.size > 1) pings.drop(1) else pings // the first one includes connection set-up
        val ping = samples.sorted().getOrNull(samples.size / 2) ?: -1
        val jitter = if (samples.size > 1) samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().toLong() else 0

        // 2. download
        onProgress("download", 0.0)
        var down = 0.0
        var source = ""
        var lastError = if (pings.isEmpty()) "No internet connection" else ""
        for ((name, url) in DOWNLOAD_URLS) {
            val r = runCatching { measure(url, BROWSER_UA, durationMs, 4) { onProgress("download", it) } }
            val mbps = r.getOrNull()
            if (mbps != null && mbps > 0.05) {
                down = mbps
                source = name
                break
            }
            lastError = r.exceptionOrNull()?.message ?: "No data from $name"
        }

        // 3. the user's own IPTV server
        var pMbps = -1.0
        var pPing = -1L
        var pError = ""
        if (!providerUrl.isNullOrBlank()) {
            onProgress("provider", 0.0)
            runCatching {
                val t = System.nanoTime()
                client.newCall(request(providerUrl, Net.USER_AGENT)).execute().use { res ->
                    pPing = (System.nanoTime() - t) / 1_000_000
                    if (!res.isSuccessful) error("the server answered HTTP ${res.code}")
                }
                pMbps = measure(providerUrl, Net.USER_AGENT, 6_000, 1) { onProgress("provider", it) }
            }.onFailure { pError = it.message ?: "no answer" }
        }

        onProgress("done", down)
        SpeedResult(
            downloadMbps = down,
            pingMs = ping,
            jitterMs = jitter,
            source = source,
            error = if (down <= 0.05) lastError.ifBlank { "Test failed" } else "",
            providerMbps = pMbps,
            providerPingMs = pPing,
            providerError = pError,
        )
    }

    /** Downloads [url] over [streams] connections for [durationMs]; returns Mb/s */
    private suspend fun measure(url: String, ua: String, durationMs: Long, streams: Int, onProgress: (Double) -> Unit): Double = supervisorScope {
        val bytes = AtomicLong(0)
        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000
        var lastReport = start
        val jobs = (0 until streams).map {
            async(Dispatchers.IO) {
                client.newCall(request(url, ua)).execute().use { res ->
                    if (!res.isSuccessful) error("HTTP ${res.code}")
                    val input = res.body?.byteStream() ?: error("empty response")
                    val buf = ByteArray(64 * 1024)
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val total = bytes.addAndGet(n.toLong())
                        val now = System.nanoTime()
                        if (it == 0 && now - lastReport > 250_000_000) {
                            lastReport = now
                            onProgress(mbps(total, now - start))
                        }
                        if (now > deadline) break
                    }
                }
            }
        }
        val results = jobs.map { j -> runCatching { j.await() } }
        if (bytes.get() == 0L) results.firstNotNullOfOrNull { r -> r.exceptionOrNull() }?.let { throw it }
        mbps(bytes.get(), System.nanoTime() - start)
    }

    private fun mbps(bytes: Long, nanos: Long): Double = if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1e9) / 1_000_000.0
}
