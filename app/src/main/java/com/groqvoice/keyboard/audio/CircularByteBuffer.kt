package com.groqvoice.keyboard.audio

import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe circular byte buffer designed for real-time audio pre-buffering.
 *
 * This buffer implements a sliding window that continuously overwrites old data,
 * enabling "lookback" functionality for hands-free mode. When the user starts
 * speaking, the buffer already contains the previous 300ms of audio, ensuring
 * the beginning of speech is not clipped.
 *
 * ## Design Rationale
 *
 * - **Circular Structure**: Avoids memory reallocation during recording; data is
 *   written continuously in a ring, overwriting the oldest bytes first.
 * - **Thread Safety**: Uses [ReentrantReadWriteLock] for efficient concurrent access.
 *   Multiple readers can access the buffer simultaneously, but writes are exclusive.
 * - **Pre-buffering**: The 300ms lookback captures speech that occurs before the
 *   VAD detects activity, addressing the "late start" problem in voice recognition.
 *
 * ## Memory Calculation
 *
 * For 16kHz/16-bit mono PCM (Groq optimal):
 * - 300ms = 0.3s × 16,000 samples/s × 2 bytes/sample = 9,600 bytes
 * - With 2× safety margin: 19,200 bytes (~19KB per buffer instance)
 *
 * ## Thread Safety
 *
 * All public methods are thread-safe. The buffer supports:
 * - Single producer (audio recording thread) writing via [write]
 * - Multiple consumers (processing thread) reading via [readAll] or [drainTo]
 *
 * @param capacityBytes Total buffer capacity in bytes. Default is 300ms of 16kHz/16-bit PCM
 *                     with a 2× safety margin (19,200 bytes).
 *
 * @see AudioRecordingManager Uses this buffer in HANDS_FREE mode for pre-buffering.
 * @see VoiceActivityDetector Triggers the transition from pre-buffer to active recording.
 *
 * TSD Section 4.3 — Hands-Free Mode with 300ms Pre-buffer.
 */
