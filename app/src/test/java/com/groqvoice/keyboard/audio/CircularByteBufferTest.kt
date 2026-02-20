package com.groqvoice.keyboard.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CircularByteBuffer].
 *
 * Tests cover:
 * - Basic write/read operations
 * - Circular wrapping behavior
 * - Overflow handling (data larger than capacity)
 * - Thread safety basics (sequential access patterns)
 * - Security clearing on close/clear
 * - Edge cases (empty reads, boundary conditions)
 *
 * TSD Section 4.3 — Pre-buffering for hands-free mode.
 */
class CircularByteBufferTest {

    private lateinit var buffer: CircularByteBuffer

    @Before
    fun setup() {
        buffer = CircularByteBuffer(capacityBytes = 100)
    }

    @Test
    fun `initial state is empty`() {
        assertEquals(100, buffer.capacity)
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        assertFalse(buffer.closed)
    }

    @Test
    fun `capacity is clamped to valid range`() {
        val smallBuffer = CircularByteBuffer(CircularByteBuffer.MIN_CAPACITY_BYTES / 2)
        assertEquals(CircularByteBuffer.MIN_CAPACITY_BYTES, smallBuffer.capacity)

        val largeBuffer = CircularByteBuffer(CircularByteBuffer.MAX_CAPACITY_BYTES * 2)
        assertEquals(CircularByteBuffer.MAX_CAPACITY_BYTES, largeBuffer.capacity)
    }

    @Test
    fun `write and readAll returns data in order`() {
        val data = "Hello, World!".toByteArray()
        buffer.write(data)

        assertEquals(data.size, buffer.size)
        assertFalse(buffer.isEmpty)

        val result = buffer.readAll()
        assertArrayEquals(data, result)
    }

    @Test
    fun `multiple writes accumulate`() {
        val part1 = "Hello".toByteArray()
        val part2 = "World".toByteArray()

        buffer.write(part1)
        buffer.write(part2)

        assertEquals(part1.size + part2.size, buffer.size)

        val result = buffer.readAll()
        val expected = "HelloWorld".toByteArray()
        assertArrayEquals(expected, result)
    }

    @Test
    fun `circular wrap overwrites old data`() {
        // Buffer capacity is 100, write 150 bytes
        val data = ByteArray(150) { it.toByte() }
        buffer.write(data)

        // Size should be capped at capacity
        assertEquals(100, buffer.size)

        val result = buffer.readAll()
        // Should contain the last 100 bytes (50..149)
        assertEquals(100, result.size)
        assertEquals(50.toByte(), result[0]) // First byte should be 50
        assertEquals(149.toByte(), result[99]) // Last byte should be 149
    }

    @Test
    fun `readAll on empty buffer returns empty array`() {
        val result = buffer.readAll()
        assertEquals(0, result.size)
    }

