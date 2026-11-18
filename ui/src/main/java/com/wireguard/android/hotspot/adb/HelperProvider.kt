/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

class HelperProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000) { "Only the local Android ADB shell may attach a helper." }
        require(method == "attach")
        val binder = requireNotNull(extras?.getBinder("helper"))
        val accepted = HelperBridge.accept(arg, extras.getInt("version", -1), binder)
        return Bundle().apply { putBoolean("accepted", accepted) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}
