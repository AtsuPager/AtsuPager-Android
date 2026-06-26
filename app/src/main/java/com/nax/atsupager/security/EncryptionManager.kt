/*
 * Copyright (c) 2026 AtsuPager Author. All rights reserved.
 * Published for security audit and educational purposes only.
 */

package com.nax.atsupager.security

import android.util.Base64
import android.util.Log
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.base.LegacyAddress
import java.io.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.*
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EncryptionManager"

/**
 * Unified label for all transport keys (messages, calls, streaming).
 * Must be identical on all devices.
 */
private const val HKDF_TRANSPORT_INFO = "AtsuPager_Transport_V1"

data class StreamingEncryptionResult(
    val cipher: Cipher,
    val iv: ByteArray,
    val encryptedKeyBase64: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamingEncryptionResult) return false
        if (!iv.contentEquals(other.iv)) return false
        return encryptedKeyBase64 == other.encryptedKeyBase64
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + encryptedKeyBase64.hashCode()
        return result
    }
}

/**
 * Вспомогательный класс для одновременной записи в несколько потоков.
 */
private class MultiOutputStream(private val streams: List<OutputStream>) : OutputStream() {
    override fun write(b: Int) { streams.forEach { it.write(b) } }
    override fun write(b: ByteArray) { streams.forEach { it.write(b) } }
    override fun write(b: ByteArray, off: Int, len: Int) { streams.forEach { it.write(b, off, len) } }
    override fun flush() { streams.forEach { it.flush() } }
    override fun close() { streams.forEach { it.close() } }
}

