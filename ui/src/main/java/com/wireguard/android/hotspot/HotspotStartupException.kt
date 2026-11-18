/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

class HotspotStartupException(
    stage: String,
    cause: Exception,
    helperDied: Boolean,
    cleanupFailure: Exception? = null
) : Exception(buildString {
    append(stage).append(": ")
    if (helperDied) {
        append("The built-in helper disconnected. Restart it in Hotspot setup. ")
    }
    append(cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName)
    if (cleanupFailure != null) {
        append(" Hotspot shutdown is unconfirmed; turn Wi-Fi hotspot off in Android settings. ")
        append(cleanupFailure.message?.takeIf { it.isNotBlank() } ?: cleanupFailure.javaClass.simpleName)
    }
}, cause) {
    init { if (cleanupFailure != null) addSuppressed(cleanupFailure) }
}
