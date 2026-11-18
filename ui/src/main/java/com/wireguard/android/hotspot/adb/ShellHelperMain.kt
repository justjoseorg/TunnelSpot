/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import com.wireguard.android.BuildConfig
import com.wireguard.android.hotspot.ShellHotspotService
import kotlin.system.exitProcess

@Keep
object ShellHelperMain {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000) { "PhoneHome helper must run as ADB shell, never root." }
        require(args.size == 3)
        val packageName = args[0]
        require(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
        val uid = args[2].toInt()
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        // app_process is a shell runtime, not an installed app process.
        val runtime = Class.forName("dalvik.system.VMRuntime")
        val vm = runtime.getDeclaredMethod("getRuntime").invoke(null)
        runtime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            .invoke(vm, arrayOf("L") as Any)
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val system = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        val shell = system.createPackageContext("com.android.shell", 0)
        check(shell.packageManager.getApplicationInfo(packageName, 0).uid == uid)
        val service = ShellHotspotService(uid)
        val response = shell.contentResolver.call(Uri.parse("content://$packageName.helper"), "attach",
            args[1], Bundle().apply {
                putBinder("helper", service)
                putInt("version", BuildConfig.VERSION_CODE)
            })
        if (response?.getBoolean("accepted") != true) exitProcess(1)
        Looper.loop()
    }
}
