package com.nax.atsupager.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the main response from the crypto_data.php endpoint.
 */
data class CryptoData(
    @SerializedName("BTC")
    val btc: String?,

    @SerializedName("ETH")
    val eth: String?,

    // This now expects a list of SignalData objects
    @SerializedName("signals")
    val signals: List<SignalData>?
)
