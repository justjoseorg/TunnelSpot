/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import org.junit.Assert.*
import org.junit.Test

class HotspotStartupExceptionTest {
    @Test fun liveHelperFailureDoesNotClaimThatItCrashed() {
        val cause = SecurityException("Permission denied")
        val failure = HotspotStartupException("Preparing the hotspot test network", cause, false)
        assertTrue(failure.message!!.contains("Preparing the hotspot test network"))
        assertTrue(failure.message!!.contains("Permission denied"))
        assertFalse(failure.message!!.contains("helper disconnected"))
        assertSame(cause, failure.cause)
    }

    @Test fun helperDeathGetsActionableGuidanceEvenWithoutAnExceptionMessage() {
        val failure = HotspotStartupException("Starting Wi-Fi tethering", IllegalStateException(), true)
        assertTrue(failure.message!!.contains("helper disconnected"))
        assertTrue(failure.message!!.contains("Restart it in Hotspot setup"))
        assertTrue(failure.message!!.contains("IllegalStateException"))
    }

    @Test fun cleanupFailureDoesNotReplaceTheStartupFailure() {
        val cause = IllegalStateException("Start refused")
        val cleanup = IllegalStateException("Shutdown timed out")
        val failure = HotspotStartupException("Starting Wi-Fi tethering", cause, false, cleanup)
        assertTrue(failure.message!!.contains("Start refused"))
        assertTrue(failure.message!!.contains("Shutdown timed out"))
        assertTrue(failure.message!!.contains("shutdown is unconfirmed"))
        assertSame(cause, failure.cause)
        assertArrayEquals(arrayOf(cleanup), failure.suppressed)
    }
}
