/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import rikka.shizuku.Shizuku
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HotspotBackend(private val context: Context) : Backend {
    private val engine = GoBackend(context)
    private val lifetime = Binder()
    @Volatile private var service: IHotspotService? = null
    @Volatile private var connection: ServiceConnection? = null
    private var currentTunnel: Tunnel? = null
    private var currentConfig: Config? = null
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(context, ShizukuHotspotService::class.java))
        // The helper must survive app death long enough to stop Wi-Fi.
        .daemon(true).processNameSuffix("hotspot").debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    init { instance = this }

    @Synchronized override fun getRunningTunnelNames(): Set<String> = engine.runningTunnelNames
    @Synchronized override fun getState(tunnel: Tunnel): Tunnel.State = engine.getState(tunnel)
    @Synchronized override fun getStatistics(tunnel: Tunnel) = engine.getStatistics(tunnel)
    override fun getVersion(): String = engine.version
    override fun isAlwaysOn() = false
    override fun isLockdownEnabled() = false

    @Synchronized
    override fun setState(tunnel: Tunnel, requested: Tunnel.State, config: Config?): Tunnel.State {
        val desired = if (requested == Tunnel.State.TOGGLE) {
            if (getState(tunnel) == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        } else requested
        if (desired == Tunnel.State.DOWN) {
            if (currentTunnel === tunnel) stop()
            return getState(tunnel)
        }
        requireNotNull(config) { "Import or create a WireGuard profile first." }
        val settings = GatewayConfig.validate(config)
        if (currentTunnel === tunnel && currentConfig == config &&
            runCatching { service?.status() == "" }.getOrDefault(false)) return Tunnel.State.UP
        check(Shizuku.pingBinder()) { context.getString(R.string.hotspot_no_shizuku) }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            context.getString(R.string.hotspot_permission_needed)
        }
        if (currentTunnel != null) stop()
        ContextCompat.startForegroundService(context, Intent(context, HotspotSessionService::class.java))
        try {
            val helper = connect()
            helper.prepare(settings.addresses, settings.dns, settings.mtu, lifetime).use { descriptor ->
                engine.startExternal(tunnel, config, descriptor)
            }
            currentTunnel = tunnel
            currentConfig = config
            helper.startHotspot()
            return Tunnel.State.UP
        } catch (e: Exception) {
            try {
                stop()
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
                throw IllegalStateException("Startup failed and hotspot shutdown is unconfirmed. Turn off Wi-Fi hotspot in Android settings.", e)
            }
            throw e
        }
    }

    private fun connect(): IHotspotService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val future = CompletableFuture<IHotspotService>()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                future.complete(IHotspotService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) service = null
                future.completeExceptionally(IllegalStateException("Shizuku disconnected."))
            }
        }
        connection = callback
        Shizuku.bindUserService(serviceArgs, callback)
        return future.get(20, TimeUnit.SECONDS).also { service = it }
    }

    @Synchronized
    fun healthProblem(): String? {
        if (currentTunnel == null) return null
        val helper = service ?: return "Shizuku disconnected. Check that Android stopped the Wi-Fi hotspot."
        return helper.status().takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun activeName(): String? = currentTunnel?.name

    @Synchronized
    fun stop() {
        val helper = service
        if (helper != null && helper.asBinder().isBinderAlive) {
            helper.stop()
        } else if (currentTunnel != null) {
            check(Shizuku.pingBinder()) {
                "Shizuku disconnected; turn off Wi-Fi hotspot in Android settings, then restart Shizuku."
            }
            connect().recoverStop()
        }
        engine.stopExternal()
        currentTunnel = null
        currentConfig = null
        connection?.let { Shizuku.unbindUserService(serviceArgs, it, true) }
        connection = null
        service = null
        context.stopService(Intent(context, HotspotSessionService::class.java))
    }

    companion object {
        @Volatile var instance: HotspotBackend? = null
            private set
    }
}
