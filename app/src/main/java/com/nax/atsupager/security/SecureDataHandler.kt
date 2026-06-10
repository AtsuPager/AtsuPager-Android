package com.nax.atsupager.security

import java.util.Arrays

/**
 * Anti-Forensics module for protecting random access memory (RAM).
 * Allows for forced destruction of sensitive data after use,
 * without waiting for the Garbage Collector to trigger.
 */
object SecureDataHandler {

    /**
     * Wipes a byte array with zeros.
     * Use for keys and decrypted data.
     */
    fun wipe(array: ByteArray?) {
        array?.fill(0)
    }

    /**
     * Wipes a character array.
     * Use for passwords and temporary strings.
     */
    fun wipe(array: CharArray?) {
        array?.fill('\u0000')
    }

    /**
     * Safely executes an operation on a ByteArray followed by wiping.
     */
    inline fun <R> useAndWipe(array: ByteArray, block: (ByteArray) -> R): R {
        return try {
            block(array)
        } finally {
            wipe(array)
        }
    }

    /**
     * Helper method to zero out a list of arrays (e.g., keys).
     */
    fun wipeAll(vararg arrays: ByteArray?) {
        arrays.forEach { wipe(it) }
    }
}
