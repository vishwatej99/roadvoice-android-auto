package dev.roadvoice.voice

import java.nio.ByteBuffer

/** Bounded mono PCM16 queue; muted or stale microphone data is never replayed. */
internal class PcmAudioBuffer(private val capacity: Int = 6_400) {
    private val bytes = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var isEnabled = false

    init { require(capacity > 0 && capacity % 2 == 0) }

    @Synchronized fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        head = 0
        size = 0
    }

    @Synchronized fun offer(source: ByteArray, count: Int) {
        require(count in 0..source.size && count % 2 == 0)
        if (!isEnabled) return
        for (index in 0 until count step 2) {
            if (size == capacity) {
                head = (head + 2) % capacity
                size -= 2
            }
            bytes[(head + size) % capacity] = source[index]
            bytes[(head + size + 1) % capacity] = source[index + 1]
            size += 2
        }
    }

    @Synchronized fun fill(target: ByteBuffer) {
        require(target.capacity() % 2 == 0)
        target.clear()
        while (target.hasRemaining()) {
            if (isEnabled && size > 0) {
                target.put(bytes[head])
                head = (head + 1) % capacity
                size--
            } else target.put(0.toByte())
        }
        target.rewind()
    }
}