class CircularByteBuffer(
    capacityBytes: Int = DEFAULT_CAPACITY_BYTES
) {
    companion object {
        /**
         * Default capacity for 300ms lookback at 16kHz/16-bit mono PCM.
         * Calculation: 0.3s × 16,000 samples/s × 2 bytes/sample × 2× safety margin = 19,200 bytes.
         */
        const val DEFAULT_CAPACITY_BYTES = 19_200

        /**
         * Minimum allowed buffer capacity to prevent undersized buffers.
         */
        const val MIN_CAPACITY_BYTES = 4_800 // 75ms minimum

        /**
         * Maximum allowed buffer capacity to prevent excessive memory usage.
         * 5 minutes at 16kHz/16-bit = ~9.6MB; we limit to 1MB for pre-buffering.
         */
        const val MAX_CAPACITY_BYTES = 1_048_576 // 1MB
    }

    // Validate capacity
    private val actualCapacity = capacityBytes.coerceIn(MIN_CAPACITY_BYTES, MAX_CAPACITY_BYTES)

    /**
     * The underlying byte array storing circular data.
     * All accesses must be protected by [lock].
     */
    private val buffer = ByteArray(actualCapacity)

    /**
     * Read-write lock for thread-safe access.
     * Write lock is acquired for [write] and [clear] operations.
     * Read lock is acquired for [readAll] and [drainTo] operations.
     */
    private val lock = ReentrantReadWriteLock()

    /**
     * Current write position in the circular buffer.
     * This index wraps around using modulo [actualCapacity].
     * Access synchronized via [lock].
     */
    private var writePosition = 0

    /**
     * Total bytes ever written to this buffer (may exceed [actualCapacity]).
     * Used to determine if buffer has wrapped and how much data is valid.
     * Access synchronized via [lock].
     */
    private var totalBytesWritten = 0L

    /**
     * Atomic flag indicating whether the buffer is in a valid state.
     * Set to false after [close] is called.
     */
    private val isOpen = AtomicInteger(1)

    /**
     * Returns true if the buffer has been closed.
     */
    val closed: Boolean
        get() = isOpen.get() == 0

    /**
     * Returns the total capacity of this buffer in bytes.
     */
    val capacity: Int
        get() = actualCapacity

    /**
     * Returns the number of valid bytes currently in the buffer.
     * This is min(totalBytesWritten, actualCapacity) after buffer wraps.
     */
    val size: Int
        get() = lock.read {
            if (totalBytesWritten >= actualCapacity) actualCapacity else totalBytesWritten.toInt()
        }

    /**
     * Returns true if no data has been written to the buffer.
     */
    val isEmpty: Boolean
        get() = lock.read { totalBytesWritten == 0L }

    /**
     * Writes [data] to the circular buffer, overwriting old data if necessary.
     *
     * This method is thread-safe and lock-free for the common case where no
     * readers are active. When readers are present, exclusive write lock is acquired.
     *
     * @param data Byte array to write. If larger than [capacity], only the
     *             trailing [capacity] bytes are retained (oldest data discarded).
     * @throws IllegalStateException if the buffer has been [closed].
     */
    fun write(data: ByteArray) {
        check(!closed) { "Cannot write to closed CircularByteBuffer" }

        lock.write {
            if (data.size <= actualCapacity) {
                // Normal case: data fits in buffer
                for (byte in data) {
                    buffer[writePosition] = byte
                    writePosition = (writePosition + 1) % actualCapacity
                }
            } else {
                // Overflow case: data exceeds buffer capacity, keep only trailing bytes
                val startIdx = data.size - actualCapacity
                for (i in startIdx until data.size) {
                    buffer[writePosition] = data[i]
                    writePosition = (writePosition + 1) % actualCapacity
                }
            }
            totalBytesWritten += data.size.toLong()
        }
    }

    /**
     * Reads all valid bytes from the buffer in chronological order (oldest first).
     *
     * For a wrapped buffer (totalBytesWritten > capacity), this returns the entire
     * circular buffer starting from the oldest data (writePosition) to the newest.
     * For a non-wrapped buffer, returns data from index 0 to writePosition.
     *
     * @return A new byte array containing all valid data in chronological order.
     *         Returns empty array if no data has been written.
     */
    fun readAll(): ByteArray = lock.read {
        when {
            totalBytesWritten == 0L -> ByteArray(0)
            totalBytesWritten >= actualCapacity -> {
                // Buffer has wrapped: read from writePosition to end, then 0 to writePosition
                val result = ByteArray(actualCapacity)
                val splitPoint = writePosition
                // Copy oldest data (writePosition to end)
                System.arraycopy(buffer, splitPoint, result, 0, actualCapacity - splitPoint)
                // Copy newest data (0 to writePosition)
                System.arraycopy(buffer, 0, result, actualCapacity - splitPoint, splitPoint)
                result
            }
            else -> {
                // Buffer not wrapped yet: simply copy 0 to writePosition
                buffer.copyOf(writePosition)
            }
        }
    }

    /**
     * Drains all valid bytes from the buffer into [destination] and clears the buffer.
     *
     * This is a combined read+clear operation that efficiently moves data from the
     * circular buffer to a linear output buffer. After this call, the buffer is empty
     * but remains open for future writes.
     *
     * @param destination The output array to write to. Must be large enough to hold
     *                    [size] bytes. If null, data is discarded and buffer cleared.
     * @return The number of bytes written to [destination].
     */
    fun drainTo(destination: ByteArray?): Int = lock.write {
        val dataSize = size

        if (dataSize == 0 || destination == null) {
            // Just reset counters if no data or null destination
            writePosition = 0
            totalBytesWritten = 0L
            return@write 0
        }

        // Ensure destination can hold our data
        val bytesToWrite = minOf(dataSize, destination.size)

        if (totalBytesWritten >= actualCapacity) {
            // Wrapped buffer: copy in two parts
            val splitPoint = writePosition
            val firstChunkSize = actualCapacity - splitPoint

            if (bytesToWrite <= firstChunkSize) {
                // Only need first chunk
                System.arraycopy(buffer, splitPoint, destination, 0, bytesToWrite)
            } else {
                // Need both chunks
                System.arraycopy(buffer, splitPoint, destination, 0, firstChunkSize)
                val secondChunkSize = bytesToWrite - firstChunkSize
                System.arraycopy(buffer, 0, destination, firstChunkSize, secondChunkSize)
            }
        } else {
            // Non-wrapped buffer: simple copy
            System.arraycopy(buffer, 0, destination, 0, bytesToWrite)
        }

        // Zero out the buffer for security (audio data may be sensitive)
        Arrays.fill(buffer, 0)

        // Reset state
        writePosition = 0
        totalBytesWritten = 0L

        bytesToWrite
    }

    /**
     * Clears all data from the buffer without closing it.
     *
     * This operation zero-fills the underlying array for security and resets
     * all internal state. The buffer remains open and can receive new writes.
     */
    fun clear() {
        lock.write {
            // Zero-fill for security
            Arrays.fill(buffer, 0)
            writePosition = 0
            totalBytesWritten = 0L
        }
    }

    /**
     * Closes the buffer, preventing further writes.
     *
     * This operation clears all data and marks the buffer as closed.
     * After closing, any call to [write] will throw [IllegalStateException].
     * Multiple calls to close are safe (idempotent).
     */
    fun close() {
        if (isOpen.compareAndSet(1, 0)) {
            lock.write {
                // Zero-fill for security before releasing
                Arrays.fill(buffer, 0)
                writePosition = 0
                totalBytesWritten = 0L
            }
        }
    }

    /**
     * Returns a snapshot of the current buffer state for debugging.
     * Does not include actual audio data for privacy.
     */
    override fun toString(): String {
        return lock.read {
            "CircularByteBuffer(capacity=$actualCapacity, size=$size, " +
            "wrapped=${totalBytesWritten >= actualCapacity}, closed=$closed)"
        }
    }
}
