package rkr.tinykeyboard.inputmethod;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NorvigSpellCheckerTest {

    @Test
    public void edits1_generatesAllFourEditTypes() {
        List<String> edits = NorvigSpellChecker.edits1("teh");

        assertTrue(edits.contains("the")); // transposition
        assertTrue(edits.contains("tea")); // replacement (h -> a)
        assertTrue(edits.contains("tech")); // insertion
        assertTrue(edits.contains("th")); // deletion
    }

    @Test
    public void ranksKnownEdits_byCorpusFrequency() {
        NorvigSpellChecker checker = new NorvigSpellChecker(input -> {
            Map<String, Long> frequencies = new HashMap<>();
            frequencies.put("the", 10L);
            frequencies.put("tea", 100L);
            frequencies.put("tec", 1L);
            return frequencies;
        });

        assertEquals(Arrays.asList("tea", "the", "tec"), checker.getSuggestions("teh"));
    }

    @Test
    public void capsSuggestionsAtLimit() {
        NorvigSpellChecker checker = new NorvigSpellChecker(input -> {
            Map<String, Long> frequencies = new HashMap<>();
            for (int i = 0; i < 20; i++) {
                frequencies.put("word" + i, (long) i);
            }
            return frequencies;
        });

        assertEquals(NorvigSpellChecker.MAX_SUGGESTIONS, checker.getSuggestions("word").size());
    }

    @Test
    public void emptyInput_returnsNoSuggestions() {
        NorvigSpellChecker checker = new NorvigSpellChecker(input -> {
            throw new AssertionError("lookup must not run for empty input");
        });

        assertTrue(checker.getSuggestions("").isEmpty());
        assertTrue(checker.getSuggestions(null).isEmpty());
    }

    @Test
    public void noKnownEdits_returnsNoSuggestions() {
        NorvigSpellChecker checker = new NorvigSpellChecker(input -> Collections.emptyMap());

        assertTrue(checker.getSuggestions("qwartzxj").isEmpty());
    }
}
