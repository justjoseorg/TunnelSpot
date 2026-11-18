/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpstreamGuardTest {
    @Test fun acceptsOnlyTheOwnedTestNetwork() {
        assertNull(UpstreamGuard.problem("testtun2", "testtun2", true))
        for (actual in listOf("wlan0", "rmnet_data0", "testtun1", null)) {
            assertNotNull(UpstreamGuard.problem("testtun2", actual, true))
        }
    }

    @Test fun treatsDisconnectedHotspotAsStoppedEvenIfUpstreamRemains() {
        assertNotNull(UpstreamGuard.problem("testtun2", "testtun2", false))
    }
}
