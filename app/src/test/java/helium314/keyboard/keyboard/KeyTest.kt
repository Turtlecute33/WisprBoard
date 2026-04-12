package helium314.keyboard.keyboard

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.App
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class KeyTest {
    @Test
    fun disabledLegacyVoiceIconStillResolvesDrawable() {
        val context = ApplicationProvider.getApplicationContext<App>()
        KeyboardIconsSet.instance.loadIcons(context)

        val key = Key(
            null,
            "shortcut_key",
            KeyCode.VOICE_INPUT,
            null,
            null,
            0,
            Key.BACKGROUND_TYPE_ACTION,
            0,
            0,
            100,
            100,
            0,
            0,
        )
        key.setEnabled(false)

        assertNotNull(key.getIcon(KeyboardIconsSet.instance, 255))
    }
}
