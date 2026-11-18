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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.wireguard.android.R
import rikka.shizuku.Shizuku

class SetupActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            status.setText(if (result == PackageManager.PERMISSION_GRANTED) R.string.hotspot_ready else R.string.hotspot_permission_denied)
            if (result == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionResult)
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
            content.addView(MaterialButton(this).apply {
                setText(resource)
                setOnClickListener { click() }
            })
        }
        text(R.string.hotspot_only, 20f)
        text(R.string.hotspot_intro, 16f)
        status = text(R.string.hotspot_no_shizuku, 16f)
        button(R.string.hotspot_shizuku_install) {
            startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
        button(R.string.hotspot_authorize) {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, R.string.hotspot_no_shizuku, Toast.LENGTH_LONG).show()
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                permissionResult.onRequestPermissionResult(1, PackageManager.PERMISSION_GRANTED)
            } else {
                Shizuku.requestPermission(1)
            }
        }
        button(R.string.hotspot_settings) {
            runCatching { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
        text(R.string.hotspot_requirements, 15f)
        text(R.string.hotspot_attribution, 14f)
        button(R.string.hotspot_source) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/justjoseorg/PhoneHome")))
        }
        button(R.string.hotspot_done) {
            getSharedPreferences("phonehome", MODE_PRIVATE).edit().putBoolean("setup_seen", true).apply()
            finish()
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(content) })
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.setText(if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                R.string.hotspot_ready else R.string.hotspot_no_shizuku)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }
}
