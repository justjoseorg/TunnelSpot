/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.annotation.Keep
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs as the local ADB shell UID, not root. Only the owning app can call it.
 */
@Keep
class ShellHotspotService(private val appUid: Int) : IHotspotService.Stub() {
    private val context: Context by lazy { ShellContext.create() }
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java)!! }
    private val tethering by lazy { context.getSystemService("tethering") ?: error("No tethering service") }
    private val testManager by lazy { context.getSystemService("test_network") ?: error("No test-network service") }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile private var network: Network? = null
    private var keepAlive: ConnectivityManager.NetworkCallback? = null
    private var eventCallback: Any? = null
    private var owner: IBinder? = null
    private val ownerDeath = IBinder.DeathRecipient { executor.execute(::monitor) }
    private val networkToken = Binder()
    private var interfaceName: String? = null
    private var ownsHotspot = false
    @Volatile private var active = false
    @Volatile private var upstream: Network? = null
    @Volatile private var wifiTethered = false
    @Volatile private var failure: String? = null
    @Volatile private var tetheringSnapshotReceived = false
    private var idleDeadline = SystemClock.elapsedRealtime() + 600_000

    init {
        executor.scheduleWithFixedDelay(::monitor, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun monitor() {
        if (owner == null && descriptor == null && SystemClock.elapsedRealtime() > idleDeadline) {
            destroy()
            return
        }
        if (owner?.isBinderAlive == false) {
            runCatching { stop() }.onSuccess { destroy() }
            return
        }
        if (!active) return
        runCatching { status() }.onFailure {
            failure = "Could not verify the hotspot upstream."
            active = false
            runCatching { shell { stopWifi() } }
        }
    }

    @Synchronized
    override fun prepare(addresses: Array<String>, dns: Array<String>, mtu: Int, owner: IBinder): ParcelFileDescriptor = shell {
        check(descriptor == null) { "A TunnelSpot session already exists." }
        failure = null
        try {
            // Probe every required hidden API before changing tethering state.
            tethering.javaClass.getMethod("setPreferTestNetworks", Boolean::class.javaPrimitiveType)
            val events = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            eventCallback = proxy(events) { name, args ->
                when (name) {
                    "onUpstreamChanged" -> upstream = args?.getOrNull(0) as? Network
                    "onTetheredInterfacesChanged" -> {
                        wifiTethered = (args?.getOrNull(0) as? Collection<*>)?.any { item ->
                            if (item is String) item.matches(Regex("(wlan|ap|swlan|softap|wifi).*"))
                            else item != null && item.javaClass.getMethod("getType").invoke(item) == 0
                        } == true
                        tetheringSnapshotReceived = true
                    }
                }
            }
            tethering.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, events)
                .invoke(tethering, callbackExecutor, eventCallback)
            await("Android did not report tethering state.") { tetheringSnapshotReceived }
            check(!wifiTethered && wifiApState() in listOf(11, 14)) {
                "Turn off the existing Wi-Fi hotspot before starting TunnelSpot."
            }

            this.owner = owner
            owner.linkToDeath(ownerDeath, 0)
            val linkAddresses = addresses.map {
                LinkAddress::class.java.getConstructor(String::class.java).newInstance(it)
            }
            val tun = testManager.javaClass.getMethod("createTunInterface", Collection::class.java)
                .invoke(testManager, linkAddresses)
            descriptor = tun.javaClass.getMethod("getFileDescriptor").invoke(tun) as ParcelFileDescriptor
            interfaceName = tun.javaClass.getMethod("getInterfaceName").invoke(tun) as String

            val specifier = Class.forName("android.net.TestNetworkSpecifier")
                .getConstructor(String::class.java).newInstance(interfaceName) as NetworkSpecifier
            val transport = Class.forName("android.net.NetworkCapabilities").getField("TRANSPORT_TEST").getInt(null)
            val request = NetworkRequest.Builder().clearCapabilities().addTransportType(transport)
                .setNetworkSpecifier(specifier).build()
            val available = CountDownLatch(1)
            keepAlive = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(found: Network) { network = found; available.countDown() }
                override fun onLost(lost: Network) {
                    if (network == lost && active) {
                        failure = "Android removed the hotspot test network."
                        active = false
                        executor.execute { runCatching { shell { stopWifi() } } }
                    }
                }
            }
            connectivity.requestNetwork(request, keepAlive!!)
            val properties = LinkProperties().apply {
                setInterfaceName(this@ShellHotspotService.interfaceName)
                setMtu(mtu)
                dns.forEach {
                    LinkProperties::class.java.getMethod("addDnsServer", InetAddress::class.java)
                        .invoke(this, InetAddress.getByName(it))
                }
            }
            testManager.javaClass.getMethod("setupTestNetwork", LinkProperties::class.java,
                Boolean::class.javaPrimitiveType, IBinder::class.java)
                .invoke(testManager, properties, true, networkToken)
            check(available.await(12, TimeUnit.SECONDS)) { "Android did not bring up the test network." }
            check(owner.isBinderAlive) { "TunnelSpot disconnected during setup." }
            ParcelFileDescriptor.dup(descriptor!!.fileDescriptor)
        } catch (e: Exception) {
            runCatching { releaseNetwork() }
            throw IllegalStateException("This device could not prepare an ADB hotspot: ${reason(e)}")
        }
    }

    @Synchronized
    override fun startHotspot() = shell {
        check(descriptor != null) { "Prepare the WireGuard tunnel first." }
        try {
            preferTestNetwork(true)
            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
            val request = builderClass.getMethod("build").invoke(builder)
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val started = CountDownLatch(1)
            var startError: String? = null
            val callback = proxy(callbackClass) { name, args ->
                when (name) {
                    "onTetheringStarted" -> started.countDown()
                    "onTetheringFailed" -> {
                        startError = "Android refused Wi-Fi tethering (code ${args?.getOrNull(0)})."
                        started.countDown()
                    }
                }
            }
            ownsHotspot = true
            tethering.javaClass.getMethod("startTethering", requestClass, Executor::class.java, callbackClass)
                .invoke(tethering, request, callbackExecutor, callback)
            check(started.await(20, TimeUnit.SECONDS)) { "Timed out starting Wi-Fi hotspot." }
            check(startError == null) { startError!! }
            await("Android did not select the WireGuard test network as hotspot upstream.") {
                wifiTethered && upstream == network
            }
            active = true
        } catch (e: Exception) {
            failure = reason(e)
            stopWifi()
            throw IllegalStateException(failure)
        }
    }

    @Synchronized
    override fun status(): String = shell {
        failure?.let { return@shell it }
        if (!active) return@shell "Hotspot session is not active."
        val actual = upstream?.let { connectivity.getLinkProperties(it)?.interfaceName }
        val problem = UpstreamGuard.problem(interfaceName!!, actual, wifiTethered)
        if (problem != null) {
            failure = problem
            active = false
            stopWifi()
        }
        problem ?: ""
    }

    @Synchronized
    override fun stop() = shell {
        active = false
        // Keep the tunnel registered if Wi-Fi could not be stopped: removing it could cause fallback.
        stopWifi()
        releaseNetwork()
    }

    @Synchronized
    override fun recoverStop() = shell {
        // A reconnected helper cannot inherit the dead helper's ownership flags.
        ownsHotspot = true
        stop()
    }

    override fun destroy() {
        stop()
        executor.shutdownNow()
        callbackExecutor.shutdownNow()
        kotlin.system.exitProcess(0)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val caller = Binder.getCallingUid()
        check(caller == appUid || caller == Process.myUid()) { "Caller does not own this TunnelSpot helper." }
        return super.onTransact(code, data, reply, flags)
    }

    @Synchronized
    private fun stopWifi() {
        if (!ownsHotspot) return
        tethering.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tethering, 0)
        await("Could not confirm hotspot shutdown. Turn Wi-Fi hotspot off in Android settings.") {
            !wifiTethered && wifiApState() in listOf(11, 14)
        }
        ownsHotspot = false
    }

    private fun wifiApState(): Int {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) ?: error("No Wi-Fi service.")
        return wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int
    }

    private fun releaseNetwork() {
        val problems = mutableListOf<String>()
        fun release(action: () -> Unit) {
            runCatching(action).onFailure { problems.add(reason(it)) }
        }
        if (descriptor != null) release { preferTestNetwork(false) }
        keepAlive?.let { release { connectivity.unregisterNetworkCallback(it) } }
        keepAlive = null
        var registered = network
        if (registered == null && interfaceName != null) {
            release {
                registered = connectivity.allNetworks.firstOrNull {
                    connectivity.getLinkProperties(it)?.interfaceName == interfaceName
                }
            }
        }
        registered?.let {
            release {
                testManager.javaClass.getMethod("teardownTestNetwork", Network::class.java).invoke(testManager, it)
            }
        }
        network = null
        descriptor?.let { release { it.close() } }
        descriptor = null
        eventCallback?.let {
            release {
                tethering.javaClass.getMethod("unregisterTetheringEventCallback",
                    Class.forName("android.net.TetheringManager\$TetheringEventCallback")).invoke(tethering, it)
            }
        }
        eventCallback = null
        owner?.let { release { it.unlinkToDeath(ownerDeath, 0) } }
        owner = null
        interfaceName = null
        upstream = null
        tetheringSnapshotReceived = false
        idleDeadline = SystemClock.elapsedRealtime() + 600_000
        check(problems.isEmpty()) { "Wi-Fi is off, but test-network cleanup was incomplete: ${problems.joinToString("; ")}" }
    }

    private fun preferTestNetwork(prefer: Boolean) {
        tethering.javaClass.getMethod("setPreferTestNetworks", Boolean::class.javaPrimitiveType)
            .invoke(tethering, prefer)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { message }
            Thread.sleep(100)
        }
    }

    private fun proxy(type: Class<*>, callback: (String, Array<out Any?>?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.getOrNull(0)
                "toString" -> "TunnelSpot-${type.simpleName}"
                else -> { callback(method.name, args); null }
            }
        }

    private fun reason(error: Throwable): String {
        var cause = error
        while (cause is java.lang.reflect.InvocationTargetException && cause.cause != null) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun <T> shell(action: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try { action() } finally { Binder.restoreCallingIdentity(token) }
    }
}
