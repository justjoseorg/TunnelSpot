/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalAdb(context: Context) : AbsAdbConnectionManager() {
    private val identity = AdbIdentity.load(context)
    private val relayLock = Any()
    private var activeRelay: LoopbackRelay? = null
    private var closed = false
    init {
        setApi(Build.VERSION.SDK_INT)
        setHostAddress(AdbInputs.HOST)
        setTimeout(15, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }
    override fun getPrivateKey(): PrivateKey = identity.privateKey
    override fun getCertificate(): Certificate = identity.certificate
    override fun getDeviceName(): String = "PhoneHome"
    // The library caches its TLS context, so do not destroy its in-memory identity on disconnect.
    override fun close() {
        synchronized(relayLock) {
            closed = true
            activeRelay?.close()
        }
        disconnect()
    }

    fun pairLocal(port: Int, code: String): Boolean {
        require(port in 1..65535)
        AdbInputs.pairingCode(code)
        // libadb pairing has no socket deadline. A byte-transparent loopback relay
        // bounds it without changing Android's end-to-end TLS/SPAKE authentication.
        return LoopbackRelay(port).use { relay ->
            synchronized(relayLock) {
                check(!closed) { "Pairing was cancelled." }
                check(activeRelay == null)
                activeRelay = relay
            }
            try { pair(AdbInputs.HOST, relay.port, code) }
            finally { synchronized(relayLock) { activeRelay = null } }
        }
    }

    fun connectLocal(port: Int): Boolean {
        require(port in 1..65535)
        val relay = LoopbackRelay(port, 60_000)
        try {
            synchronized(relayLock) {
                check(!closed && activeRelay == null)
                activeRelay = relay
            }
            return connect(AdbInputs.HOST, relay.port)
        } catch (e: Exception) {
            relay.close()
            throw e
        }
    }

    companion object {
        fun discoverPort(context: Context, type: String): Int {
            val found = CountDownLatch(1)
            val port = AtomicInteger(-1)
            val discovery = AdbMdns(context, type) { host, value ->
                if (host != null && value in 1..65535) {
                    port.set(value)
                    found.countDown()
                }
            }
            try {
                discovery.start()
                check(found.await(12, TimeUnit.SECONDS)) {
                    "Wireless debugging was not found. Enable it on this phone, or enter its connection port manually."
                }
                return port.get()
            } finally { discovery.stop() }
        }
    }
}
