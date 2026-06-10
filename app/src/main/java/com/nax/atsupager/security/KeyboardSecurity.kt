package com.nax.atsupager.security

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions

/**
 * Module for ensuring input security at the system keyboard level.
 * Prevents data leakage through cloud-based keyboard services (Gboard, SwiftKey, etc.)
 */
object KeyboardSecurity {

    /**
     * Configuration for message input that prevents the keyboard 
     * from remembering and analyzing text.
     */
    val secureChatOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        autoCorrect = false, // Auto-correction can cache typed words in the OS dictionary
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Send,
        // The noPersonalizedLearning flag forcibly disables keyboard learning on this field
        platformImeOptions = PlatformImeOptions("noPersonalizedLearning")
    )

    /**
     * Configuration for password or PIN input.
     */
    val securePasswordOptions = KeyboardOptions(
        autoCorrect = false,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        platformImeOptions = PlatformImeOptions("noPersonalizedLearning")
    )
}
