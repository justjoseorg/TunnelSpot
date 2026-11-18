/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

object AdbInputs {
    const val HOST = "127.0.0.1"

    fun port(value: String): Int {
        require(value.matches(Regex("[0-9]{1,5}"))) { "Enter a port number from 1 to 65535." }
        return value.toInt().also { require(it in 1..65535) { "Enter a port number from 1 to 65535." } }
    }

    fun pairingCode(value: String): String {
        require(value.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code shown by Android." }
        return value
    }

    fun launchCommand(apk: String, packageName: String, token: String, uid: Int): String {
        require(apk.startsWith("/data/app/") && !apk.contains('\n') && !apk.contains('\u0000'))
        require(packageName.matches(Regex("[a-zA-Z0-9_.]+")) && uid >= 10_000)
        require(token.matches(Regex("[0-9a-f]{64}")))
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return "CLASSPATH=${quote(apk)} nohup /system/bin/app_process /system/bin " +
            "--nice-name=tunnelspot_helper com.wireguard.android.hotspot.adb.ShellHelperMain " +
            "${quote(packageName)} ${quote(token)} $uid > /dev/null 2>&1 < /dev/null &"
    }
}
