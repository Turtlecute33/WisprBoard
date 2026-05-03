package helium314.keyboard.latin

import helium314.keyboard.event.Event
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.define.DecoderSpecificConstants
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WordComposerTest {
    @Test
    fun resumeSuggestionOnLastComposedWordWithEventsDoesNotThrow() {
        val events = arrayListOf(Event.createEventForCodePointFromUnknownSource('a'.code))
        val pointers = InputPointers(DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH)
        val lastComposedWord = LastComposedWord(
            events,
            pointers,
            "a",
            "a",
            LastComposedWord.NOT_A_SEPARATOR,
            null,
            WordComposer.CAPS_MODE_OFF,
        )

        val composer = WordComposer()
        composer.resumeSuggestionOnLastComposedWord(lastComposedWord)

        assertTrue(composer.isResumed)
    }
}
