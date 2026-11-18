/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 PhoneHome contributors
 */
package com.wireguard.android.hotspot.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        private const val ALIAS = "phonehome-adb-storage"

        @Synchronized fun load(context: Context): AdbIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                }.generateKey()
            val file = AtomicFile(File(context.noBackupFilesDir, "adb-identity.enc"))
            if (file.baseFile.exists()) {
                val encrypted = file.readFully()
                require(encrypted.size > 28) { "ADB identity is damaged. Clear app data and pair again." }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                }
                val plain = cipher.doFinal(encrypted, 12, encrypted.size - 12)
                return try {
                    DataInputStream(ByteArrayInputStream(plain)).use {
                        val keySize = it.readInt()
                        require(keySize in 1..8192)
                        val encoded = ByteArray(keySize).also(it::readFully)
                        val privateKey = try {
                            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
                        } finally { encoded.fill(0) }
                        AdbIdentity(privateKey, CertificateFactory.getInstance("X.509").generateCertificate(it))
                    }
                } finally { plain.fill(0) }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val provider = BouncyCastleProvider()
            val subject = X500Name("CN=TunnelSpot Wireless ADB")
            val now = System.currentTimeMillis()
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                JcaX509v3CertificateBuilder(subject, BigInteger(160, SecureRandom()),
                    Date(now - 86_400_000), Date(now + 3_155_760_000_000L), subject, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
            val plain = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use {
                    val encoded = pair.private.encoded
                    try { it.writeInt(encoded.size); it.write(encoded); it.write(certificate.encoded) }
                    finally { encoded.fill(0) }
                }
            }.toByteArray()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                val output = file.startWrite()
                try {
                    output.write(cipher.iv)
                    output.write(cipher.doFinal(plain))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
            } finally { plain.fill(0) }
            return AdbIdentity(pair.private, certificate)
        }
    }
}
