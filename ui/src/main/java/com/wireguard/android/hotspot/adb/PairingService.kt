/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wireguard.android.R
import com.wireguard.android.hotspot.SetupActivity
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    @Volatile private var pairingPort = -1
    private var discovery: AdbMdns? = null
    @Volatile private var connection: LocalAdb? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.adb_pairing_channel), NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(ID, notification(getString(R.string.adb_pairing_waiting), false))
        discovery = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            pairingPort = if (host != null) port else -1
            if (!busy.get()) {
                notifications.notify(ID, notification(getString(if (pairingPort in 1..65535)
                    R.string.adb_pairing_found else R.string.adb_pairing_waiting), pairingPort in 1..65535))
            }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == PAIR && busy.compareAndSet(false, true)) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()
            scope.launch {
                try {
                    require(pairingPort in 1..65535) { getString(R.string.adb_pairing_missing) }
                    val validated = AdbInputs.pairingCode(code ?: "")
                    notifications.notify(ID, notification(getString(R.string.adb_pairing_busy), false))
                    LocalAdb(this@PairingService).use { adb ->
                        connection = adb
                        check(!cancelled.get())
                        check(adb.pairLocal(pairingPort, validated)) { "Android rejected pairing." }
                    }
                    if (!cancelled.get()) {
                        notifications.notify(RESULT_ID, notification(getString(R.string.adb_paired), false, false))
                    }
                } catch (_: Exception) {
                    if (!cancelled.get()) {
                        notifications.notify(RESULT_ID, notification(getString(R.string.adb_pairing_failed), false, false))
                    }
                } finally {
                    connection = null
                    busy.set(false)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String, reply: Boolean, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.adb_pairing_channel)).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        if (reply) {
            val action = PendingIntent.getService(this, 1, Intent(this, PairingService::class.java).setAction(PAIR),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(NotificationCompat.Action.Builder(0, getString(R.string.adb_enter_code), action)
                .addRemoteInput(androidx.core.app.RemoteInput.Builder(CODE)
                    .setLabel(getString(R.string.adb_code)).build())
                .setAllowGeneratedReplies(false)
                .setAuthenticationRequired(true).build())
        }
        if (ongoing) {
            builder.addAction(0, getString(R.string.adb_cancel),
                PendingIntent.getService(this, 2, Intent(this, PairingService::class.java).setAction(CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        cancelled.set(true)
        runCatching { connection?.close() }
        discovery?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "phonehome-adb-pairing"
        private const val ID = 741
        private const val RESULT_ID = 742
        private const val PAIR = "org.justjose.phonehome.PAIR"
        private const val CANCEL = "org.justjose.phonehome.CANCEL_PAIRING"
        private const val CODE = "pairing_code"
    }
}
