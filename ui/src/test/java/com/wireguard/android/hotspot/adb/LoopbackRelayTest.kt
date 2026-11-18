/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoopbackRelayTest {
    @Test fun carriesBytesBothWays() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val task = worker.submit {
                    server.accept().use {
                        val value = it.getInputStream().read()
                        it.getOutputStream().write(value + 1)
                        it.getOutputStream().flush()
                        it.getInputStream().read()
                    }
                }
                LoopbackRelay(server.localPort).use { relay ->
                    Socket("127.0.0.1", relay.port).use {
                        it.soTimeout = 2_000
                        it.getOutputStream().write(41)
                        assertEquals(42, it.getInputStream().read())
                    }
                }
                task.get(3, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun deadlineClosesAnUnresponsivePairingTransport() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            LoopbackRelay(server.localPort, 250).use { relay ->
                server.accept().use { remote ->
                    Socket("127.0.0.1", relay.port).use { client ->
                        client.soTimeout = 3_000
                        remote.soTimeout = 3_000
                        assertEquals(-1, client.getInputStream().read())
                        assertEquals(-1, remote.getInputStream().read())
                    }
                }
            }
        }
    }
}
