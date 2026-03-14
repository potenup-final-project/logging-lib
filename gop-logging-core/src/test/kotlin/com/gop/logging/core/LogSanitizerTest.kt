package com.gop.logging.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSanitizerTest {

    private val sanitizer = LogSanitizer()

    @Test
    fun masksSensitiveKeyAndCardPattern() {
        val result = sanitizer.sanitize(
            mapOf(
                "token" to "abcdefghijklmn",
                "card" to "4111 1111 1111 1111",
                "nested" to mapOf("secret" to "mysecretvalue"),
                "email" to "abcdef@example.com",
                "webhookSecret" to "super-secret-value",
                "myApiKey" to "abcdef123456"
            )
        )

        assertEquals("ab***mn", result.payload["token"])
        assertEquals("****", result.payload["card"])
        val nested = result.payload["nested"] as Map<*, *>
        assertEquals("my***ue", nested["secret"])
        assertEquals("ab***@example.com", result.payload["email"])
        assertEquals("su***ue", result.payload["webhookSecret"])
        assertEquals("ab***56", result.payload["myApiKey"])
    }

    @Test
    fun truncatesPayloadFieldsBeyondLimit() {
        val payload = (1..12).associate { index -> "k$index" to index }
        val result = sanitizer.sanitize(payload)

        assertEquals(10, result.payload.size)
        assertTrue(result.payloadTruncated)
    }

    @Test
    fun compressesLargeListToCountObject() {
        val result = sanitizer.sanitize(
            mapOf("items" to listOf(1, 2, 3, 4, 5, 6, 7))
        )

        assertEquals(mapOf("count" to 7), result.payload["items"])
    }
}
