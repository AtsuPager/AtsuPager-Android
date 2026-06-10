/*
 * AtsuPager - Secure Bitcoin-based Messenger
 * Copyright (c) 2026 AtsuLab. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * For commercial licensing inquiries, contact AtsuLab.
 */

package com.nax.atsupager.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.base.LegacyAddress
import org.bitcoinj.params.MainNetParams
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.*
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeyStorageManager"

@Singleton
class KeyStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bip39Manager: Bip39Manager
) {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val prefs by lazy {
        context.getSharedPreferences("secure_key_storage", Context.MODE_PRIVATE)
    }

    private fun getKeyAlias(userId: String) = "atsu_pager_key_$userId"
    private fun getDeviceKeyAlias() = "atsu_pager_device_key"
    private fun getDbPassKey(userId: String) = "db_pass_encrypted_$userId"
    private fun getDeviceDbPassKey() = "device_db_pass_encrypted"
    private fun getMediaKeyAlias(userId: String) = "media_key_encrypted_$userId"
    private fun getPinKey(userId: String) = "pin_encrypted_$userId"
    private fun getMnemonicKey(userId: String) = "mnemonic_encrypted_$userId"

    /**
     * Returns an ECKey (Bitcoin) object for the user based on their mnemonic.
     */
    fun getBitcoinKey(userId: String): ECKey? {
        val mnemonicChars = getMnemonicAsCharArray(userId) ?: return null
        return try {
            val seed = bip39Manager.toSeed(mnemonicChars)
            val key = ECKey.fromPrivate(seed.copyOfRange(0, 32), true)
            
            // Cleanup
            SecureDataHandler.wipe(mnemonicChars)
            SecureDataHandler.wipe(seed)
            key
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive ECKey", e)
            null
        }
    }

    fun getBitcoinPublicKeyBase64(userId: String): String? {
        val key = getBitcoinKey(userId)
        val pubKey = key?.let { Base64.encodeToString(it.pubKey, Base64.NO_WRAP) }
        return pubKey
    }

    fun getBitcoinAddressFromMnemonic(mnemonicChars: CharArray): String {
        val seed = bip39Manager.toSeed(mnemonicChars)
        val key = ECKey.fromPrivate(seed.copyOfRange(0, 32), true)
        @Suppress("DEPRECATION")
        val address = LegacyAddress.fromKey(MainNetParams.get(), key).toString()
        SecureDataHandler.wipe(seed)
        return address
    }

    fun signWithBitcoinKey(userId: String, data: String): String? {
        val key = getBitcoinKey(userId) ?: return null
        return try {
            @Suppress("DEPRECATION")
            key.signMessage(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign with Bitcoin key", e)
            null
        }
    }

    fun verifyBitcoinSignature(address: String, message: String, signature: String): Boolean {
        return try {
            val signedKey = ECKey.signedMessageToKey(message, signature)
            @Suppress("DEPRECATION")
            val signedAddress = LegacyAddress.fromKey(MainNetParams.get(), signedKey).toString()
            address == signedAddress
        } catch (e: Exception) {
            Log.e(TAG, "Bitcoin signature verification failed", e)
            false
        }
    }

    fun saveMnemonic(userId: String, mnemonicChars: CharArray) {
        val mnemonicBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(mnemonicChars)).run {
            val b = ByteArray(remaining())
            get(b)
            b
        }
        val alias = getKeyAlias(userId)
        generateKeyPair(alias)
        try {
            val publicKey = keyStore.getCertificate(alias).publicKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(mnemonicBytes)
            prefs.edit().putString(getMnemonicKey(userId), Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save mnemonic", e)
        } finally {
            SecureDataHandler.wipe(mnemonicBytes)
        }
    }

    fun getMnemonicAsCharArray(userId: String): CharArray? {
        val encryptedBase64 = prefs.getString(getMnemonicKey(userId), null) ?: return null
        val alias = getKeyAlias(userId)
        return try {
            val privateKey = keyStore.getKey(alias, null) as PrivateKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val decryptedBytes = cipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP))
            
            val charBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes))
            val chars = CharArray(charBuffer.remaining())
            charBuffer.get(chars)
            
            SecureDataHandler.wipe(decryptedBytes)
            chars
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mnemonic", e)
            null
        }
    }

    private fun generateKeyPair(alias: String) {
        try {
            if (keyStore.containsAlias(alias)) return
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT or
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)

            // StrongBox support (Android 9+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val hasStrongBox = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                if (hasStrongBox) {
                    builder.setIsStrongBoxBacked(true)
                }
            }

            kpg.initialize(builder.build())
            kpg.generateKeyPair()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate KeyStore pair", e)
        }
    }

    fun savePin(userId: String, pin: CharArray) {
        val alias = getKeyAlias(userId)
        generateKeyPair(alias)
        val pinBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pin)).run {
            val b = ByteArray(remaining())
            get(b)
            b
        }
        try {
            val publicKey = keyStore.getCertificate(alias).publicKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(pinBytes)
            prefs.edit().putString(getPinKey(userId), Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save PIN", e)
        } finally {
            SecureDataHandler.wipe(pinBytes)
        }
    }

    fun verifyPin(userId: String, inputPin: CharArray): Boolean {
        val encryptedPinBase64 = prefs.getString(getPinKey(userId), null) ?: return false
        val alias = getKeyAlias(userId)
        val inputPinBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(inputPin)).run {
            val b = ByteArray(remaining())
            get(b)
            b
        }
        return try {
            val privateKey = keyStore.getKey(alias, null) as PrivateKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val decryptedPinBytes = cipher.doFinal(Base64.decode(encryptedPinBase64, Base64.NO_WRAP))
            
            val isValid = MessageDigest.isEqual(decryptedPinBytes, inputPinBytes)
            
            SecureDataHandler.wipe(decryptedPinBytes)
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify PIN", e)
            false
        } finally {
            SecureDataHandler.wipe(inputPinBytes)
        }
    }

    fun hasPin(userId: String): Boolean = prefs.contains(getPinKey(userId))
    fun removePin(userId: String) = prefs.edit().remove(getPinKey(userId)).apply()

    fun getDatabasePassphrase(userId: String): ByteArray {
        return getOrGenerateEncryptedKey(getKeyAlias(userId), getDbPassKey(userId), 32)
    }

    fun getDevicePersistentPassphrase(): ByteArray {
        return getOrGenerateEncryptedKey(getDeviceKeyAlias(), getDeviceDbPassKey(), 32)
    }

    fun getMediaEncryptionKey(userId: String): ByteArray {
        return getOrGenerateEncryptedKey(getKeyAlias(userId), getMediaKeyAlias(userId), 32)
    }

    private fun getOrGenerateEncryptedKey(alias: String, prefsKey: String, size: Int): ByteArray {
        val encryptedBase64 = prefs.getString(prefsKey, null)
        generateKeyPair(alias)
        if (encryptedBase64 != null) {
            try {
                val privateKey = keyStore.getKey(alias, null) as PrivateKey
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.DECRYPT_MODE, privateKey)
                return cipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt key $prefsKey", e)
            }
        }
        val newKey = ByteArray(size)
        SecureRandom().nextBytes(newKey)
        try {
            val publicKey = keyStore.getCertificate(alias).publicKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(newKey)
            prefs.edit().putString(prefsKey, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store encrypted key $prefsKey", e)
        }
        return newKey
    }

    fun purgeProfileData(userId: String) {
        try {
            val alias = getKeyAlias(userId)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            prefs.edit().remove(getMnemonicKey(userId)).remove(getPinKey(userId)).remove(getDbPassKey(userId)).remove(getMediaKeyAlias(userId)).apply()
            Log.d(TAG, "Purged security data for user: $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error purging profile data", e)
        }
    }
}