@Singleton
class EncryptionManager @Inject constructor(
    private val keyStorageManager: KeyStorageManager
) {

    fun isKeyValidForAddress(publicKeyBase64: String, expectedAddress: String): Boolean {
        return try {
            val pubKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val ecKey = ECKey.fromPublicOnly(pubKeyBytes)
            @Suppress("DEPRECATION")
            val derivedAddress = LegacyAddress.fromKey(MainNetParams.get(), ecKey).toString()
            derivedAddress == expectedAddress
        } catch (e: Exception) {
            Log.e(TAG, "Key validation failed for address: $expectedAddress", e)
            false
        }
    }

    private fun getSharedSecret(myUserId: String, hisPubKeyBase64: String): ByteArray? {
        val myKey = keyStorageManager.getBitcoinKey(myUserId) ?: return null
        return try {
            val hisPubKeyBytes = Base64.decode(hisPubKeyBase64, Base64.NO_WRAP)
            val hisKey = ECKey.fromPublicOnly(hisPubKeyBytes)
            val hisPoint = hisKey.pubKeyPoint
            val commonPoint = hisPoint.multiply(myKey.privKey).normalize()
            val secret = commonPoint.affineXCoord.encoded
            
            hkdfDerive(secret, HKDF_TRANSPORT_INFO).also {
                SecureDataHandler.wipe(secret)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ECDH failed", e)
            null
        }
    }

    private fun getSharedSecretWithEphemeral(ephemeralKey: ECKey, hisPubKeyBase64: String): ByteArray? {
        return try {
            val hisPubKeyBytes = Base64.decode(hisPubKeyBase64, Base64.NO_WRAP)
            val hisKey = ECKey.fromPublicOnly(hisPubKeyBytes)
            val hisPoint = hisKey.pubKeyPoint
            val commonPoint = hisPoint.multiply(ephemeralKey.privKey).normalize()
            val secret = commonPoint.affineXCoord.encoded
            
            hkdfDerive(secret, HKDF_TRANSPORT_INFO).also {
                SecureDataHandler.wipe(secret)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ephemeral ECDH failed", e)
            null
        }
    }

    private fun hkdfDerive(sharedSecret: ByteArray, info: String): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        val salt = ByteArray(32) 
        hmac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = hmac.doFinal(sharedSecret)

        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update(info.toByteArray(StandardCharsets.UTF_8))
        hmac.update(0x01.toByte())
        
        val okm = hmac.doFinal()
        SecureDataHandler.wipe(prk)
        return okm
    }

    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, 600000, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encryptWithRawKey(data: ByteArray, key: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    fun decryptWithRawKey(encryptedData: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val iv = encryptedData.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.doFinal(encryptedData.copyOfRange(12, encryptedData.size))
        } catch (e: Exception) {
            Log.e(TAG, "Manual decryption failed", e)
            null
        }
    }

    fun prepareStreamingEncryption(publicKeyBase64: String, senderUserId: String): StreamingEncryptionResult? {
        return try {
            val ephemeralKey = ECKey()
            val sharedKey = getSharedSecretWithEphemeral(ephemeralKey, publicKeyBase64) ?: return null
            val aesKey = SecretKeySpec(sharedKey, "AES")
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            
            val ephemeralPubKeyBase64 = Base64.encodeToString(ephemeralKey.pubKey, Base64.NO_WRAP)
            SecureDataHandler.wipe(sharedKey)
            
            StreamingEncryptionResult(cipher, iv, ephemeralPubKeyBase64)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare streaming encryption", e)
            null
        }
    }

    /**
     * Создает случайный ключ и IV для группового/общего шифрования файла.
     */
    fun prepareGroupStreamingEncryption(): StreamingEncryptionResult? {
        return try {
            val fileKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val aesKey = SecretKeySpec(fileKey, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            
            val keyBase64 = Base64.encodeToString(fileKey, Base64.NO_WRAP)
            StreamingEncryptionResult(cipher, iv, keyBase64)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare group streaming encryption", e)
            null
        }
    }

    /**
     * Создает поток, который шифрует данные сразу для двух целей (хранилище и транспорт).
     */
    fun getDualEncryptingStream(
        storageOutput: OutputStream, storageKey: ByteArray,
        transportOutput: OutputStream, transportResult: StreamingEncryptionResult
    ): OutputStream? {
        return try {
            val storageStream = getEncryptingStreamForStorage(storageOutput, storageKey) ?: return null
            
            transportOutput.write(transportResult.iv)
            val transportStream = CipherOutputStream(transportOutput, transportResult.cipher)
            
            MultiOutputStream(listOf(storageStream, transportStream))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create dual encrypting stream", e)
            null
        }
    }

    /**
     * Перешифровывает файл из локального ключа в целевой транспортный ключ.
     */
    fun reEncryptFile(source: File, sourceKey: ByteArray, targetFile: File, targetResult: StreamingEncryptionResult): Boolean {
        return try {
            val fis = FileInputStream(source)
            val input = getDecryptingStreamFromStorage(fis, sourceKey) ?: return false
            val fos = FileOutputStream(targetFile)
            fos.write(targetResult.iv)
            val output = CipherOutputStream(fos, targetResult.cipher)
            
            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Re-encryption failed", e)
            false
        }
    }

    fun decryptFileToByteArray(file: File, key: ByteArray): ByteArray? {
        if (!file.exists() || file.length() < 28) return null
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val fis = FileInputStream(file)
            val iv = ByteArray(12)
            if (fis.read(iv) != 12) { fis.close(); return null }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encryptedData = fis.readBytes()
            fis.close()
            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            Log.e(TAG, "Fast decryption failed", e)
            null
        }
    }

    fun encrypt(data: CharArray, recipientPublicKeyBase64: String, senderUserId: String): EncryptedPayload? {
        return try {
            val ephemeralKey = ECKey()
            val sharedKey = getSharedSecretWithEphemeral(ephemeralKey, recipientPublicKeyBase64) ?: return null
            val aesKey = SecretKeySpec(sharedKey, "AES")
            
            val charBuffer = CharBuffer.wrap(data)
            val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
            val dataBytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(dataBytes)

            val encryptedData = encryptWithAes(dataBytes, aesKey)
            val encDataBase64 = Base64.encodeToString(encryptedData, Base64.NO_WRAP)
            val ephemeralPubKeyBase64 = Base64.encodeToString(ephemeralKey.pubKey, Base64.NO_WRAP)
            val timestamp = System.currentTimeMillis()

            val signature = sign("$encDataBase64|$ephemeralPubKeyBase64|$timestamp", senderUserId)

            SecureDataHandler.wipe(sharedKey)
            SecureDataHandler.wipe(dataBytes)

            EncryptedPayload(ephemeralPubKeyBase64, encDataBase64, signature, timestamp)
        } catch (e: Exception) { 
            Log.e(TAG, "Encryption failed", e)
            null 
        }
    }

    fun encrypt(data: String, recipientPublicKeyBase64: String, senderUserId: String): EncryptedPayload? {
        val chars = data.toCharArray()
        return try {
            encrypt(chars, recipientPublicKeyBase64, senderUserId)
        } finally {
            SecureDataHandler.wipe(chars)
        }
    }

    fun decryptToCharArray(payload: EncryptedPayload, recipientUserId: String, senderPublicKeyBase64: String? = null): CharArray? {
        val bytes = decryptToByteArray(payload, recipientUserId, senderPublicKeyBase64) ?: return null
        return try {
            val charBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            val chars = CharArray(charBuffer.remaining())
            charBuffer.get(chars)
            SecureDataHandler.wipe(bytes)
            chars
        } catch (e: Exception) { null }
    }

    fun decrypt(payload: EncryptedPayload, recipientUserId: String, senderPublicKeyBase64: String? = null): String? {
        val chars = decryptToCharArray(payload, recipientUserId, senderPublicKeyBase64) ?: return null
        return try {
            String(chars).also { SecureDataHandler.wipe(chars) }
        } catch (e: Exception) { null }
    }

    fun decryptToByteArray(payload: EncryptedPayload, recipientUserId: String, senderPublicKeyBase64: String? = null): ByteArray? {
        return try {
            val finalSenderPubKey = senderPublicKeyBase64 ?: throw Exception("Sender public key required")
            val signature = payload.signature ?: return null
            
            val now = System.currentTimeMillis()
            if (Math.abs(now - payload.timestamp) > 300000) return null

            if (!verify("${payload.encryptedData}|${payload.encryptedKey}|${payload.timestamp}", signature, finalSenderPubKey)) return null

            val sharedKey = getSharedSecret(recipientUserId, payload.encryptedKey) ?: return null
            val aesKey = SecretKeySpec(sharedKey, "AES")
            val result = decryptWithAesToByteArray(Base64.decode(payload.encryptedData, Base64.NO_WRAP), aesKey)
            SecureDataHandler.wipe(sharedKey)
            result
        } catch (e: Exception) { 
            Log.e(TAG, "Decryption failed", e)
            null 
        }
    }

    fun verify(data: String, signatureBase64: String, publicKeyBase64: String): Boolean {
        return try {
            val pubKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val ecKey = ECKey.fromPublicOnly(pubKeyBytes)
            @Suppress("DEPRECATION")
            val address = LegacyAddress.fromKey(MainNetParams.get(), ecKey).toString()
            keyStorageManager.verifyBitcoinSignature(address, data, signatureBase64)
        } catch (e: Exception) {
            Log.e(TAG, "Verification error", e)
            false
        }
    }

    fun getDecryptingStreamForTransport(input: InputStream, ephemeralKeyBase64: String, userId: String, senderPubKeyBase64: String): InputStream? {
        return try {
            val sharedKey = getSharedSecret(userId, ephemeralKeyBase64) ?: return null
            val aesKey = SecretKeySpec(sharedKey, "AES")
            val iv = ByteArray(12)
            if (input.read(iv) != 12) { SecureDataHandler.wipe(sharedKey); return null }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            SecureDataHandler.wipe(sharedKey)
            CipherInputStream(input, cipher)
        } catch (e: Exception) { null }
    }

    fun getEncryptingStreamForStorage(output: OutputStream, key: ByteArray): OutputStream? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            output.write(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            CipherOutputStream(output, cipher)
        } catch (e: Exception) { null }
    }

    fun getDecryptingStreamFromStorage(input: InputStream, key: ByteArray): InputStream? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val iv = ByteArray(12)
            if (input.read(iv) != 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            CipherInputStream(input, cipher)
        } catch (e: Exception) { null }
    }

    private fun encryptWithAes(data: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(data)
    }

    private fun decryptWithAesToByteArray(enc: ByteArray, key: SecretKey): ByteArray {
        val iv = enc.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return cipher.doFinal(enc.copyOfRange(12, enc.size))
    }

    private fun sign(data: String, userId: String): String? {
        return keyStorageManager.signWithBitcoinKey(userId, data)
    }
}
