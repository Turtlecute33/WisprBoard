package helium314.keyboard.latin.voice

import android.text.InputType
import helium314.keyboard.latin.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextFixManagerTest {
    @Test
    fun blocksSensitiveTextFixFields() {
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                isPasswordField = true,
                noLearning = false,
                incognitoModeEnabled = false,
            )
        )
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_TEXT,
                isPasswordField = false,
                noLearning = true,
                incognitoModeEnabled = false,
            )
        )
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_TEXT,
                isPasswordField = false,
                noLearning = false,
                incognitoModeEnabled = true,
            )
        )
    }

    @Test
    fun blocksUnsupportedTextFixFields() {
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_PHONE,
                isPasswordField = false,
                noLearning = false,
                incognitoModeEnabled = false,
            )
        )
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                isPasswordField = false,
                noLearning = false,
                incognitoModeEnabled = false,
            )
        )
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                isPasswordField = false,
                noLearning = false,
                incognitoModeEnabled = false,
            )
        )
    }

    @Test
    fun allowsGeneralTextInput() {
        assertNull(
            TextFixManager.getBlockedErrorResId(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
                isPasswordField = false,
                noLearning = false,
                incognitoModeEnabled = false,
            )
        )
    }
}
