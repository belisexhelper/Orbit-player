package com.orbitplayer.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Beleži greške i padove aplikacije. Zapisi se šalju serveru (admin ih vidi u „Dnevnik uređaja”).
 */
object AppLog {
    private const val TAG = "Orbit"
    private val pending = ArrayDeque<JSONObject>()
    private var crashFile: File? = null

    fun install(context: Context) {
        crashFile = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile?.writeText(stackTrace(e)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun d(msg: String) = Log.d(TAG, msg)

    fun i(msg: String, kind: String = "log") = add("info", kind, msg, null)

    fun w(msg: String, e: Throwable? = null) {
        Log.w(TAG, msg, e)
        add("warn", "log", msg + (e?.let { ": ${it.message}" } ?: ""), null)
    }

    fun e(msg: String, e: Throwable? = null, kind: String = "log") {
        Log.e(TAG, msg, e)
        add("error", kind, msg, e?.let { JSONObject().put("stack", stackTrace(it).take(6000)) })
    }

    @Synchronized
    private fun add(level: String, kind: String, msg: String, data: JSONObject?) {
        val o = JSONObject().put("level", level).put("kind", kind).put("message", msg.take(4000))
        if (data != null) o.put("data", data)
        pending.addLast(o)
        while (pending.size > 100) pending.removeFirst()
    }

    /** Uzima zapise za slanje (uključujući pad iz prethodnog pokretanja). */
    @Synchronized
    fun drain(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        crashFile?.let { f ->
            if (f.exists()) {
                val text = runCatching { f.readText() }.getOrDefault("")
                f.delete()
                if (text.isNotBlank()) {
                    out += JSONObject().put("level", "crash").put("kind", "crash")
                        .put("message", text.lineSequence().firstOrNull() ?: "crash")
                        .put("data", JSONObject().put("stack", text.take(8000)))
                }
            }
        }
        out.addAll(pending)
        pending.clear()
        return out
    }

    @Synchronized
    fun requeue(items: List<JSONObject>) {
        items.asReversed().forEach { pending.addFirst(it) }
        while (pending.size > 100) pending.removeLast()
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