    @Test
    fun `drainTo moves data and clears buffer`() {
        val data = "Test data for drain".toByteArray()
        buffer.write(data)

        val destination = ByteArray(buffer.capacity)
        val bytesDrained = buffer.drainTo(destination)

        assertEquals(data.size, bytesDrained)
        assertArrayEquals(data, destination.copyOf(data.size))
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `drainTo with null destination clears without copying`() {
        val data = "Test data".toByteArray()
        buffer.write(data)

        val bytesDrained = buffer.drainTo(null)

        assertEquals(0, bytesDrained)
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `drainTo respects destination size`() {
        // Write more data than destination can hold
        val data = ByteArray(80) { it.toByte() }
        buffer.write(data)

        val destination = ByteArray(50) // Smaller than data
        val bytesDrained = buffer.drainTo(destination)

        assertEquals(50, bytesDrained)
        assertEquals(0, buffer.size) // Buffer still cleared
    }

    @Test
    fun `clear resets buffer state`() {
        val data = "Data to be cleared".toByteArray()
        buffer.write(data)
        assertFalse(buffer.isEmpty)

        buffer.clear()

        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        val result = buffer.readAll()
        assertEquals(0, result.size)
    }

    @Test
    fun `close marks buffer as closed`() {
        buffer.close()
        assertTrue(buffer.closed)
    }

    @Test(expected = IllegalStateException::class)
    fun `write after close throws exception`() {
        buffer.close()
        buffer.write("Should fail".toByteArray())
    }

    @Test
    fun `close is idempotent`() {
        buffer.write("Data".toByteArray())
        buffer.close()
        buffer.close() // Should not throw
        assertTrue(buffer.closed)
    }

    @Test
    fun `close clears data`() {
        buffer.write("Sensitive audio".toByteArray())
        buffer.close()

        // After close, buffer should be empty
        assertEquals(0, buffer.size)
    }

    @Test
    fun `wrapped buffer read order is correct`() {
        // Create small buffer to force wrapping
        val smallBuffer = CircularByteBuffer(10)

        // Write 15 bytes: buffer wraps, keeps last 10 (5-14)
        val data = ByteArray(15) { it.toByte() }
        smallBuffer.write(data)

        val result = smallBuffer.readAll()
        assertEquals(10, result.size)
        // Should be bytes 5-14 in order
        for (i in 0..9) {
            assertEquals((i + 5).toByte(), result[i])
        }
    }

    @Test
    fun `default capacity is correct for 300ms prebuffer`() {
        val defaultBuffer = CircularByteBuffer()
        assertEquals(CircularByteBuffer.DEFAULT_CAPACITY_BYTES, defaultBuffer.capacity)
        // DEFAULT_CAPACITY_BYTES should be 19,200 (300ms at 16kHz/16-bit × 2× safety)
        assertEquals(19_200, CircularByteBuffer.DEFAULT_CAPACITY_BYTES)
    }

    @Test
    fun `boundary condition - exact capacity write`() {
        // Write exactly capacity bytes
        val data = ByteArray(100) { it.toByte() }
        buffer.write(data)

        assertEquals(100, buffer.size)
        val result = buffer.readAll()
        assertArrayEquals(data, result)
    }

    @Test
    fun `boundary condition - capacity plus one`() {
        // Write capacity + 1 bytes
        val data = ByteArray(101) { it.toByte() }
        buffer.write(data)

        assertEquals(100, buffer.size)
        val result = buffer.readAll()
        // Should have lost the first byte (0), kept 1-100
        assertEquals(1.toByte(), result[0])
        assertEquals(100.toByte(), result[99])
    }

    @Test
    fun `sequential operations maintain consistency`() {
        // Simulate real recording: multiple small writes
        val chunks = listOf(
            ByteArray(20) { 1 },
            ByteArray(20) { 2 },
            ByteArray(20) { 3 },
            ByteArray(20) { 4 },
            ByteArray(20) { 5 }
        )

        chunks.forEach { buffer.write(it) }

        // Total 100 bytes, should be full
        assertEquals(100, buffer.size)

        // Read and verify
        val result = buffer.readAll()
        assertEquals(100, result.size)
        assertTrue(result.slice(0..19).all { it == 1.toByte() })
        assertTrue(result.slice(20..39).all { it == 2.toByte() })
        assertTrue(result.slice(40..59).all { it == 3.toByte() })
        assertTrue(result.slice(60..79).all { it == 4.toByte() })
        assertTrue(result.slice(80..99).all { it == 5.toByte() })

        // Write more to trigger wrap
        buffer.write(ByteArray(50) { 6 })

        val result2 = buffer.readAll()
        // Should have lost first 50 bytes (all 1s and half of 2s)
        assertEquals(100, result2.size)
        assertTrue(result2.slice(0..9).all { it == 2.toByte() }) // Remaining 2s
        assertTrue(result2.slice(10..29).all { it == 3.toByte() })
        assertTrue(result2.slice(30..49).all { it == 4.toByte() })
        assertTrue(result2.slice(50..69).all { it == 5.toByte() })
        assertTrue(result2.slice(70..99).all { it == 6.toByte() }) // New 6s
    }

    @Test
    fun `toString does not expose sensitive data`() {
        buffer.write("Sensitive audio data".toByteArray())
        val str = buffer.toString()

        assertTrue(str.contains("CircularByteBuffer"))
        assertTrue(str.contains("capacity="))
        assertTrue(str.contains("size="))
        assertFalse(str.contains("Sensitive")) // Data should not be exposed
    }
}
