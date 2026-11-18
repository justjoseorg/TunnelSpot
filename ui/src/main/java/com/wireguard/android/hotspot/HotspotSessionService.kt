/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HotspotSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.hotspot_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(getString(R.string.hotspot_starting)))
        scope.launch {
            while (isActive) {
                delay(1_000)
                val backend = HotspotBackend.instance ?: continue
                val problem = runCatching { backend.healthProblem() }.getOrElse {
                    it.message ?: "Could not verify hotspot routing."
                }
                if (problem != null) {
                    stopping = true
                    val cleanup = runCatching { backend.stop() }
                    notifications.notify(ERROR_ID, notification(getString(R.string.hotspot_error,
                        problem + (cleanup.exceptionOrNull()?.let { " ${it.message}" } ?: "")), false))
                    if (cleanup.isSuccess) {
                        stopSelf()
                        break
                    }
                    stopping = false
                    notifications.notify(ID, notification(getString(R.string.hotspot_error, problem)))
                    delay(2_000)
                    continue
                }
                backend.activeName()?.let {
                    notifications.notify(ID, notification(getString(R.string.hotspot_active, it)))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP && !stopping) {
            stopping = true
            scope.launch {
                runCatching {
                    HotspotBackend.instance?.stop()
                    Application.getTunnelManager().saveState()
                }.onFailure {
                    stopping = false
                    notifications.notify(ERROR_ID, notification(getString(R.string.hotspot_error, it.message), false))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String, controls: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(controls)
            .apply {
                if (controls) addAction(0, getString(R.string.hotspot_stop),
                    PendingIntent.getService(this@HotspotSessionService, 1,
                        Intent(this@HotspotSessionService, HotspotSessionService::class.java).setAction(STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "phonehome-hotspot"
        private const val ID = 731
        private const val ERROR_ID = 732
        private const val STOP = "org.justjose.phonehome.STOP"
    }
}
