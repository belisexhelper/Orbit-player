package com.orbitplayer.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.orbitplayer.core.Net
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Tests that show whether a connection is stable, not only fast */
object ConnectionTest {

    data class Latency(val samples: Int, val failed: Int, val minMs: Long, val avgMs: Long, val maxMs: Long, val jitterMs: Long)

    /**
     * Opens [count] connections to host:port one after another and times each (like ping, works without
     * special rights). Failed ones = lost; jitter = average change between two answers.
     */
    fun latency(host: String, port: Int, count: Int = 20, gapMs: Long = 250, progress: (Int) -> Unit = {}): Latency {
        val times = ArrayList<Long>()
        var failed = 0
        repeat(count) { i ->
            progress(i + 1)
            val t = SystemClock.elapsedRealtimeNanos()
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 3_000) }
                times += (SystemClock.elapsedRealtimeNanos() - t) / 1_000_000
            } catch (e: Exception) {
                failed++
            }
            Thread.sleep(gapMs)
        }
        if (times.isEmpty()) return Latency(count, failed, 0, 0, 0, 0)
        val jitter = if (times.size < 2) 0L else times.zipWithNext { a, b -> abs(b - a) }.average().toLong()
        return Latency(count, failed, times.min(), times.average().toLong(), times.max(), jitter)
    }

    /** host and port of an address (default port by scheme) */
    fun hostPort(url: String): Pair<String, Int>? = runCatching {
        val u = URI(url)
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        host to port
    }.getOrNull()

    /** Wi-Fi / cable details: signal, band, link speed */
    fun networkDetails(context: Context): Pair<String, Boolean?> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = if (Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(cm.activeNetwork) else null
        if (caps == null) return "No connection" to false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Cable (Ethernet)" to true
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Mobile data or other" to null
        @Suppress("DEPRECATION")
        val info = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo }.getOrNull()
            ?: return "Wi-Fi" to null
        val rssi = info.rssi
        val quality = when {
            rssi >= -55 -> "excellent"
            rssi >= -67 -> "good"
            rssi >= -75 -> "weak"
            else -> "very weak"
        }
        val band = when (info.frequency) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5900 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> "${info.frequency} MHz"
        }
        val text = "Wi-Fi $band, signal $rssi dBm ($quality), link ${info.linkSpeed} Mbit/s"
        return text to (rssi >= -67)
    }

    data class StreamResult(
        val seconds: Int,
        val perSecondKb: List<Int>,
        val avgMbps: Double,
        val emptySeconds: Int,
        val longestGapS: Int,
        val gaps: Int,
        val error: String?,
    )

    /**
     * Reads a live stream for [seconds] seconds without playing it and records how much arrived each second.
     * A stable stream gets data every second; an unstable connection shows seconds without data (gaps).
     */
    fun stream(url: String, seconds: Int = 30, progress: (Int, Double) -> Unit): StreamResult {
        val client = Net.iptv.newBuilder().readTimeout(seconds.toLong() + 5, TimeUnit.SECONDS).build()
        val perSecond = IntArray(seconds)
        var err: String? = null
        val start = SystemClock.elapsedRealtime()
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) error("server answered HTTP ${r.code}")
                val input = r.body?.byteStream() ?: error("empty answer")
                val buf = ByteArray(32 * 1024)
                // a watcher closes the stream at the end even when no data comes at all
                val stopper = Thread {
                    try { Thread.sleep(seconds * 1000L) } catch (_: InterruptedException) {}
                    runCatching { r.close() }
                }.apply { isDaemon = true; start() }
                var total = 0L
                while (true) {
                    val n = try { input.read(buf) } catch (e: Exception) { break }
                    if (n < 0) break
                    val sec = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
                    if (sec >= seconds) break
                    perSecond[sec] += n
                    total += n
                    progress(sec + 1, total * 8.0 / 1_000_000 / maxOf(1.0, (SystemClock.elapsedRealtime() - start) / 1000.0))
                }
                stopper.interrupt()
            }
        } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
        }
        val elapsed = ((SystemClock.elapsedRealtime() - start) / 1000).toInt().coerceIn(1, seconds)
        val used = perSecond.take(elapsed)
        var longest = 0
        var run = 0
        var gaps = 0
        for (b in used) {
            if (b == 0) { run++; if (run == 1) gaps++; longest = maxOf(longest, run) } else run = 0
        }
        val totalBytes = used.sumOf { it.toLong() }
        return StreamResult(
            seconds = elapsed,
            perSecondKb = used.map { it / 1024 },
            avgMbps = totalBytes * 8.0 / 1_000_000 / elapsed,
            emptySeconds = used.count { it == 0 },
            longestGapS = longest,
            gaps = gaps,
            error = err,
        )
    }

    /** one character per second: how much data arrived (· = nothing) */
    fun graph(perSecondKb: List<Int>): String {
        if (perSecondKb.isEmpty()) return ""
        val max = perSecondKb.max().coerceAtLeast(1)
        val bars = "▁▂▃▄▅▆▇█"
        return perSecondKb.joinToString("") { kb ->
            if (kb == 0) "·" else bars[((kb.toDouble() / max) * (bars.length - 1)).toInt().coerceIn(0, bars.length - 1)].toString()
        }
    }
}
