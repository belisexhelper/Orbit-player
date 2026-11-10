package com.orbitplayer.playback

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What happened while watching since the app started: stalls, new connections, reconnects.
 * Shown in Diagnostics, so an unstable connection can be seen in numbers.
 */
object PlaybackStats {
    data class Event(val time: Long, val text: String)

    @Volatile var stalls = 0; private set
    @Volatile var stallMs = 0L; private set
    @Volatile var longestStallMs = 0L; private set
    @Volatile var newConnections = 0; private set
    @Volatile var reconnects = 0; private set
    @Volatile var watchedMs = 0L; private set
    /** extra live buffer learned per server */
    val learnedBuffer = ConcurrentHashMap<String, Long>()

    private val events = ArrayDeque<Event>()
    private var stallStarted = 0L
    private var playingSince = 0L

    @Synchronized fun event(text: String) {
        events.addLast(Event(System.currentTimeMillis(), text))
        while (events.size > 30) events.removeFirst()
    }

    @Synchronized fun recent(): List<String> {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        return events.reversed().map { "${f.format(Date(it.time))}  ${it.text}" }
    }

    /** the picture plays (or stopped playing) — for the watched time */
    @Synchronized fun playing(on: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (on && playingSince == 0L) playingSince = now
        if (!on && playingSince != 0L) { watchedMs += now - playingSince; playingSince = 0L }
    }

    @Synchronized fun stallStarted(title: String) {
        if (stallStarted != 0L) return
        stallStarted = SystemClock.elapsedRealtime()
        stalls++
        event("Stall on $title")
    }

    @Synchronized fun stallEnded() {
        if (stallStarted == 0L) return
        val d = SystemClock.elapsedRealtime() - stallStarted
        stallStarted = 0L
        stallMs += d
        if (d > longestStallMs) longestStallMs = d
        event("Picture again after %.1f s".format(d / 1000f))
    }

    /** a new stream started: an unfinished stall of the previous one doesn't count on */
    @Synchronized fun streamChanged() { stallStarted = 0L }

    @Synchronized fun newConnection(reason: String) {
        newConnections++
        event("No data ($reason) → new connection")
    }

    @Synchronized fun reconnect(reason: String) {
        reconnects++
        event("Player reconnect: $reason")
    }

    val totalWatchedMs: Long
        @Synchronized get() = watchedMs + if (playingSince != 0L) SystemClock.elapsedRealtime() - playingSince else 0L
}
