package com.groqvoice.keyboard.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionResponseParsingTest {

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(TranscriptionResponse::class.java)

    @Test
    fun `parses response when x_groq usage is missing`() {
        val json = """
            {
              "text": "hello world",
              "x_groq": {
                "id": "req_123"
              }
            }
        """.trimIndent()

        val parsed = adapter.fromJson(json)

        assertNotNull(parsed)
        assertEquals("hello world", parsed?.text)
        assertEquals("req_123", parsed?.xGroq?.id)
        assertNull(parsed?.xGroq?.usage)
    }
}
