package com.orbitplayer.playback

/**
 * Watches an MPEG-TS live stream as it passes and, after a new connection, keeps out what the player
 * already has: servers start a new connection a few seconds in the past, and those seconds a second
 * time made the sound jump back while the picture waited.
 *
 *  - Before any reconnect it only looks (bytes pass unchanged) and remembers, per stream (PID), the last
 *    presentation time (PTS) and whether video marks its key frames.
 *  - After a reconnect it passes whole packets only: tables (PAT/PMT…) always; each audio/video stream from
 *    its first packet that is newer than what was already passed (video: at a key frame when the stream
 *    marks them). A timestamp far in the past means the server restarted its clock: accepted right away.
 *  - Not TS (or nothing found to sync on): everything passes unchanged.
 */
class TsGate {
    private companion object {
        const val SIZE = 188
        const val SYNC = 0x47
        const val WRAP = 1L shl 33
        const val RESET_BACK = 30L * 90_000 // a jump back of more than 30 s = new clock, not an overlap
        // give up waiting only when nothing tells the time any more (~3.7 MB without a timestamp on a waiting
        // stream); a long overlap (servers resend up to half a minute) keeps being left out however long it is
        const val MAX_BLIND = 20_000
    }

    private val pkt = ByteArray(SIZE)
    private var have = 0

    /** TS confirmed (several packets in a row); until then nothing is filtered */
    private var isTs = false
    private var goodPackets = 0

    private val lastPts = HashMap<Int, Long>()
    private val video = HashSet<Int>()
    private val pes = HashSet<Int>()
    private val marksKeyFrames = HashSet<Int>()

    /** after a reconnect: streams still waiting for new data (null = not gating) */
    private var waiting: HashSet<Int>? = null
    private var dropped = 0
    /** gating just ended: the unfinished packet in [pkt] hasn't been passed yet */
    private var flushPartial = false

    private var out = ByteArray(256 * 1024)
    private var outStart = 0
    private var outEnd = 0

    val pending: Int get() = outEnd - outStart

    /** a new connection starts: drop the unfinished packet of the old one and wait for new data per stream */
    fun reconnected() {
        have = 0
        flushPartial = false
        if (isTs) {
            waiting = HashSet(pes)
            dropped = 0
        }
    }

    /** bytes from the network; what may go to the player can be taken with [drain] */
    fun feed(b: ByteArray, off: Int, len: Int) {
        val gating = waiting != null
        if (!gating && flushPartial) {
            append(pkt, 0, have) // the start of the packet that continues in these bytes
            flushPartial = false
        }
        if (!gating) append(b, off, len) // looking only: everything passes unchanged
        for (i in off until off + len) {
            val v = b[i].toInt() and 0xFF
            if (have == 0 && v != SYNC) {
                if (isTs && goodPackets > 0) goodPackets = 0
                continue // resync on the next packet start
            }
            pkt[have++] = v.toByte()
            if (have == SIZE) {
                have = 0
                goodPackets++
                if (goodPackets >= 5) isTs = true
                val pass = packet()
                if (gating && pass) append(pkt, 0, SIZE)
            }
        }
    }

    fun drain(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, pending)
        System.arraycopy(out, outStart, buffer, offset, n)
        outStart += n
        if (outStart == outEnd) { outStart = 0; outEnd = 0 }
        return n
    }

    /** looks at one packet; returns whether it may pass while gating */
    private fun packet(): Boolean {
        val b1 = pkt[1].toInt() and 0xFF
        val pid = ((b1 and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)
        val pusi = b1 and 0x40 != 0
        val afc = (pkt[3].toInt() shr 4) and 3
        var p = 4
        var keyFrame = false
        if (afc and 2 != 0) {
            val afLen = pkt[4].toInt() and 0xFF
            if (afLen > 0) keyFrame = (pkt[5].toInt() and 0x40) != 0 // random access indicator
            p = 5 + afLen
        }
        var pts = -1L
        if (pusi && afc and 1 != 0 && p + 14 <= SIZE &&
            pkt[p].toInt() == 0 && pkt[p + 1].toInt() == 0 && pkt[p + 2].toInt() == 1
        ) {
            val sid = pkt[p + 3].toInt() and 0xFF
            pes += pid
            if (sid in 0xE0..0xEF) video += pid
            if ((pkt[p + 7].toInt() shr 6) and 2 != 0) {
                fun u(i: Int) = (pkt[p + i].toInt() and 0xFF).toLong()
                pts = (((u(9) shr 1) and 7) shl 30) or (u(10) shl 22) or ((u(11) shr 1) shl 15) or (u(12) shl 7) or (u(13) shr 1)
            }
        }
        if (keyFrame && pid in video) marksKeyFrames += pid

        val w = waiting
        val pass = when {
            w == null -> true
            pid !in pes -> true // tables and anything that isn't audio/video
            pid !in w -> true // this stream already continues
            pts >= 0 && isNewer(pid, pts) && (pid !in video || pid !in marksKeyFrames || keyFrame) -> {
                w -= pid
                true
            }
            else -> {
                if (pts >= 0) dropped = 0 // a timestamp of a waiting stream: still the overlap, keep leaving it out
                false
            }
        }
        if (w != null) {
            if (!pass) dropped++
            if (w.isEmpty() || dropped > MAX_BLIND) {
                waiting = null // all streams continue (or give up waiting)
                flushPartial = true
            }
        }
        if (pass && pts >= 0) {
            val last = lastPts[pid]
            if (last == null || isNewer(pid, pts)) lastPts[pid] = pts
        }
        return pass
    }

    /** newer than what was passed on this stream (with the 33-bit wrap); far older = the server's clock restarted */
    private fun isNewer(pid: Int, pts: Long): Boolean {
        val last = lastPts[pid] ?: return true
        var d = (pts - last) % WRAP
        if (d < 0) d += WRAP
        if (d >= WRAP / 2) d -= WRAP // signed difference
        return d > 0 || d < -RESET_BACK
    }

    private fun append(b: ByteArray, off: Int, len: Int) {
        if (outEnd + len > out.size) {
            // move to the front, grow if still too small
            val n = pending
            val target = if (n + len > out.size) ByteArray(maxOf(out.size * 2, n + len)) else out
            System.arraycopy(out, outStart, target, 0, n)
            out = target
            outStart = 0
            outEnd = n
        }
        System.arraycopy(b, off, out, outEnd, len)
        outEnd += len
    }
}
