package helium314.keyboard

import helium314.keyboard.keyboard.ColorSetting
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationSmokeTest {
    @Test
    fun parsesRepresentativeUserThemeJson() {
        val colors = Json.decodeFromString<List<ColorSetting>>(
            """
            [
              {"name":"KEY_TEXT","auto":false,"color":-16777216},
              {"name":"KEY_BACKGROUND","auto":null,"color":null}
            ]
            """.trimIndent()
        )

        assertEquals(2, colors.size)
        assertEquals("KEY_TEXT", colors[0].name)
        assertEquals(false, colors[0].auto)
        assertEquals(-16777216, colors[0].color)
    }
}
