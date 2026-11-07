package com.orbitplayer.tools

import com.orbitplayer.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SpeedResult(val downloadMbps: Double = 0.0, val pingMs: Long = 0, val jitterMs: Long = 0, val error: String = "")

/**
 * Test brzine: ping (5 kratkih zahteva) i brzina preuzimanja (do [durationMs] milisekundi).
 * Koristi javne Cloudflare test adrese.
 */
object SpeedTest {
    private const val PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
    private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=100000000"

    private val client = Net.iptv.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** @param onProgress (faza, trenutna brzina u Mb/s) */
    suspend fun run(durationMs: Long = 10_000, onProgress: (String, Double) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        try {
            onProgress("ping", 0.0)
            val pings = ArrayList<Long>()
            repeat(5) {
                val t = System.nanoTime()
                client.newCall(Request.Builder().url(PING_URL).build()).execute().close()
                pings += (System.nanoTime() - t) / 1_000_000
            }
            val samples = pings.drop(1) // prvi zahtev uključuje uspostavljanje veze
            val sorted = samples.sorted()
            val ping = sorted[sorted.size / 2]
            val jitter = samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().toLong()

            onProgress("download", 0.0)
            var bytes = 0L
            val start = System.nanoTime()
            var lastReport = start
            client.newCall(Request.Builder().url(DOWN_URL).build()).execute().use { res ->
                val input = res.body?.byteStream() ?: error("empty response")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val now = System.nanoTime()
                    if (now - lastReport > 250_000_000) {
                        lastReport = now
                        onProgress("download", mbps(bytes, now - start))
                    }
                    if ((now - start) / 1_000_000 > durationMs) break
                }
            }
            val result = mbps(bytes, System.nanoTime() - start)
            onProgress("done", result)
            SpeedResult(downloadMbps = result, pingMs = ping, jitterMs = jitter)
        } catch (e: Exception) {
            SpeedResult(error = e.message ?: "Test failed")
        }
    }

    private fun mbps(bytes: Long, nanos: Long): Double = if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1e9) / 1_000_000.0
}
