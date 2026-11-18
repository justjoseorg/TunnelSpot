/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import org.junit.Assert.*
import org.junit.Test

class AdbInputsTest {
    @Test fun validatesPortsAndCodesWithoutAcceptingShellInput() {
        assertEquals(65535, AdbInputs.port("65535"))
        assertEquals(1, AdbInputs.port("1"))
        listOf("0", "65536", "-1", "localhost:5555", "5555;id", " 5555", "123456789").forEach {
            assertThrows(IllegalArgumentException::class.java) { AdbInputs.port(it) }
        }
        assertEquals("001234", AdbInputs.pairingCode("001234"))
        listOf("12345", "1234567", "123 45", "１２３４５６").forEach {
            assertThrows(IllegalArgumentException::class.java) { AdbInputs.pairingCode(it) }
        }
    }

    @Test fun quotesApkPathsAndRejectsUntrustedLaunchArguments() {
        val token = "a".repeat(64)
        val command = AdbInputs.launchCommand("/data/app/a'b/base.apk", "org.example.app", token, 10123)
        assertTrue(command.startsWith("CLASSPATH='/data/app/a'\\''b/base.apk' "))
        assertTrue(command.contains("'org.example.app' '$token' 10123"))
        assertThrows(IllegalArgumentException::class.java) {
            AdbInputs.launchCommand("/sdcard/evil.apk", "org.example.app", token, 10123)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbInputs.launchCommand("/data/app/base.apk", "app;id", token, 10123)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbInputs.launchCommand("/data/app/base.apk", "org.example", "bad", 10123)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbInputs.launchCommand("/data/app/base.apk", "org.example", token, 2000)
        }
    }

    @Test fun challengeRejectsWrongValueAndReplayAndCanBeCancelled() {
        val challenge = LaunchChallenge()
        assertFalse(challenge.consume("before-start"))
        val first = challenge.begin()
        assertFalse(challenge.consume(null))
        assertFalse(challenge.consume("b".repeat(64).takeIf { it != first } ?: "c".repeat(64)))
        assertTrue(challenge.consume(first))
        assertFalse(challenge.consume(first))
        val second = challenge.begin()
        assertNotEquals(first, second)
        challenge.cancel()
        assertFalse(challenge.consume(second))
        val third = challenge.begin()
        assertFalse(challenge.consume(second))
        assertTrue(challenge.consume(third))
    }
}
