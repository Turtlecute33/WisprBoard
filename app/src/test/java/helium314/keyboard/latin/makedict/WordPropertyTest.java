package helium314.keyboard.latin.makedict;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class WordPropertyTest {
    @Test
    public void equalsAndHashCodeIncludeShortcutAndNgramPresence() {
        final WordProperty noExtras = nativeWordWithFlags(false, false);
        final WordProperty sameNoExtras = nativeWordWithFlags(false, false);
        final WordProperty withShortcut = nativeWordWithFlags(true, false);
        final WordProperty withNgram = nativeWordWithFlags(false, true);

        assertEquals(noExtras, sameNoExtras);
        assertEquals(noExtras.hashCode(), sameNoExtras.hashCode());
        assertNotEquals(noExtras, withShortcut);
        assertNotEquals(noExtras, withNgram);
        assertNotEquals(withShortcut, withNgram);
    }

    @SuppressWarnings("unchecked")
    private static WordProperty nativeWordWithFlags(final boolean hasShortcut,
            final boolean hasNgram) {
        return new WordProperty(
                new int[] {'w', 'o', 'r', 'd', 0},
                false,
                false,
                hasNgram,
                hasShortcut,
                false,
                new int[] {100, -1, 0, 0},
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );
    }

    private static WordProperty wordWith(final boolean hasShortcut, final boolean hasNgram) {
        final ArrayList<WeightedString> shortcuts = new ArrayList<>();
        if (hasShortcut) {
            shortcuts.add(new WeightedString("s", 10));
        }
        final ArrayList<WeightedString> bigrams = hasNgram ? new ArrayList<>() : null;
        if (hasNgram) {
            bigrams.add(new WeightedString("next", 10));
        }
        return new WordProperty("word", new ProbabilityInfo(100), shortcuts, bigrams,
                false, false);
    }
}
