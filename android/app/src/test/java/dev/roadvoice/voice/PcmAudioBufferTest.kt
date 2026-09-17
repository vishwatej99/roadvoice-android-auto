package dev.roadvoice.voice

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmAudioBufferTest {
    @Test fun muteDiscardsBufferedSpeechAndRejectsSpeechRecordedWhileMuted() {
        val queue = PcmAudioBuffer(8)
        queue.setEnabled(true)
        queue.offer(byteArrayOf(1, 2, 3, 4), 4)
        queue.setEnabled(false)
        queue.offer(byteArrayOf(5, 6), 2)
        queue.setEnabled(true)
        val frame = ByteBuffer.allocate(4)
        queue.fill(frame)
        assertArrayEquals(ByteArray(4), frame.array())
    }

    @Test fun slowConsumerReceivesNewestCompleteSamplesAndSilenceForUnderflow() {
        val queue = PcmAudioBuffer(4)
        queue.setEnabled(true)
        queue.offer(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
        val frame = ByteBuffer.allocate(6)
        queue.fill(frame)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 0, 0), frame.array())
    }

    @Test fun producerChunkBoundariesPreserveSampleOrderAcrossWraparound() {
        val queue = PcmAudioBuffer(6)
        queue.setEnabled(true)
        queue.offer(byteArrayOf(1, 2, 3, 4), 4)
        queue.fill(ByteBuffer.allocate(2))
        queue.offer(byteArrayOf(5, 6, 7, 8), 4)
        val frame = ByteBuffer.allocateDirect(6)
        queue.fill(frame)
        val actual = ByteArray(6)
        frame.get(actual)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), actual)
    }
}
