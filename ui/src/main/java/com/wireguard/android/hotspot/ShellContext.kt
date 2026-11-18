/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import android.content.Context
import android.os.Process

object ShellContext {
    fun create(): Context {
        check(Process.myUid() == 2000) { "The hotspot helper must run as Android shell." }
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val shell = system.createPackageContext("com.android.shell", 0)
        // createPackageContext inherits the system context's AppOps attribution.
        val attribution = shell.javaClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }
        val current = checkNotNull(attribution.get(shell))
        attribution.set(shell, current.javaClass.getMethod("withPackageName", String::class.java)
            .invoke(current, "com.android.shell"))
        shell.javaClass.getDeclaredField("mOpPackageName").apply {
            isAccessible = true
            set(shell, "com.android.shell")
        }
        check(shell.opPackageName == "com.android.shell" &&
            shell.attributionSource.packageName == "com.android.shell" &&
            shell.attributionSource.uid == 2000) { "Could not establish shell package attribution." }
        return shell
    }
}
