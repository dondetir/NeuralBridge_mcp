package com.neuralbridge.companion.log

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LogStream — live tail of THIS app's own logcat output.
 *
 * Spawns `logcat --pid=<self>` in a background thread so every Log.* call made
 * anywhere in the app (MCP server, tool handler, screenshot pipeline, web driver,
 * etc.) streams in as a real log line — no need to re-route individual call sites.
 *
 * Lines are kept in a bounded ring buffer and pushed to listeners as they arrive.
 * Supports a minimum-level filter (V/D/I/W/E).
 */
object LogStream {

    private const val TAG = "LogStream"
    private const val MAX_LINES = 2000

    data class Line(val level: Char, val raw: String)

    private val buffer = ArrayDeque<Line>(MAX_LINES)
    private val listeners = mutableListOf<(Line) -> Unit>()

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var proc: java.lang.Process? = null

    /** Start tailing logcat for this process. Idempotent. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ readLoop() }, "LogStream-reader").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "LogStream started (pid=${Process.myPid()})")
    }

    @Synchronized
    fun stop() {
        running = false
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        thread = null
    }

    private fun readLoop() {
        try {
            // -v time: timestamped lines; filter to our own PID so we only see our logs.
            val pb = ProcessBuilder(
                "logcat", "--pid=${Process.myPid()}", "-v", "time"
            ).redirectErrorStream(true)
            val p = pb.start()
            proc = p
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String? = null
            while (running && reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                val parsed = Line(parseLevel(l), l)
                append(parsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "logcat read loop ended: ${e.message}")
            append(Line('E', "[LogStream] reader stopped: ${e.message}"))
        }
    }

    /**
     * Parse the priority letter from a `-v time` logcat line, e.g.
     * "10-21 05:40:01.123 D/Tag( 1234): message" -> 'D'.
     */
    private fun parseLevel(l: String): Char {
        // Find " X/" after the timestamp where X is one of V D I W E F
        val idx = l.indexOf('/')
        if (idx in 1 until l.length) {
            val c = l[idx - 1]
            if (c in "VDIWEF") return c
        }
        return 'I'
    }

    @Synchronized
    private fun append(line: Line) {
        if (buffer.size >= MAX_LINES) buffer.removeFirst()
        buffer.addLast(line)
        // Copy listeners to avoid concurrent-modification if a listener unregisters.
        val snapshot = listeners.toList()
        snapshot.forEach { try { it(line) } catch (_: Exception) {} }
    }

    /** All buffered lines at or above [minLevel] (V<D<I<W<E). */
    @Synchronized
    fun snapshot(minLevel: Char = 'V'): List<Line> {
        val min = rank(minLevel)
        return buffer.filter { rank(it.level) >= min }
    }

    fun rank(level: Char): Int = when (level) {
        'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; 'F' -> 5; else -> 2
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        // Best-effort clear of the underlying logcat buffer too (our pid only isn't
        // supported, so just clear our in-memory view).
    }

    @Synchronized
    fun addListener(l: (Line) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (Line) -> Unit) { listeners.remove(l) }
}
