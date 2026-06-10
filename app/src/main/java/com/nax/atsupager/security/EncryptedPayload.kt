package com.nax.atsupager.security

import com.google.gson.annotations.SerializedName

/**
 * A data class to hold the components of a hybrid encrypted message.
 */
data class EncryptedPayload(
    /**
     * The ephemeral public key (or EC point) from the sender, 
     * used by the recipient to derive the shared AES key via ECDH.
     */
    @SerializedName("encrypted_key")
    val encryptedKey: String,

    /**
     * The actual data, encrypted with the AES key. (Base64 encoded)
     * This includes the IV prepended to the ciphertext.
     */
    @SerializedName("encrypted_data")
    val encryptedData: String,

    /**
     * Digital signature of the data,
     * created with the sender's private Bitcoin (secp256k1) key.
     */
    @SerializedName("signature")
    val signature: String? = null,

    /**
     * Timestamp of the message to prevent replay attacks.
     */
    @SerializedName("ts")
    val timestamp: Long = System.currentTimeMillis()
)
