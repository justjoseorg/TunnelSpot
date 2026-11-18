/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import com.wireguard.config.Config
import java.net.Inet4Address

object GatewayConfig {
    data class Settings(val addresses: Array<String>, val dns: Array<String>, val mtu: Int)

    fun validate(config: Config): Settings {
        val addresses = config.`interface`.addresses.filter { it.address is Inet4Address }
        require(addresses.size == 1) { "PhoneHome needs exactly one IPv4 tunnel address." }
        require(config.peers.any { peer ->
            peer.allowedIps.any { it.address is Inet4Address && it.mask == 0 }
        }) { "Hotspot sharing requires AllowedIPs = 0.0.0.0/0; split tunnels are not supported." }
        require(config.peers.all { it.endpoint.isPresent }) { "Every peer needs a WireGuard endpoint." }
        val dns = config.`interface`.dnsServers.filterIsInstance<Inet4Address>()
        require(dns.isNotEmpty()) { "Add an IPv4 DNS server to this WireGuard profile." }
        val mtu = config.`interface`.mtu.orElse(1280)
        require(mtu in 1280..1500) { "PhoneHome currently supports MTU values from 1280 to 1500." }
        return Settings(addresses.map { it.toString() }.toTypedArray(),
            dns.map { it.hostAddress!! }.toTypedArray(), mtu)
    }
}
