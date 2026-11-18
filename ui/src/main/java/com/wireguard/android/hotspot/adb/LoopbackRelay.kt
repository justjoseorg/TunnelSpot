/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LoopbackRelay(targetPort: Int, deadlineMillis: Long = 30_000) : Closeable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val closed = AtomicBoolean()
    private val workers = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "phonehome-pairing-io").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "phonehome-pairing-deadline").apply { isDaemon = true }
    }
    private val remote = Socket()
    private val listener = ServerSocket()
    @Volatile private var client: Socket? = null
    val port: Int get() = listener.localPort

    init {
        try {
            require(targetPort in 1..65535 && deadlineMillis > 0)
            remote.connect(InetSocketAddress(loopback, targetPort), 5_000)
            listener.bind(InetSocketAddress(loopback, 0), 1)
            timer.schedule({ close() }, deadlineMillis, TimeUnit.MILLISECONDS)
            workers.execute {
                try {
                    val accepted = listener.accept()
                    synchronized(this) {
                        if (closed.get()) { accepted.close(); return@execute }
                        client = accepted
                    }
                    workers.execute {
                        try { remote.getInputStream().copyTo(accepted.getOutputStream()) }
                        catch (_: IOException) { /* Socket deadlines/cancellation close both ends. */ }
                        finally { close() }
                    }
                    accepted.getInputStream().copyTo(remote.getOutputStream())
                } catch (_: IOException) {
                    // Closing the listener also interrupts a pending accept.
                } catch (_: RejectedExecutionException) {
                    // The deadline may race with starting the second pump.
                } finally { close() }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { client?.close() }
        runCatching { remote.close() }
        timer.shutdownNow()
        workers.shutdownNow()
    }
}
