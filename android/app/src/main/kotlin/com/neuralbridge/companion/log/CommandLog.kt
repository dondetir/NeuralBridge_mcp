package com.neuralbridge.companion.log

/**
 * Singleton command log with circular buffer for tracking MCP command execution.
 */
object CommandLog {

    enum class Category {
        GESTURE, OBSERVE, MANAGE, WAIT, CONNECT, INPUT
    }

    data class Entry(
        val timestamp: Long,
        val command: String,
        val latencyMs: Int,
        val success: Boolean,
        val category: Category
    )

    data class Stats(
        val p50: Int,
        val p95: Int,
        val p99: Int,
        val count: Int
    )

    private const val MAX_SIZE = 100
    private val buffer = ArrayDeque<Entry>(MAX_SIZE)
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(entry: Entry) {
        if (buffer.size >= MAX_SIZE) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
        listeners.forEach { it() }
    }

    @Synchronized
    fun getRecent(count: Int): List<Entry> {
        return buffer.takeLast(count.coerceAtMost(buffer.size)).reversed()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        listeners.forEach { it() }
    }

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun getPerformanceStats(): Stats {
        if (buffer.isEmpty()) return Stats(0, 0, 0, 0)
        val latencies = buffer.map { it.latencyMs }.sorted()
        return Stats(
            p50 = latencies.percentile(50),
            p95 = latencies.percentile(95),
            p99 = latencies.percentile(99),
            count = latencies.size
        )
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun List<Int>.percentile(p: Int): Int {
        if (isEmpty()) return 0
        val index = ((p / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[index]
    }
}
