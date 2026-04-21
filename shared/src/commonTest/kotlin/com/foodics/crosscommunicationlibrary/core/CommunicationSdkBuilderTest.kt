package com.foodics.crosscommunicationlibrary.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommunicationSdkBuilderTest {

    @Test
    fun build_withNoChannels_throwsIllegalArgumentException() {
        assertFailsWith<IllegalArgumentException>(
            message = "build() must throw when no channels are added"
        ) {
            CommunicationSdkBuilder().build()
        }
    }

    @Test
    fun build_errorMessage_mentionsChannels() {
        val exception = runCatching { CommunicationSdkBuilder().build() }
            .exceptionOrNull()
        assertNotNull(exception)
        val msg = exception.message ?: ""
        assertTrue(msg.contains("channel", ignoreCase = true), "Exception message should mention 'channel', got: $msg")
    }
}
