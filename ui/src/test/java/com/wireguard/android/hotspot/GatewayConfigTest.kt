/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import com.wireguard.config.Config
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayConfigTest {
    private fun config(address: String = "10.64.0.2/32", allowed: String = "0.0.0.0/0",
                       dns: String = "10.64.0.1", extra: String = "") = Config.parse("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = $address
        DNS = $dns
        $extra
        [Peer]
        PublicKey = AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = $allowed
    """.trimIndent().byteInputStream())

    @Test fun extractsOnlyIpv4FromDualStackProfile() {
        val result = GatewayConfig.validate(config(address = "10.64.0.2/32, fd00::2/128",
            dns = "10.64.0.1, fd00::1"))
        assertArrayEquals(arrayOf("10.64.0.2/32"), result.addresses)
        assertArrayEquals(arrayOf("10.64.0.1"), result.dns)
        assertEquals(1280, result.mtu)
    }

    @Test fun rejectsSplitTunnelInsteadOfSilentlyBypassingVpn() {
        assertThrows(IllegalArgumentException::class.java) {
            GatewayConfig.validate(config(allowed = "10.0.0.0/8"))
        }
    }

    @Test fun rejectsIpv6OnlyAndAmbiguousSourceAddresses() {
        for (address in listOf("fd00::2/128", "10.0.0.2/32, 10.1.0.2/32")) {
            assertThrows(IllegalArgumentException::class.java) { GatewayConfig.validate(config(address = address)) }
        }
    }

    @Test fun requiresDnsThatCanTravelOverTheSharedIpv4Tunnel() {
        assertThrows(IllegalArgumentException::class.java) { GatewayConfig.validate(config(dns = "fd00::1")) }
    }

    @Test fun importedAppFiltersDoNotChangeGatewaySettings() {
        val normal = GatewayConfig.validate(config())
        val filtered = GatewayConfig.validate(config(extra = "IncludedApplications = com.example.bank"))
        assertArrayEquals(normal.addresses, filtered.addresses)
        assertArrayEquals(normal.dns, filtered.dns)
    }

    @Test fun rejectsUnsupportedMtu() {
        assertThrows(IllegalArgumentException::class.java) { GatewayConfig.validate(config(extra = "MTU = 9000")) }
    }
}
