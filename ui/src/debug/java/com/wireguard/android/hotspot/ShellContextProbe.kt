/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 TunnelSpot contributors
 */
package com.wireguard.android.hotspot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

@Keep
object ShellContextProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000)
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val runtime = Class.forName("dalvik.system.VMRuntime")
        runtime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            .invoke(runtime.getDeclaredMethod("getRuntime").invoke(null), arrayOf("L") as Any)
        val shell = ShellContext.create()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val uncorrected = system.createPackageContext("com.android.shell", 0)
        PrintStream(FileOutputStream(FileDescriptor.out)).use { output ->
            output.println("uncorrectedOpPackage=${uncorrected.opPackageName}")
            output.println("correctedOpPackage=${shell.opPackageName}")
            output.println("attributionUid=${shell.attributionSource.uid}")
            for (permission in listOf("MANAGE_TEST_NETWORKS", "NETWORK_SETTINGS", "TETHER_PRIVILEGED")) {
                output.println("$permission=${shell.checkSelfPermission("android.permission.$permission") == PackageManager.PERMISSION_GRANTED}")
            }
        }
        exitProcess(0)
    }
}
