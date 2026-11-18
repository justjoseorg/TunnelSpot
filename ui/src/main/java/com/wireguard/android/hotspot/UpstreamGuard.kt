/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

object UpstreamGuard {
    fun problem(expected: String, actual: String?, tethered: Boolean): String? = when {
        !tethered -> "Wi-Fi hotspot is no longer active."
        actual != expected -> "Android changed the hotspot upstream; Wi-Fi sharing was stopped."
        else -> null
    }
}
