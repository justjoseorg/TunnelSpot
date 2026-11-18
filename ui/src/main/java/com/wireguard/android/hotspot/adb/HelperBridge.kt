/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.wireguard.android.BuildConfig
import com.wireguard.android.hotspot.IHotspotService
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object HelperBridge {
    private val challenge = LaunchChallenge()
    private val lock = Any()
    @Volatile private var service: IHotspotService? = null
    private var pending: CompletableFuture<IHotspotService>? = null

    fun ready(): Boolean = service?.asBinder()?.isBinderAlive == true

    fun accept(token: String?, version: Int, binder: IBinder): Boolean = synchronized(lock) {
        val receiver = pending ?: return false
        if (version != BuildConfig.VERSION_CODE || !binder.isBinderAlive || !challenge.consume(token)) return false
        val helper = IHotspotService.Stub.asInterface(binder)
        binder.linkToDeath({
            synchronized(lock) { if (service?.asBinder() === binder) service = null }
        }, 0)
        service = helper
        receiver.complete(helper)
        true
    }

    fun requireService(): IHotspotService = service?.takeIf { it.asBinder().isBinderAlive }
        ?: error("Start the built-in helper in Hotspot setup.")

    fun start(context: Context, connectionPort: Int? = null): IHotspotService {
        val (future, token) = synchronized(lock) {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            check(pending == null) { "Helper startup is already in progress." }
            val future = CompletableFuture<IHotspotService>()
            pending = future
            future to challenge.begin()
        }
        try {
            val port = connectionPort ?: LocalAdb.discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT)
            require(port in 1..65535)
            // Close ADB after the authenticated Binder is returned; the helper survives independently.
            LocalAdb(context).use { adb ->
                check(adb.connectLocal(port)) {
                    "Could not connect. Pair TunnelSpot first, then use the Wireless debugging connection port (not the pairing port)."
                }
                val command = AdbInputs.launchCommand(context.applicationInfo.sourceDir, context.packageName,
                    token, Process.myUid())
                adb.openStream("shell:$command").use { stream ->
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    while (!future.isDone && !stream.isClosed && SystemClock.elapsedRealtime() < deadline) {
                        // Drain output to avoid blocking adbd; never record bootstrap arguments.
                        val available = stream.available()
                        if (available > 0) stream.read(ByteArray(minOf(available, 4096)), 0, minOf(available, 4096))
                        Thread.sleep(50)
                    }
                    return future.get(20, TimeUnit.SECONDS)
                }
            }
        } finally {
            synchronized(lock) {
                if (pending === future) {
                    challenge.cancel()
                    pending = null
                }
            }
        }
    }

    fun stop() {
        val helper = synchronized(lock) {
            challenge.cancel()
            pending?.cancel(true)
            pending = null
            service
        } ?: return
        if (helper.asBinder().isBinderAlive) helper.stop()
        synchronized(lock) { if (service === helper) service = null }
        runCatching { helper.destroy() }
    }
}
