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

    companion object {
        private const val TEST_CAPACITY = CircularByteBuffer.MIN_CAPACITY_BYTES
    }

    private lateinit var buffer: CircularByteBuffer

    @Before
    fun setup() {
        buffer = CircularByteBuffer(capacityBytes = TEST_CAPACITY)
    }

    @Test
    fun `initial state is empty`() {
        assertEquals(TEST_CAPACITY, buffer.capacity)
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
        val data = ByteArray(TEST_CAPACITY + 50) { it.toByte() }
        buffer.write(data)

        // Size should be capped at capacity
        assertEquals(TEST_CAPACITY, buffer.size)

        val result = buffer.readAll()
        assertEquals(TEST_CAPACITY, result.size)
        assertEquals(data[50], result[0])
        assertEquals(data.last(), result.last())
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
        val smallBuffer = CircularByteBuffer(TEST_CAPACITY)
        val data = ByteArray(TEST_CAPACITY + 5) { it.toByte() }
        smallBuffer.write(data)

        val result = smallBuffer.readAll()
        assertEquals(TEST_CAPACITY, result.size)
        for (i in 0 until TEST_CAPACITY) {
            assertEquals(data[i + 5], result[i])
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
        val data = ByteArray(TEST_CAPACITY) { it.toByte() }
        buffer.write(data)

        assertEquals(TEST_CAPACITY, buffer.size)
        val result = buffer.readAll()
        assertArrayEquals(data, result)
    }

    @Test
    fun `boundary condition - capacity plus one`() {
        // Write capacity + 1 bytes
        val data = ByteArray(TEST_CAPACITY + 1) { it.toByte() }
        buffer.write(data)

        assertEquals(TEST_CAPACITY, buffer.size)
        val result = buffer.readAll()
        assertEquals(data[1], result[0])
        assertEquals(data.last(), result.last())
    }

    @Test
    fun `sequential operations maintain consistency`() {
        buffer.write(ByteArray(TEST_CAPACITY) { 1 })
        buffer.write(ByteArray(100) { 2 })

        val result = buffer.readAll()
        assertEquals(TEST_CAPACITY, result.size)
        assertTrue(result.copyOfRange(0, TEST_CAPACITY - 100).all { it == 1.toByte() })
        assertTrue(result.copyOfRange(TEST_CAPACITY - 100, TEST_CAPACITY).all { it == 2.toByte() })
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
