/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wireguard.android.R
import com.wireguard.android.hotspot.adb.AdbInputs
import com.wireguard.android.hotspot.adb.HelperBridge
import com.wireguard.android.hotspot.adb.LocalAdb
import com.wireguard.android.hotspot.adb.PairingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val controls = mutableListOf<MaterialButton>()
    private var busy = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPairing()
        else status.setText(R.string.adb_notifications_needed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            fitsSystemWindows = true
        }
        fun text(resource: Int, size: Float) = TextView(this).apply {
            setText(resource)
            textSize = size
            setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this)
        }
        fun button(resource: Int, click: () -> Unit) {
            val button = MaterialButton(this).apply { setText(resource); setOnClickListener { click() } }
            controls.add(button)
            content.addView(button)
        }
        fun input(label: Int, secret: Boolean = false): TextInputEditText {
            val field = TextInputEditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or
                    if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0
                isSaveEnabled = !secret
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                setSingleLine()
            }
            content.addView(TextInputLayout(this).apply { setHint(label); addView(field) })
            return field
        }
        text(R.string.hotspot_only, 20f)
        text(R.string.hotspot_intro, 16f)
        status = text(R.string.adb_helper_needed, 16f)
        text(R.string.adb_setup_steps, 15f)
        button(R.string.adb_open_settings) {
            runCatching { startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        }
        button(R.string.adb_pair_notification) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startPairing()
        }
        text(R.string.adb_manual_pairing, 15f)
        val pairingPort = input(R.string.adb_pairing_port)
        val pairingCode = input(R.string.adb_code, true)
        button(R.string.adb_pair_manual) {
            val port = pairingPort.text?.toString().orEmpty()
            val code = pairingCode.text?.toString().orEmpty()
            pairingCode.text?.clear()
            perform(R.string.adb_pairing_busy) {
                LocalAdb(this).use { check(it.pairLocal(AdbInputs.port(port), AdbInputs.pairingCode(code))) }
                getString(R.string.adb_paired)
            }
        }
        val connectionPort = input(R.string.adb_connection_port)
        button(R.string.adb_start_helper) {
            val value = connectionPort.text?.toString().orEmpty().trim()
            perform(R.string.adb_helper_starting) {
                HelperBridge.start(applicationContext, value.takeIf { it.isNotEmpty() }?.let(AdbInputs::port))
                getString(R.string.hotspot_ready)
            }
        }
        button(R.string.adb_stop_helper) {
            perform(R.string.adb_helper_stopping) {
                val backend = HotspotBackend.instance
                backend?.stop()
                HelperBridge.stop()
                getString(R.string.adb_helper_needed)
            }
        }
        button(R.string.hotspot_settings) {
            runCatching { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
        text(R.string.hotspot_requirements, 15f)
        text(R.string.hotspot_attribution, 14f)
        button(R.string.hotspot_source) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/justjoseorg/TunnelSpot")))
        }
        button(R.string.hotspot_done) {
            getSharedPreferences("phonehome", MODE_PRIVATE).edit().putBoolean("setup_seen", true).apply()
            finish()
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(content) })
    }

    private fun startPairing() {
        ContextCompat.startForegroundService(this, Intent(this, PairingService::class.java))
        status.setText(R.string.adb_pairing_waiting)
    }

    private fun perform(message: Int, action: () -> String) {
        if (busy) return
        busy = true
        controls.forEach { it.isEnabled = false }
        status.setText(message)
        lifecycleScope.launch {
            try {
                status.text = withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                status.text = getString(R.string.adb_setup_error, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
                controls.forEach { it.isEnabled = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && !busy) {
            val helper = getString(if (HelperBridge.ready()) R.string.hotspot_ready else R.string.adb_helper_needed)
            val problem = HotspotBackend.instance?.lastProblem
            status.text = if (problem == null) helper else getString(R.string.hotspot_last_problem, helper, problem)
        }
    }
}
