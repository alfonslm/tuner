package io.github.alfonslm.tuner.engine

import kotlin.math.max
import kotlin.math.min

/** Fixed-size history of the most recent samples. */
class RingBuffer(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var writeIndex = 0

    var length = 0
        private set

    fun push(block: FloatArray, count: Int = block.size) {
        val start = max(0, count - capacity)
        for (i in start until count) {
            data[writeIndex] = block[i]
            writeIndex = if (writeIndex + 1 == capacity) 0 else writeIndex + 1
        }
        length = min(capacity, length + count - start)
    }

    /**
     * Copies `out.size` samples (oldest first) ending [ago] samples before the
     * newest. Returns false if that reaches further back than what is held.
     */
    fun copyLatest(out: FloatArray, ago: Int = 0): Boolean {
        val count = out.size
        if (count + ago > length) return false
        var index = (writeIndex - count - ago) % capacity
        if (index < 0) index += capacity
        val firstPart = min(count, capacity - index)
        data.copyInto(out, 0, index, index + firstPart)
        if (firstPart < count) data.copyInto(out, firstPart, 0, count - firstPart)
        return true
    }

    fun clear() {
        data.fill(0f)
        writeIndex = 0
        length = 0
    }
}
