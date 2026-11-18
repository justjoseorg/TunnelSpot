/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import android.content.Context
import android.content.Intent
import android.os.Binder
import androidx.core.content.ContextCompat
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.android.hotspot.adb.HelperBridge

class HotspotBackend(private val context: Context) : Backend {
    private val engine = GoBackend(context)
    private val lifetime = Binder()
    @Volatile private var service: IHotspotService? = null
    private var currentTunnel: Tunnel? = null
    private var currentConfig: Config? = null
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
        HelperBridge.requireService()
        if (currentTunnel != null) stopSession()
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
        return HelperBridge.requireService().also { service = it }
    }

    @Synchronized
    fun healthProblem(): String? {
        if (currentTunnel == null) return null
        val helper = service ?: return "The built-in helper disconnected. Check that Android stopped the Wi-Fi hotspot."
        return helper.status().takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun activeName(): String? = currentTunnel?.name

    @Synchronized
    fun stop() {
        stopSession()
    }

    private fun stopSession() {
        val helper = service
        if (helper != null && helper.asBinder().isBinderAlive) {
            helper.stop()
        } else if (currentTunnel != null) {
            connect().recoverStop()
        }
        engine.stopExternal()
        currentTunnel = null
        currentConfig = null
        service = null
        context.stopService(Intent(context, HotspotSessionService::class.java))
    }

    companion object {
        @Volatile var instance: HotspotBackend? = null
            private set
    }
}
