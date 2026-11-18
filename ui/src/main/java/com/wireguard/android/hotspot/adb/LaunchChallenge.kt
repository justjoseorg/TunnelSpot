/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import java.security.MessageDigest
import java.security.SecureRandom

class LaunchChallenge {
    private var expected: String? = null

    @Synchronized fun begin(): String {
        check(expected == null) { "A helper launch is already pending." }
        return ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { expected = it }
    }

    @Synchronized fun consume(candidate: String?): Boolean {
        val value = expected ?: return false
        if (candidate == null || candidate.length != value.length ||
            !MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII),
                candidate.toByteArray(Charsets.US_ASCII))) return false
        expected = null
        return true
    }

    @Synchronized fun cancel() { expected = null }
}
