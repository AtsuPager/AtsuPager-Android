package com.nax.atsupager.security

import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Bip39Manager @Inject constructor() {

    /**
     * Generates a new 12-word mnemonic phrase as a CharArray.
     */
    fun generateMnemonic(): CharArray {
        val entropy = ByteArray(16) // 128 bits for 12 words
        SecureRandom().nextBytes(entropy)
        val words = MnemonicCode.INSTANCE.toMnemonic(entropy)
        val phrase = words.joinToString(" ").toCharArray()
        // The intermediate list of words will be cleared by GC, but we return a CharArray for manual wiping
        return phrase
    }

    /**
     * Converts a mnemonic phrase (CharArray) to a seed (ByteArray)
     * without creating intermediate Strings.
     */
    fun toSeed(mnemonic: CharArray, passphrase: String = ""): ByteArray {
        // BIP39 seed derivation: PBKDF2 with HMAC-SHA512, 2048 iterations
        // Salt is "mnemonic" + passphrase
        val salt = ("mnemonic" + passphrase).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(mnemonic, salt, 2048, 512)
        return try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            skf.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Validates a mnemonic phrase.
     */
    fun validateMnemonic(mnemonic: List<String>): Boolean {
        return try {
            MnemonicCode.INSTANCE.check(mnemonic)
            true
        } catch (e: MnemonicException) {
            false
        }
    }
}
