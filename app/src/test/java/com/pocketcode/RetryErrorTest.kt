package com.pocketcode

import com.pocketcode.agent.isPermanentRequestError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryErrorTest {
    @Test
    fun geminiRequestErrorsAreNotRetried() {
        assertTrue(isPermanentRequestError("Function call is missing a thought_signature in functionCall parts"))
        assertTrue(isPermanentRequestError("INVALID_ARGUMENT: Request contains an invalid argument"))
        assertTrue(isPermanentRequestError("Request contains an Invalid Argument."))
    }

    @Test
    fun networkAndServerErrorsStillRetry() {
        assertFalse(isPermanentRequestError("HTTP 503: service unavailable"))
        assertFalse(isPermanentRequestError("Unexpected end of stream"))
        assertFalse(isPermanentRequestError("timeout"))
        assertFalse(isPermanentRequestError(""))
        assertFalse(isPermanentRequestError(null))
    }
}
